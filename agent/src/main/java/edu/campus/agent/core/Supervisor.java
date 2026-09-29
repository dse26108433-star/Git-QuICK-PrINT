package edu.campus.agent.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.campus.agent.config.AgentConfig;
import edu.campus.agent.net.BackendClient;
import edu.campus.agent.net.BackendClient.OfflineException;
import edu.campus.agent.net.BackendClient.RejectedException;
import edu.campus.agent.net.Messages.HeartbeatResult;
import edu.campus.agent.net.Messages.PrinterConfig;
import edu.campus.agent.net.Messages.PrinterReport;
import edu.campus.agent.print.CapabilityCache;
import edu.campus.agent.print.CapabilityDiscovery;
import edu.campus.agent.print.PrintTicket;
import edu.campus.agent.print.PrinterDiscovery;
import edu.campus.agent.util.TempFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the Xerox center PC:
 *   - every ~20 s tells the backend it is alive and what each printer looks like,
 *     and gets back the list of printers to run;
 *   - starts one PrinterWorker per printer, stops workers for removed ones;
 *   - finds out what each printer can do (paper sizes, two-sided, stapling...)
 *     and keeps the server up to date, so students only see what is possible:
 *     at start, when printers change, when staff ask, every 30 minutes, and at
 *     once when a printer turned out unable to do something;
 *   - reports delayed results from the journal;
 *   - cleans old temp files every hour.
 */
public class Supervisor {

    private static final Logger log = LoggerFactory.getLogger(Supervisor.class);
    private static final Duration LOOK_AGAIN = Duration.ofMinutes(30);

    private final AgentConfig cfg;
    private final BackendClient backend;
    private final JobProcessor processor;
    private final PrinterHealth health;
    private final TempFiles temp;
    private final CapabilityCache capabilities;
    private final PrintTicket tickets;

    private final Map<String, PrinterWorker> workers = new HashMap<>();
    private volatile List<PrinterConfig> printers = List.of();
    private volatile boolean running = true;
    private boolean wasOffline = false;
    private volatile Instant lastContact;        // last good answer from the backend
    private volatile String problem;             // why the backend cannot be reached / refused us, or null
    private final Object wake = new Object();
    private Instant lastSweep = Instant.EPOCH;

