package edu.campus.agent.core;

import edu.campus.agent.net.BackendClient;
import edu.campus.agent.net.BackendClient.OfflineException;
import edu.campus.agent.net.BackendClient.RejectedException;
import edu.campus.agent.net.Messages.ClaimedJob;
import edu.campus.agent.print.PrintEngine;
import edu.campus.agent.print.PrintJob;
import edu.campus.agent.print.PrinterDiscovery;
import edu.campus.agent.print.SpoolerMonitor;
import edu.campus.agent.util.TempFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Prints ONE document of a paid order (a "job"). Used by every printer's
 * worker thread.
 *
 * The order of steps is what prevents double printing:
 *
 *   1. tell the backend DOWNLOADING
 *   2. download and check the file, and prepare the sheets: lay the chosen pages
 *      out on the chosen paper, check the printer can do every setting
 *                                            (problem -> give it back or fail it;
 *                                             nothing has printed)
 *   3. tell the backend SUBMITTED           (if that fails -> do NOT print)
 *   4. write "sent" to the journal on disk
 *   5. send the sheets to Windows           -- point of no return --
 *   6. watch the Windows queue until it leaves
 *   7. tell the backend COMPLETED / FAILED  (if that fails, the journal
 *                                             remembers and reports later)
 *
 * After step 5 nothing in this program ever sends the same document again.
 * A second print only happens if a staff member presses "Print again".
 */
public class JobProcessor {

    private static final Logger log = LoggerFactory.getLogger(JobProcessor.class);

