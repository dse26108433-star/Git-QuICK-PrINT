package edu.campus.agent.station;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import java.net.BindException;
import java.util.List;

/**
 * XeoGo Station: the one program installed on the Xerox center PC.
 *
 *   - first start: a setup wizard connects the PC to the XeoGo server
 *     and scans the printers;
 *   - then: the counter screen in its own window, and orders print by
 *     themselves in the background (it keeps printing when the window is
 *     closed; the icon next to the clock opens it again);
 *   - starts with Windows (--background: no window, just printing).
 */
public final class StationMain {

    private StationMain() {
    }

    public static void main(String[] args) {
        boolean background = List.of(args).contains("--background");
        // Logs go next to the settings, before anything writes a log line.
        System.setProperty("LOG_DIR", StationConfig.dir().resolve("logs").toString());

        HttpServer port;
        try {
            port = LocalServer.bind();
        } catch (BindException e) {
            // Already running: bring its window forward instead of starting twice.
            if (!background && !LocalServer.askRunningAppToShow()) {
                JOptionPane.showMessageDialog(null, "XeoGo cannot start: port " + LocalServer.PORT
                        + " is used by another program.", "XeoGo", JOptionPane.ERROR_MESSAGE);
            }
            return;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "XeoGo cannot start: " + e.getMessage(),
                    "XeoGo", JOptionPane.ERROR_MESSAGE);
            return;
        }

        Logger log = LoggerFactory.getLogger(StationMain.class);
        StationConfig cfg = StationConfig.load();
        AgentRunner runner = new AgentRunner();
        if (cfg.isConfigured()) {
            runner.start(cfg);
        }

        LocalServer[] server = new LocalServer[1];
        Runnable open = () -> DesktopShell.openWindow(server[0].url());
        server[0] = new LocalServer(port, cfg, runner, open);
        server[0].start();

        Runnable quit = () -> {
            log.info("Quitting XeoGo Station");
            runner.stop();
            server[0].stop();
            DesktopShell.removeTray();
            System.exit(0);
        };
        DesktopShell.installTray(open, quit, DesktopShell::autostartOn, DesktopShell::setAutostart);
        new Thread(DesktopShell::refreshAutostart, "start-with-windows").start();
        Runtime.getRuntime().addShutdownHook(new Thread(runner::stop, "stop-printing"));

        log.info("XeoGo Station started{}", background ? " with Windows" : "");
        if (background) {
            DesktopShell.notify("XeoGo is running", cfg.isConfigured()
                    ? "Paid orders print automatically. Click here to open the counter."
                    : "Open XeoGo to finish the setup.");
        } else {
            open.run();
        }
    }
}