    private final ExecutorService discovery = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "printer-features");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean discovering = new AtomicBoolean(false);
    private final Set<String> lookAgain = ConcurrentHashMap.newKeySet();      // printer ids
    private final Map<String, Instant> lookedAt = new ConcurrentHashMap<>();  // windows name -> when

    public Supervisor(AgentConfig cfg, BackendClient backend, JobProcessor processor, PrinterHealth health,
                      TempFiles temp, CapabilityCache capabilities, PrintTicket tickets) {
        this.cfg = cfg;
        this.backend = backend;
        this.processor = processor;
        this.health = health;
        this.temp = temp;
        this.capabilities = capabilities;
        this.tickets = tickets;
        processor.onCapabilityDoubt(id -> {
            lookAgain.add(id);
            wakeUp();
        });
    }

    /** Runs until stop() is called. */
    public void run() {
        processor.flushJournal();
        while (running) {
            beat();
            if (Instant.now().isAfter(lastSweep.plus(Duration.ofHours(1)))) {
                temp.sweepStale();
                lastSweep = Instant.now();
            }
            synchronized (wake) {
                try {
                    wake.wait(cfg.heartbeat().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Talk to the backend now instead of at the next heartbeat (e.g. printers were just changed). */
    public void wakeUp() {
        synchronized (wake) {
            wake.notifyAll();
        }
    }

    /** Staff pressed "Scan again": read every printer's features from Windows now. */
    public void lookAgainAtAllPrinters() {
        for (PrinterConfig p : printers) lookAgain.add(p.id());
        wakeUp();
    }

    private void beat() {
        List<PrinterReport> reports = new ArrayList<>();
        for (PrinterConfig p : printers) {
            boolean present = PrinterDiscovery.find(p.windowsPrinterName()).isPresent();
            health.setPresent(p.id(), present);
            String attention = health.attention(p.id());
            if (!present) {
                reports.add(new PrinterReport(p.id(), "MISSING",
                        "Windows has no printer called \"" + p.windowsPrinterName() + "\""));
            } else if (attention != null) {
                reports.add(new PrinterReport(p.id(), "ERROR", attention));
            } else {
                reports.add(new PrinterReport(p.id(), "READY", ""));
            }
        }

        HeartbeatResult result;
        try {
            result = backend.heartbeat(reports);
        } catch (OfflineException e) {
            problem = "Cannot reach the server: " + e.getMessage();
            if (!wasOffline) {
                log.warn("Backend unreachable ({}). Printing continues for documents already here; "
                        + "new ones arrive when the connection is back.", e.getMessage());
                wasOffline = true;
            }
            return;
        } catch (RejectedException e) {
            problem = "The server refused this PC: " + e.getMessage();
            log.error("Backend refused this PC: {}", e.getMessage());
            return;
        } catch (Exception e) {
            problem = "Problem talking to the server: " + e.getMessage();
            log.error("Heartbeat problem", e);
            return;
        }
        lastContact = Instant.now();
        problem = null;
        if (wasOffline) {
            log.info("Connection to the backend is back");
            wasOffline = false;
        }

        List<PrinterConfig> fresh = result.printers() == null ? List.of() : result.printers();
        if (!sameSetup(fresh, printers)) {
            applyPrinters(fresh);
        } else {
            printers = fresh;            // the server's features version may have changed
        }
        processor.flushJournal();
        keepFeaturesUpToDate();
    }

    /** Workers only care about what they print on; the features' version is handled separately. */
    private static boolean sameSetup(List<PrinterConfig> a, List<PrinterConfig> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            PrinterConfig x = a.get(i), y = b.get(i);
            if (!x.id().equals(y.id()) || !x.name().equals(y.name())
                    || !x.windowsPrinterName().equals(y.windowsPrinterName()) || x.supportsColor() != y.supportsColor()
                    || x.acceptsBw() != y.acceptsBw() || x.enabled() != y.enabled()) {
                return false;
            }
        }
        return true;
    }

    private void applyPrinters(List<PrinterConfig> fresh) {
        Set<String> keep = new HashSet<>();
        for (PrinterConfig p : fresh) {
            keep.add(p.id());
            boolean present = PrinterDiscovery.find(p.windowsPrinterName()).isPresent();
            health.setPresent(p.id(), present);
            if (present && tickets != null) tickets.restore(p.windowsPrinterName());   // after a power cut
            PrinterWorker w = workers.get(p.id());
            if (w == null || !w.isAlive()) {
                w = new PrinterWorker(p, backend, processor, health, cfg.pollInterval());
                workers.put(p.id(), w);
                w.start();
            } else {
                w.update(p);
            }
            log.info("Printer {}: \"{}\" {}{}{}", p.name(), p.windowsPrinterName(),
                    p.supportsColor() ? "colour" : "B/W only",
                    p.enabled() ? "" : " (switched OFF on the counter screen)",
                    present ? "" : "  <-- NOT FOUND in Windows. Fix the name on the Printers screen.");
        }
        for (Iterator<Map.Entry<String, PrinterWorker>> it = workers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, PrinterWorker> e = it.next();
            if (!keep.contains(e.getKey())) {
                e.getValue().stop();
                it.remove();
            }
        }
        if (fresh.isEmpty()) {
            log.warn("No printers are set up for this PC yet (Printers screen in Campus Print Station).");
        }
        printers = fresh;
    }

    /**
     * Sends a printer's features when the server's version differs from what
     * this PC found, and (in the background) looks at printers again when due.
     */
    private void keepFeaturesUpToDate() {
        List<PrinterConfig> due = new ArrayList<>();
        for (PrinterConfig p : printers) {
            if (PrinterDiscovery.find(p.windowsPrinterName()).isEmpty()) continue;
            Instant at = lookedAt.get(p.windowsPrinterName());
            if (p.rescan() || lookAgain.contains(p.id()) || at == null || at.isBefore(Instant.now().minus(LOOK_AGAIN))) {
                due.add(p);
            }
        }
        if (!due.isEmpty() && discovering.compareAndSet(false, true)) {
            List<String> names = due.stream().map(PrinterConfig::windowsPrinterName).distinct().toList();
            due.forEach(p -> lookAgain.remove(p.id()));
            discovery.submit(() -> {
                try {
                    Map<String, CapabilityDiscovery.Discovered> found = CapabilityDiscovery.discover(names);
                    found.forEach(capabilities::put);
                    for (String n : names) lookedAt.put(n, Instant.now());
                    log.info("Printer features read from Windows: {}", found.keySet());
                    sendChangedFeatures(due.stream().map(PrinterConfig::id).toList());
                } catch (Exception e) {
                    log.warn("Reading printer features failed: {}", e.toString());
                } finally {
                    discovering.set(false);
                }
            });
        }
        sendChangedFeatures(List.of());
    }

    /** For each printer this PC knows the features of: send them if the server has another version (or was asked). */
    private void sendChangedFeatures(List<String> forced) {
        for (PrinterConfig p : printers) {
            var d = capabilities.get(p.windowsPrinterName());
            if (d.isEmpty()) continue;
            ObjectNode caps = d.get().capabilities();
            String hash = CapabilityDiscovery.hash(caps);
            if (hash.equals(p.capabilitiesHash()) && !forced.contains(p.id())) continue;
            try {
                backend.sendCapabilities(p.id(), caps, hash);
                log.info("Printer {}: features sent to the server ({} paper sizes{}{})", p.name(),
                        caps.path("paperSizes").size(), caps.path("duplex").asBoolean() ? ", two-sided" : "",
                        caps.path("finishing").isEmpty() ? "" : ", " + caps.path("finishing"));
            } catch (Exception e) {
                log.debug("Could not send features of {}: {}", p.name(), e.toString());
            }
        }
    }

    public void stop() {
        running = false;
        workers.values().forEach(PrinterWorker::stop);
        discovery.shutdownNow();
        wakeUp();
    }

    /** When the backend last answered, or null if it never did. */
    public Instant lastContact() { return lastContact; }

    /** Why the backend cannot be used right now, or null when all is well. */
    public String problem() { return problem; }

    /** The printers this PC runs, as the backend last described them. */
    public List<PrinterConfig> printers() { return printers; }

    /** What this PC found each printer can do (for the Station's screens). */
    public CapabilityCache capabilities() { return capabilities; }
}