    private final BackendClient backend;
    private final PrintEngine engine;
    private final SpoolerMonitor spooler;
    private final Journal journal;
    private final TempFiles temp;
    private final PrinterHealth health;
    private final long maxFileSizeBytes;
    private volatile Consumer<String> capabilityDoubt = id -> { };

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService leaseTimer = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "lease-renewal");
        t.setDaemon(true);
        return t;
    });

    public JobProcessor(BackendClient backend, PrintEngine engine, SpoolerMonitor spooler, Journal journal,
                        TempFiles temp, PrinterHealth health, long maxFileSizeBytes) {
        this.backend = backend;
        this.engine = engine;
        this.spooler = spooler;
        this.journal = journal;
        this.temp = temp;
        this.health = health;
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    /** Called with a printer id when it could not do what the server believed it can: look at it again. */
    public void onCapabilityDoubt(Consumer<String> action) {
        this.capabilityDoubt = action;
    }

    public boolean isBusy() {
        return !inFlight.isEmpty();
    }

    public void process(ClaimedJob j) {
        String id = j.jobId();
        String tag = "Order " + j.pickupCode() + (j.documentCount() > 1
                ? " file " + j.documentNumber() + "/" + j.documentCount() : "");
        inFlight.add(id);
        Path file = null;
        PrintEngine.Prepared prepared = null;
        boolean sentToPrinter = false;
        Lease lease = new Lease(j);

        try {
            if (journal.has(id)) {
                // Our own disk says this was already sent once. Never again.
                log.error("{}: already sent to a printer earlier. Not printing it twice.", tag);
                reportFinal(j, "FAILED", "ALREADY_SENT",
                        "This PC had already sent this file to a printer once. Staff: check the tray.");
                return;
            }

            // 1 + 2: fetch, check, prepare. Nothing can have printed yet.
            backend.reportStatus(id, j.claimToken(), "DOWNLOADING", "", "");
            file = temp.create(id, extensionOf(j.fileType()));
            try {
                backend.downloadTo(backend.downloadUrl(id, j.claimToken()), file);
            } catch (OfflineException | RejectedException e) {
                if (e instanceof RejectedException r && r.notMine()) throw r;
                giveBack(j, "DOWNLOAD_FAILED", e.getMessage());
                return;
            }
            String problem = check(j, file);
            if (problem != null) {
                log.warn("{}: {}", tag, problem);
                backend.reportStatus(id, j.claimToken(), "FAILED", "FILE_CHECK_FAILED", problem);
                return;
            }
            if (PrinterDiscovery.find(j.windowsPrinterName()).isEmpty()) {
                giveBack(j, "PRINTER_MISSING", "Windows printer \"" + j.windowsPrinterName() + "\" not found");
                return;
            }
            PrintJob job = PrintJob.of(j);
            try {
                prepared = engine.prepare(file, job);
            } catch (PrintEngine.CannotPrint e) {
                // The printer cannot do a setting the student chose: never print it differently.
                log.warn("{}: {} cannot print it as chosen: {}", tag, j.windowsPrinterName(), e.getMessage());
                capabilityDoubt.accept(j.printerId());
                giveBack(j, "PRINTER_CANNOT", e.getMessage());
                return;
            } catch (Exception e) {
                log.error("{}: the file could not be prepared for printing", tag, e);
                backend.reportStatus(id, j.claimToken(), "FAILED", "PREPARE_FAILED",
                        "The file could not be prepared for printing: " + e.getMessage());
                return;
            }
            if (lease.lost()) {
                log.warn("{}: the backend took this document back while preparing. Not printing.", tag);
                return;
            }

            // 3: claim the right to print. If the backend does not confirm, we do not print.
            try {
                backend.reportStatus(id, j.claimToken(), "SUBMITTED", "", "");
            } catch (OfflineException e) {
                log.warn("{}: could not confirm with the backend, so NOT printing. {}", tag, e.getMessage());
                giveBack(j, "NO_CONFIRMATION", "Lost contact before printing");
                return;
            }

            // 4 + 5: remember on disk, then print.
            journal.recordSent(id, j.claimToken(), j.pickupCode());
            sentToPrinter = true;
            String queueName;
            try {
                queueName = engine.print(prepared, file);
            } catch (Exception e) {
                log.error("{}: Windows refused the document: {}", tag, e.getMessage());
                health.setAttention(j.printerId(), "Last document refused: " + e.getMessage());
                reportFinal(j, "FAILED", "PRINTER_REFUSED",
                        "The printer refused the document: " + e.getMessage());
                return;
            }
            log.info("{}: sent to \"{}\"", tag, j.windowsPrinterName());

            // 6: wait for the paper to come out.
            SpoolerMonitor.Result r = spooler.awaitCompletion(j.windowsPrinterName(), queueName,
                    detail -> health.setAttention(j.printerId(), detail));

            // 7: report.
            if (r.outcome() == SpoolerMonitor.Outcome.REMOVED) {
                reportFinal(j, "FAILED", "REMOVED_FROM_QUEUE",
                        "The document was removed from the printer queue (" + r.detail() + "). Staff: check the tray.");
            } else {
                reportFinal(j, "COMPLETED", "", r.detail());
                log.info("{}: done ({})", tag, r.detail());
            }

        } catch (RejectedException e) {
            if (e.notMine()) {
                log.warn("{}: this document is no longer assigned to this PC{}", tag,
                        sentToPrinter ? " (it was already sent to the printer)" : "; not printing it");
            } else {
                log.error("{}: backend refused: {}", tag, e.getMessage());
            }
        } catch (OfflineException e) {
            log.warn("{}: lost contact with the backend ({}). {}", tag, e.getMessage(), sentToPrinter
                    ? "The result will be reported when the connection is back."
                    : "Nothing printed; the backend will put the document back in the queue.");
        } catch (Exception e) {
            log.error("{}: unexpected problem", tag, e);
            if (!sentToPrinter) {
                giveBack(j, "AGENT_ERROR", e.getMessage());
            }
        } finally {
            lease.stop();
            if (prepared != null) {
                try {
                    prepared.close();
                } catch (Exception ignored) {
                }
            }
            temp.shred(file);
            inFlight.remove(id);
        }
    }

    /**
     * Reports a final result. If the backend cannot be reached, the journal
     * keeps the result and flushJournal() sends it later.
     */
    private void reportFinal(ClaimedJob j, String status, String code, String message) {
        try {
            backend.reportStatus(j.jobId(), j.claimToken(), status, code, message);
            journal.clear(j.jobId());
            if ("COMPLETED".equals(status)) health.setAttention(j.printerId(), null);
        } catch (RejectedException e) {
            log.warn("Order {}: backend did not accept {} ({}); forgetting it", j.pickupCode(), status, e.status);
            journal.clear(j.jobId());
        } catch (IOException e) {
            Journal.Entry entry = journal.get(j.jobId());
            if (entry != null) {
                journal.recordOutcome(entry, status, code, message);
            }
            log.warn("Order {}: result {} saved; will report when the connection is back", j.pickupCode(), status);
        }
    }

    /**
     * Reports results the backend has not heard yet: after a restart, or after
     * the internet came back. Never prints anything.
     */
    public void flushJournal() {
        for (Journal.Entry e : journal.all()) {
            if (inFlight.contains(e.orderId())) continue;
            String status = e.outcomeStatus() != null ? e.outcomeStatus() : "COMPLETED";
            String message = e.outcomeStatus() != null ? e.outcomeMessage()
                    : "Sent to the printer before the PC restarted; not printed again. Staff: check the tray.";
            try {
                backend.reportStatus(e.orderId(), e.claimToken(), status, e.outcomeCode(), message);
                journal.clear(e.orderId());
                log.info("Order {}: reported {} (delayed)", e.pickupCode(), status);
            } catch (RejectedException r) {
                log.info("Order {}: backend already has a final result; clearing", e.pickupCode());
                journal.clear(e.orderId());
            } catch (IOException offline) {
                return;   // still offline; next heartbeat tries again
            }
        }
    }

    /** Hands the document back BEFORE anything printed. */
    private void giveBack(ClaimedJob j, String code, String message) {
        try {
            String now = backend.release(j.jobId(), j.claimToken(), code, message);
            log.warn("Order {}: given back ({}), now {}", j.pickupCode(), code, now);
        } catch (Exception e) {
            log.warn("Order {}: could not give back ({}); the backend will recover it itself",
                    j.pickupCode(), e.getMessage());
        }
    }

    /** Size, type and checksum must match what the backend recorded. */
    private String check(ClaimedJob j, Path file) throws IOException {
        long size = Files.size(file);
        if (size == 0) return "Downloaded file is empty";
        if (size > maxFileSizeBytes) return "Downloaded file is larger than allowed";
        if (j.fileSizeBytes() > 0 && size != j.fileSizeBytes()) {
            return "Size mismatch: expected " + j.fileSizeBytes() + " bytes, got " + size;
        }
        byte[] head = new byte[1024];
        int n;
        try (InputStream in = Files.newInputStream(file)) {
            n = in.readNBytes(head, 0, head.length);
        }
        String type = detect(head, n);
        if (!j.fileType().equals(type)) {
            return "File type mismatch: expected " + j.fileType() + ", got " + (type == null ? "unknown" : type);
        }
        if (j.sha256() != null && !j.sha256().isBlank()) {
            String actual = sha256(file);
            if (!actual.equalsIgnoreCase(j.sha256())) {
                return "Checksum mismatch: the file changed after upload";
            }
        }
        return null;
    }

    private static String detect(byte[] b, int n) {
        if (n >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "PNG";
        if (n >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "JPEG";
        for (int i = 0; i + 4 < n; i++) {
            if (b[i] == '%' && b[i + 1] == 'P' && b[i + 2] == 'D' && b[i + 3] == 'F' && b[i + 4] == '-') return "PDF";
        }
        return null;
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String extensionOf(String fileType) {
        return switch (fileType) {
            case "PNG" -> ".png";
            case "JPEG" -> ".jpg";
            default -> ".pdf";
        };
    }

    /**
     * Keeps the document's lease alive while this PC works on it, so the
     * backend's recovery does not take it away from a long print.
     */
    private final class Lease {
        private final AtomicBoolean lost = new AtomicBoolean(false);
        private final ScheduledFuture<?> task;

        Lease(ClaimedJob j) {
            long every = Math.max(20, j.leaseSeconds() / 3);
            task = leaseTimer.scheduleAtFixedRate(() -> {
                try {
                    backend.renewLease(j.jobId(), j.claimToken());
                } catch (RejectedException e) {
                    if (e.notMine()) {
                        lost.set(true);
                        log.warn("Order {}: lease refused, the backend has taken it back", j.pickupCode());
                    }
                } catch (Exception e) {
                    log.debug("Lease renewal for {} failed: {}", j.pickupCode(), e.toString());
                }
            }, every, every, TimeUnit.SECONDS);
        }

        boolean lost() {
            return lost.get();
        }

        void stop() {
            task.cancel(false);
        }
    }
}
