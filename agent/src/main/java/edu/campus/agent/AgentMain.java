package edu.campus.agent;

import edu.campus.agent.config.AgentConfig;
import edu.campus.agent.core.Journal;
import edu.campus.agent.core.JobProcessor;
import edu.campus.agent.core.PrinterHealth;
import edu.campus.agent.core.Supervisor;
import edu.campus.agent.net.AgentVersion;
import edu.campus.agent.net.BackendClient;
import edu.campus.agent.print.*;
import edu.campus.agent.util.TempFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;

/**
 * The Xerox center PC program.
 *
 *   java -jar print-agent.jar                 run (reads agent.yml next to you)
 *   java -jar print-agent.jar C:\path\agent.yml
 *   java -jar print-agent.jar --list-printers show the exact Windows printer names
 */
public final class AgentMain {

    private static final Logger log = LoggerFactory.getLogger(AgentMain.class);

    private AgentMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--list-printers")) {
            PrinterSmokeTest.main(new String[]{"--list"});
            return;
        }

        Path configPath = Path.of(args.length > 0 ? args[0] : "agent.yml");
        AgentConfig cfg = AgentConfig.load(configPath);
        log.info("Campus Print agent {} starting (backend {})", AgentVersion.VALUE, cfg.backendUrl);

        Parts parts = build(cfg);
        Supervisor supervisor = parts.supervisor();
        JobProcessor processor = parts.processor();

        log.info("Printers installed in Windows: {}",
                PrinterDiscovery.all().stream().map(javax.print.PrintService::getName).toList());

        Thread main = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Stopping: finishing the document in progress (up to 30 s)...");
            supervisor.stop();
            long until = System.currentTimeMillis() + 30_000;
            while (processor.isBusy() && System.currentTimeMillis() < until) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
            main.interrupt();
            log.info("Stopped");
        }, "shutdown"));

        supervisor.run();
    }

    /** The running pieces of the agent. */
    public record Parts(Supervisor supervisor, JobProcessor processor) {}

    /** Builds everything the agent needs from its settings. Call supervisor().run() to start. */
    public static Parts build(AgentConfig cfg) throws Exception {
        BackendClient backend = new BackendClient(cfg);
        Journal journal = new Journal(cfg.work());
        TempFiles temp = new TempFiles(cfg.work(), Duration.ofMinutes(cfg.tempRetentionMinutes));
        PrinterHealth health = new PrinterHealth();

        PrintStrategy strategy = "external".equalsIgnoreCase(cfg.printStrategy)
                ? new ExternalToolPrintStrategy(Path.of(cfg.externalToolPath), cfg.externalToolTimeoutSeconds)
                : new PdfBoxPrintStrategy();
        CapabilityCache capabilities = new CapabilityCache();
        PrintTicket tickets = new PrintTicket(cfg.work());
        PrintEngine engine = new PrintEngine(strategy, cfg.pickupCodeOnPage, cfg.coverSheetMinSheets, tickets,
                capabilities);
        log.info("Pickup code: {}", !cfg.pickupCodeOnPage ? "on a cover sheet before every order"
                : cfg.coverSheetMinSheets > 0
                        ? "on the first page; cover sheet only for orders of " + cfg.coverSheetMinSheets + "+ sheets"
                        : "on the first page (no cover sheets)");
        SpoolerMonitor spooler = new SpoolerMonitor(cfg.verifyViaSpooler, cfg.spoolerPoll());

        JobProcessor processor = new JobProcessor(backend, engine, spooler, journal, temp, health,
                cfg.maxFileSizeBytes);
        return new Parts(new Supervisor(cfg, backend, processor, health, temp, capabilities, tickets), processor);
    }
}
