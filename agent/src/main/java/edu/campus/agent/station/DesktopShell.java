package edu.campus.agent.station;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The parts of the Station that belong to Windows itself: the app window,
 * the icon next to the clock, and starting together with Windows.
 */
public final class DesktopShell {

    private static final Logger log = LoggerFactory.getLogger(DesktopShell.class);
    private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String RUN_VALUE = "CampusPrintStation";

    private static TrayIcon trayIcon;

    private DesktopShell() {
    }

    // ------------------------------------------------------------ app window

    /**
     * Opens the Station screens in their own window, without address bar or tabs
     * (Microsoft Edge "app" mode, present on every Windows 10/11 PC). Falls back
     * to the normal browser.
     */
    public static void openWindow(String url) {
        for (String edge : List.of(
                System.getenv("ProgramFiles(x86)") + "\\Microsoft\\Edge\\Application\\msedge.exe",
                System.getenv("ProgramFiles") + "\\Microsoft\\Edge\\Application\\msedge.exe")) {
            if (edge != null && Files.isRegularFile(Path.of(edge))) {
                try {
                    new ProcessBuilder(edge, "--app=" + url,
                            "--user-data-dir=" + StationConfig.dir().resolve("window"),
                            "--no-first-run", "--no-default-browser-check", "--window-size=1380,900")
                            .start();
                    return;
                } catch (Exception e) {
                    log.warn("Could not open the app window with Edge: {}", e.getMessage());
                }
            }
        }
        try {
            java.awt.Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            log.error("Could not open a window: {}", e.getMessage());
        }
    }

    public static void openFolder(Path folder) {
        try {
            Files.createDirectories(folder);
            java.awt.Desktop.getDesktop().open(folder.toFile());
        } catch (Exception e) {
            log.warn("Could not open {}: {}", folder, e.getMessage());
        }
    }

    // ------------------------------------------------------------ tray icon

    public static void installTray(Runnable open, Runnable quit, BooleanSupplier autostartOn,
                                   java.util.function.Consumer<Boolean> setAutostart) {
        if (!SystemTray.isSupported()) return;
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem openItem = new MenuItem("Open XeoGo");
            openItem.addActionListener(e -> open.run());
            menu.add(openItem);
            menu.addSeparator();
            CheckboxMenuItem auto = new CheckboxMenuItem("Start with Windows", autostartOn.getAsBoolean());
            auto.setEnabled(autostartSupported());
            auto.addItemListener(e -> setAutostart.accept(auto.getState()));
            menu.add(auto);
            menu.addSeparator();
            MenuItem quitItem = new MenuItem("Quit (stops printing)");
            quitItem.addActionListener(e -> quit.run());
            menu.add(quitItem);

            trayIcon = new TrayIcon(icon(), "XeoGo Station", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> open.run());          // double-click
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception e) {
            log.warn("No tray icon: {}", e.getMessage());
        }
    }

    public static void notify(String title, String text) {
        if (trayIcon != null) trayIcon.displayMessage(title, text, TrayIcon.MessageType.INFO);
    }

    public static void removeTray() {
        if (trayIcon != null) SystemTray.getSystemTray().remove(trayIcon);
    }

    private static Image icon() {
        try (InputStream in = DesktopShell.class.getResourceAsStream("/station-ui/icon-64.png")) {
            if (in != null) return ImageIO.read(in);
        } catch (Exception ignored) {
        }
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x14263B));
        g.fillRoundRect(0, 0, 32, 32, 8, 8);
        g.dispose();
        return img;
    }

    // ------------------------------------------------------------ start with Windows

    /** The installed program (set by the installer's launcher); null when run from a jar. */
    private static String launcher() {
        String p = System.getProperty("jpackage.app-path");
        return p == null || p.isBlank() ? null : p;
    }

    public static boolean autostartSupported() {
        return launcher() != null;
    }

    public static boolean autostartOn() {
        return run("reg", "query", RUN_KEY, "/v", RUN_VALUE) == 0;
    }

    /**
     * After an update the program can be in a new folder or have a new name
     * (before 4.3 it was "Campus Print Station"). "Start with Windows" then
     * still points at the old place, which is gone: point it at this program.
     */
    public static void refreshAutostart() {
        if (!autostartSupported()) return;
        String now = output("reg", "query", RUN_KEY, "/v", RUN_VALUE);
        if (now == null) return;                                  // switched off: leave it off
        if (!now.toLowerCase(java.util.Locale.ROOT).contains(launcher().toLowerCase(java.util.Locale.ROOT))) {
            log.info("Start with Windows pointed at another place: now this program");
            setAutostart(true);
        }
    }

    /** What a command printed, or null when it failed. */
    private static String output(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
            return p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0 ? text : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean setAutostart(boolean on) {
        if (!autostartSupported()) return false;
        // The value holds quotes ("C:\...\XeoGo Station.exe" --background), which do not survive
        // Windows command-line quoting; an encoded PowerShell command carries them safely.
        String key = "'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run'";
        String script = on
                ? "Set-ItemProperty -Path " + key + " -Name '" + RUN_VALUE + "' -Value ('\"' + '"
                  + launcher().replace("'", "''") + "' + '\" --background')"
                : "Remove-ItemProperty -Path " + key + " -Name '" + RUN_VALUE + "' -ErrorAction SilentlyContinue";
        String encoded = java.util.Base64.getEncoder()
                .encodeToString(script.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        run("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
        boolean ok = autostartOn() == on;
        log.info("Start with Windows: {}{}", on ? "on" : "off", ok ? "" : " (could not change it)");
        return ok;
    }

    private static int run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(15, TimeUnit.SECONDS) ? p.exitValue() : -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
