package edu.campus.agent.print;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Is a printer ready to take a document right now? Windows' own view:
 * the printer's status flags (offline, paper out, jam...) and the
 * "Use printer offline" switch.
 *
 * A printer that is not ready takes no new documents: a paid document then
 * waits safely on the server, where another printer that can do it may take
 * it, instead of sitting in a switched-off printer's Windows queue.
 */
public final class PrinterStatus {

    private static final Logger log = LoggerFactory.getLogger(PrinterStatus.class);

    /** Windows status flags that mean "nothing will come out now", in words for the counter. */
    private static final Map<String, String> BLOCKING = new LinkedHashMap<>();

    static {
        BLOCKING.put("offline", "Printer is offline");
        BLOCKING.put("paperout", "Out of paper");
        BLOCKING.put("paperjam", "Paper jam");
        BLOCKING.put("paperproblem", "Paper problem");
        BLOCKING.put("dooropen", "A door is open");
        BLOCKING.put("notoner", "Out of toner");
        BLOCKING.put("paused", "Paused in Windows");
        BLOCKING.put("userintervention", "Needs attention");
        BLOCKING.put("notavailable", "Not available");
        BLOCKING.put("outputbinfull", "Output tray full");
        BLOCKING.put("outofmemory", "Printer out of memory");
        BLOCKING.put("error", "Printer error");
    }

    private PrinterStatus() {
    }

    /**
     * Why the printer cannot print now, or null when it can.
     *
     * @param flags       Windows' PrinterStatus as text, e.g. "Normal", "Offline", "PaperOut, Error"
     * @param workOffline Windows' "Use printer offline" switch
     */
    public static String problem(String flags, boolean workOffline) {
        if (workOffline) return "Set to \"Use printer offline\" in Windows";
        if (flags == null || flags.isBlank()) return null;
        List<String> found = List.of(flags.toLowerCase(Locale.ROOT).replace(" ", "").split("[,|]"));
        for (Map.Entry<String, String> e : BLOCKING.entrySet()) {
            if (found.contains(e.getKey())) return e.getValue();
        }
        return null;             // Normal, Printing, Busy, Processing, WarmingUp, TonerLow, PowerSave...
    }

    /**
     * Reads every given printer at once. Name -> problem (null = ready).
     * Printers Windows does not list are left out.
     *
     * @throws Exception when Windows could not be asked (the caller keeps its last answer)
     */
    public static Map<String, String> read(Collection<String> windowsNames) throws Exception {
        Map<String, String> out = new HashMap<>();
        if (windowsNames.isEmpty() || !SpoolerMonitor.isWindows()) return out;
        String ps = "$ErrorActionPreference='Stop'; "
                + "$o = @{}; Get-CimInstance Win32_Printer | ForEach-Object { $o[$_.Name] = [bool]$_.WorkOffline }; "
                + "Get-Printer | ForEach-Object { 'P' + [char]9 + $_.Name + [char]9 + [string]$_.PrinterStatus + [char]9 + [string]$o[$_.Name] }";
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps)
                .redirectErrorStream(true).start();
        byte[] bytes = p.getInputStream().readAllBytes();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("Get-Printer timed out");
        }
        if (p.exitValue() != 0) throw new IllegalStateException("Get-Printer failed");
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            String[] f = line.split("\t", -1);
            if (f.length < 4 || !"P".equals(f[0])) continue;
            if (!windowsNames.contains(f[1])) continue;
            out.put(f[1], problem(f[2], "true".equalsIgnoreCase(f[3].trim())));
        }
        return out;
    }

    /** The same, never failing: an empty answer when Windows could not be asked. */
    public static Map<String, String> readQuietly(Collection<String> windowsNames) {
        try {
            return read(windowsNames);
        } catch (Exception e) {
            log.debug("Could not read printer status: {}", e.toString());
            return Map.of();
        }
    }
}
