package edu.campus.agent.station;

import com.sun.net.httpserver.HttpServer;
import edu.campus.agent.server.LocalStack;
import edu.campus.agent.server.ServerSettings;
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

        // "This PC is the XeoGo server": the database, the print service and the tunnel to the internet start
        // here, by themselves, before anything else. (Not set up that way: nothing happens.)
        ServerSettings serverCfg = ServerSettings.load();
        LocalStack stack = LocalStack.available() ? new LocalStack(serverCfg) : null;
        if (stack != null && serverCfg.enabled) {
            stack.start();
        } else if (serverCfg.enabled) {
            log.error("This PC is set up as the XeoGo server, but the server programs are missing from {}. "
                    + "Install XeoGo Station again.", LocalStack.home());
        }

        AgentRunner runner = new AgentRunner();
        if (cfg.isConfigured()) {
            runner.start(cfg);          // it keeps trying until the server (here or in the cloud) answers
        }

        LocalServer[] server = new LocalServer[1];
        Runnable open = () -> DesktopShell.openWindow(server[0].url());
        server[0] = new LocalServer(port, cfg, runner, open, stack, serverCfg);
        server[0].start();

        Runnable quit = () -> {
            log.info("Quitting XeoGo Station");
            runner.stop();
            if (stack != null) stack.stop();
            server[0].stop();
            DesktopShell.removeTray();
            System.exit(0);
        };
        DesktopShell.installTray(open, quit, DesktopShell::autostartOn, DesktopShell::setAutostart);
        new Thread(DesktopShell::refreshAutostart, "start-with-windows").start();
        Runtime.getRuntime().addShutdownHook(new Thread(runner::stop, "stop-printing"));
        // Windows is shutting down (or the Station was closed): the database gets its few seconds to close its
        // files properly. If Windows does not wait, nothing is lost: it tidies itself at the next start.
        if (stack != null) Runtime.getRuntime().addShutdownHook(new Thread(stack::stop, "stop-server"));

        log.info("XeoGo Station started{}", background ? " with Windows" : "");
        if (background) {
            DesktopShell.notify("XeoGo is running", !cfg.isConfigured() ? "Open XeoGo to finish the setup."
                    : serverCfg.enabled ? "The XeoGo server on this PC is starting. Paid orders print automatically."
                    : "Paid orders print automatically. Click here to open the counter.");
        } else {
            open.run();
        }
    }
}
