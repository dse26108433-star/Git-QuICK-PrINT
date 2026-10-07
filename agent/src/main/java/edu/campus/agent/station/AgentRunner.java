package edu.campus.agent.station;

import edu.campus.agent.AgentMain;
import edu.campus.agent.config.AgentConfig;
import edu.campus.agent.core.JobProcessor;
import edu.campus.agent.core.Supervisor;
import edu.campus.agent.net.Messages.PrinterConfig;
import edu.campus.agent.print.PrinterDiscovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the printing part of the Station (the same code as the old background
 * service) inside the app, and lets the app start, stop and watch it.
 */
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    private Supervisor supervisor;
    private JobProcessor processor;
    private Thread thread;
    private volatile String startProblem;

    public synchronized void start(StationConfig c) {
        stop();
        startProblem = null;
        try {
            AgentConfig cfg = new AgentConfig();
            cfg.backendUrl = c.backendUrl;
            cfg.agentId = c.agentId;
            cfg.agentSecret = c.agentSecret;
            cfg.workDir = StationConfig.dir().resolve("work").toString();
            cfg.check();
            AgentMain.Parts parts = AgentMain.build(cfg);
            supervisor = parts.supervisor();
            processor = parts.processor();
            thread = new Thread(supervisor::run, "printing");
            thread.setDaemon(true);
            thread.start();
            log.info("Printing started for PC \"{}\" (server {})", c.pcName, c.backendUrl);
        } catch (Exception e) {
            startProblem = e.getMessage();
            log.error("Printing could not start: {}", e.getMessage());
            supervisor = null;
            processor = null;
            thread = null;
        }
    }

    /** Stops asking for new work and lets an order that is printing finish (up to 30 s). */
    public synchronized void stop() {
        if (supervisor == null) return;
        supervisor.stop();
        long until = System.currentTimeMillis() + 30_000;
        while (processor.isBusy() && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        thread.interrupt();
        supervisor = null;
        processor = null;
        thread = null;
        log.info("Printing stopped");
    }

    /** Printers were changed on the server: pick them up now. */
    public synchronized void refreshNow() {
        if (supervisor != null) supervisor.wakeUp();
    }

    /** Staff pressed "Scan again": read the printers' features from Windows and tell the server. */
    public synchronized void rescanNow() {
        if (supervisor != null) supervisor.lookAgainAtAllPrinters();
    }

    public synchronized boolean isRunning() {
        return supervisor != null;
    }

    /** For the app's screens: is this PC printing, connected, and which printers does it run? */
    public synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", supervisor != null);
        m.put("busy", processor != null && processor.isBusy());
        Instant last = supervisor == null ? null : supervisor.lastContact();
        m.put("lastContact", last == null ? null : last.toString());
        m.put("connected", last != null && last.isAfter(Instant.now().minusSeconds(90)));
        m.put("problem", supervisor == null ? startProblem : supervisor.problem());
        List<Map<String, Object>> list = new ArrayList<>();
        if (supervisor != null) {
            for (PrinterConfig p : supervisor.printers()) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("id", p.id());
                pm.put("name", p.name());
                pm.put("windowsPrinterName", p.windowsPrinterName());
                pm.put("found", PrinterDiscovery.find(p.windowsPrinterName()).isPresent());
                supervisor.capabilities().get(p.windowsPrinterName())
                        .ifPresent(d -> pm.put("capabilities", d.capabilities()));
                list.add(pm);
            }
        }
        m.put("printers", list);
        // Word files: can this PC turn them into PDFs (its Microsoft Word passed the test), or why not
        edu.campus.agent.core.WordFiles w = supervisor == null ? null : supervisor.wordFiles();
        if (w != null) {
            Map<String, Object> word = new LinkedHashMap<>();
            word.put("ready", w.state().ready());
            word.put("note", w.state().note());
            m.put("word", word);
        }
        return m;
    }

    /** Staff pressed "Check again" for Word files (Word was installed or activated meanwhile). */
    public synchronized void checkWordNow() {
        if (supervisor != null && supervisor.wordFiles() != null) supervisor.wordFiles().checkAgain();
    }
}
