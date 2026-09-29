package edu.campus.agent.core;

import edu.campus.agent.net.BackendClient;
import edu.campus.agent.net.BackendClient.OfflineException;
import edu.campus.agent.net.BackendClient.RejectedException;
import edu.campus.agent.net.Messages.ClaimedJob;
import edu.campus.agent.net.Messages.PrinterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One thread per printer. It asks for the next document THIS printer can do
 * (the server only hands out what the printer's features allow), prints it,
 * waits until the paper is out, then asks again.
 *
 * Because each printer only asks when it is free, work spreads over the
 * printers by itself: a free printer takes the next document, a busy one waits.
 */
public class PrinterWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(PrinterWorker.class);

    private final AtomicReference<PrinterConfig> config;
    private final BackendClient backend;
    private final JobProcessor processor;
    private final PrinterHealth health;
    private final Duration poll;
    private volatile boolean running = true;
    private Thread thread;

    public PrinterWorker(PrinterConfig config, BackendClient backend, JobProcessor processor,
                         PrinterHealth health, Duration poll) {
        this.config = new AtomicReference<>(config);
        this.backend = backend;
        this.processor = processor;
        this.health = health;
        this.poll = poll;
    }

    public void start() {
        thread = new Thread(this, "printer-" + config.get().name());
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops after the current document (one already sent is never interrupted). */
    public void stop() {
        running = false;
    }

    public boolean isAlive() {
        return thread != null && thread.isAlive();
    }

    public void update(PrinterConfig newConfig) {
        config.set(newConfig);
    }

    @Override
    public void run() {
        log.info("Worker started for {} (\"{}\")", config.get().name(), config.get().windowsPrinterName());
        Duration backoff = poll;
        while (running) {
            PrinterConfig c = config.get();
            try {
                if (!c.enabled() || !health.isPresent(c.id())) {
                    sleep(Duration.ofSeconds(10));        // switched off, or not installed in Windows
                    continue;
                }
                if (health.notReady(c.id()) != null) {
                    // offline, out of paper, jammed...: paid documents stay safe on the server (another
                    // printer may take them) instead of waiting in this printer's Windows queue
                    sleep(Duration.ofSeconds(5));
                    continue;
                }
                Optional<ClaimedJob> job = backend.claim(c.id());
                backoff = poll;
                if (job.isPresent()) {
                    processor.process(job.get());
                } else {
                    sleep(poll);
                }
            } catch (OfflineException e) {
                log.debug("{}: backend unreachable ({}), retrying in {}s", c.name(), e.getMessage(),
                        backoff.toSeconds());
                sleep(backoff);
                backoff = backoff.multipliedBy(2).compareTo(Duration.ofSeconds(60)) > 0
                        ? Duration.ofSeconds(60) : backoff.multipliedBy(2);
            } catch (RejectedException e) {
                log.error("{}: backend refused ({}). Retrying in 60 s.", c.name(), e.getMessage());
                sleep(Duration.ofSeconds(60));
            } catch (Exception e) {
                log.error("{}: unexpected problem", c.name(), e);
                sleep(Duration.ofSeconds(10));
            }
        }
        log.info("Worker stopped for {}", config.get().name());
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
