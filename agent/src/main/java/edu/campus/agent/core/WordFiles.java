package edu.campus.agent.core;

import edu.campus.agent.net.BackendClient;
import edu.campus.agent.net.BackendClient.OfflineException;
import edu.campus.agent.net.BackendClient.RejectedException;
import edu.campus.agent.net.Messages.ConversionJob;
import edu.campus.agent.util.TempFiles;
import edu.campus.agent.word.WordEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Word files: students add them like any file, and this PC turns each one
 * into a PDF with its own Microsoft Word before the student pays, so the
 * pages they see are the pages that print.
 *
 * One file at a time, in the order they came: ask the server for the next
 * one, fetch it (it must be exactly the file the server looked into), let
 * Word save it as PDF, send the PDF back. A file Word cannot open is reported
 * with the reason; the student is then told to save it as PDF.
 *
 * Runs only while this PC's Word passed its test (WordEngine.check): the
 * heartbeat tells the server, and the server offers Word files to students
 * only while such a PC is online.
 */
public class WordFiles {

    private static final Logger log = LoggerFactory.getLogger(WordFiles.class);
    private static final Duration CHECK_AGAIN = Duration.ofMinutes(2);       // while Word is not ready

    private final BackendClient backend;
    private final WordEngine engine;
    private final TempFiles temp;
    private final Duration poll;
    private final long maxBytes;
    private final boolean enabled;

    private volatile boolean running;
    private volatile Instant checkedAt = Instant.EPOCH;
    private volatile boolean checkNow;
    private Thread thread;

    public WordFiles(BackendClient backend, WordEngine engine, TempFiles temp, Duration poll, long maxBytes, boolean enabled) {
        this.backend = backend;
        this.engine = engine;
        this.temp = temp;
        this.poll = poll;
        this.maxBytes = maxBytes;
        this.enabled = enabled;
    }

    public synchronized void start() {
        if (!enabled || running) return;
        running = true;
        thread = new Thread(this::run, "word-files");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        engine.close();
    }

    /** What the heartbeat tells the server and the Station's screen shows. */
    public WordEngine.State state() {
        return enabled ? engine.state() : new WordEngine.State(false, "Word files are switched off on this PC.");
    }

    /** Staff pressed "Check again" (Word was installed or activated meanwhile). */
    public void checkAgain() {
        checkNow = true;
    }

    private void run() {
        check();
        while (running) {
            try {
                if (checkNow) check();
                if (!engine.state().ready()) {
                    if (Duration.between(checkedAt, Instant.now()).compareTo(CHECK_AGAIN) > 0) check();
                    pause(Duration.ofSeconds(5));
                    continue;
                }
                Optional<ConversionJob> job = backend.claimConversion();
                if (job.isEmpty()) {
                    pause(poll);
                    continue;
                }
                handle(job.get());
            } catch (OfflineException e) {
                pause(Duration.ofSeconds(10));                  // the heartbeat says so on the screen
            } catch (RejectedException e) {
                // 404: a server from before Word files. Anything else: this PC is not welcome right now.
                pause(e.status == 404 ? Duration.ofMinutes(5) : Duration.ofSeconds(30));
            } catch (Exception e) {
                log.warn("Word files: {}", e.toString());
                pause(Duration.ofSeconds(10));
            }
        }
    }

    private void check() {
        checkNow = false;
        WordEngine.State before = engine.state();
        WordEngine.State now = engine.check();
        checkedAt = Instant.now();
        if (!now.ready() && (before.ready() || !now.note().equals(before.note()))) {
            log.warn("Word files cannot be taken on this PC: {}", now.note());
        }
    }

    private void handle(ConversionJob job) {
        String id = job.documentId();
        Path in = null;
        Path out = null;
        long started = System.nanoTime();
        try {
            if (job.fileSizeBytes() > maxBytes) {
                fail(id, "FAILED", "The file is larger than this PC takes.");
                return;
            }
            in = temp.create("word-" + id, ".docx");
            out = in.resolveSibling("word-" + id + ".pdf");
            Files.deleteIfExists(out);
            backend.downloadTo(job.downloadUrl(), in);
            // Only the very file the server looked into is opened in Word: never one swapped in afterwards.
            if (Files.size(in) > maxBytes || job.sha256() == null || job.sha256().isBlank()
                    || !sha256(in).equalsIgnoreCase(job.sha256())) {
                log.warn("Word file \"{}\": not the file the server checked; it is not opened", job.fileName());
                fail(id, "CHANGED", "Not the file the server checked.");
                return;
            }
            int seconds = Math.max(20, Math.min(60, job.secondsAllowed() - 15));
            WordEngine.Result r = engine.convert(in, out, Duration.ofSeconds(seconds));
            if (!r.ok()) {
                log.warn("Word file \"{}\" could not be turned into a PDF: {} {}", job.fileName(), r.code(), r.message());
                fail(id, r.code(), r.message());
                if ("ENGINE".equals(r.code())) check();        // is Word still usable here at all?
                return;
            }
            if (!WordEngine.looksLikePdf(out)) {
                fail(id, "FAILED", "Word wrote no PDF.");
                return;
            }
            if (Files.size(out) > maxBytes) {
                fail(id, "TOO_LARGE", "The PDF is " + Files.size(out) / (1024 * 1024) + " MB.");
                return;
            }
            backend.uploadTo(job.uploadUrl(), out, job.uploadContentType());
            String status = backend.conversionDone(id);
            log.info("Word file \"{}\": {} page(s) in {} ms ({})", job.fileName(), r.pages(),
                    (System.nanoTime() - started) / 1_000_000, status);
        } catch (RejectedException e) {
            // the student took the file away meanwhile, or the server gave up on it: nothing to do
            log.info("Word file \"{}\" is no longer wanted ({})", job.fileName(), e.getMessage());
        } catch (OfflineException e) {
            log.warn("Word file \"{}\": the connection dropped; the server hands it out again", job.fileName());
        } catch (Exception e) {
            log.warn("Word file \"{}\": {}", job.fileName(), e.toString());
            try {
                fail(id, "FAILED", e.toString());
            } catch (Exception ignored) {
                // the server gives up on it by itself after a while
            }
        } finally {
            temp.shred(in);
            temp.shred(out);
        }
    }

    private void fail(String id, String code, String message) throws IOException {
        backend.conversionFailed(id, code, message);
    }

    private void pause(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            // stop() or checkAgain(): the loop looks at what is asked
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
