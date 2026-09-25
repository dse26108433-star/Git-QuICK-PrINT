package edu.campus.agent.core;

import edu.campus.agent.config.AgentConfig;
import edu.campus.agent.net.BackendClient;
import edu.campus.agent.net.BackendClient.OfflineException;
import edu.campus.agent.net.BackendClient.RejectedException;
import edu.campus.agent.net.Messages.HeartbeatResult;
import edu.campus.agent.net.Messages.PrinterConfig;
import edu.campus.agent.net.Messages.PrinterReport;
import edu.campus.agent.print.PrinterDiscovery;
import edu.campus.agent.util.TempFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Runs the Xerox center PC:
 *   - every ~20 s tells the backend it is alive and what each printer looks like,
 *     and gets back the list of printers to run;
 *   - starts one PrinterWorker per printer, stops workers for removed ones;
 *   - reports delayed results from the journal;
 *   - cleans old temp files every hour.
 */
public class Supervisor {

    private static final Logger log = LoggerFactory.getLogger(Supervisor.class);

    private final AgentConfig cfg;
    private final BackendClient backend;
    private final OrderProcessor processor;
    private final PrinterHealth health;
    private final TempFiles temp;

    private final Map<String, PrinterWorker> workers = new HashMap<>();
    private volatile List<PrinterConfig> printers = List.of();
    private volatile boolean running = true;
    private boolean wasOffline = false;
    private volatile Instant lastContact;        // last good answer from the backend
    private volatile String problem;             // why the backend cannot be reached / refused us, or null
    private final Object wake = new Object();
    private Instant lastSweep = Instant.EPOCH;

    public Supervisor(AgentConfig cfg, BackendClient backend, OrderProcessor processor,
                      PrinterHealth health, TempFiles temp) {
        this.cfg = cfg;
        this.backend = backend;
        this.processor = processor;
        this.health = health;
        this.temp = temp;
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
                log.warn("Backend unreachable ({}). Printing continues for orders already here; "
                        + "new orders arrive when the connection is back.", e.getMessage());
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
        if (!fresh.equals(printers)) {
            applyPrinters(fresh);
        }
        processor.flushJournal();
    }

    private void applyPrinters(List<PrinterConfig> fresh) {
        Set<String> keep = new HashSet<>();
        for (PrinterConfig p : fresh) {
            keep.add(p.id());
            boolean present = PrinterDiscovery.find(p.windowsPrinterName()).isPresent();
            health.setPresent(p.id(), present);
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
                    present ? "" : "  <-- NOT FOUND in Windows. Fix the name in the printers table.");
        }
        for (Iterator<Map.Entry<String, PrinterWorker>> it = workers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, PrinterWorker> e = it.next();
            if (!keep.contains(e.getKey())) {
                e.getValue().stop();
                it.remove();
            }
        }
        if (fresh.isEmpty()) {
            log.warn("No printers are set up in the database yet (seed.sql step 2).");
        }
        printers = fresh;
    }

    public void stop() {
        running = false;
        workers.values().forEach(PrinterWorker::stop);
        wakeUp();
    }

    /** When the backend last answered, or null if it never did. */
    public Instant lastContact() { return lastContact; }

    /** Why the backend cannot be used right now, or null when all is well. */
    public String problem() { return problem; }

    /** The printers this PC runs, as the backend last described them. */
    public List<PrinterConfig> printers() { return printers; }
}
