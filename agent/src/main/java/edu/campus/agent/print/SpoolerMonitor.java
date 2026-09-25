package edu.campus.agent.print;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Watches the Windows print queue so staff and students see the real result.
 *
 * Important: a printer that is out of paper or jammed is NOT a failed order.
 * Windows keeps the document and prints it as soon as staff fix the printer.
 * So while the document is still in the queue we keep waiting (and tell the
 * counter screen the printer needs attention). Marking it failed would invite
 * a second print, and then the student gets two copies.
 *
 * The order is FAILED only if the document leaves the queue after an error
 * or while being deleted (staff removed it).
 */
public class SpoolerMonitor {

    private static final Logger log = LoggerFactory.getLogger(SpoolerMonitor.class);

    /** How long the queue may stay unreadable before we stop watching. */
    private static final Duration QUEUE_UNREADABLE_LIMIT = Duration.ofSeconds(60);

    public enum Outcome { COMPLETED, REMOVED, UNKNOWN }

    public record Result(Outcome outcome, String detail) {}

    private final boolean enabled;
    private final Duration pollInterval;

    public SpoolerMonitor(boolean enabled, Duration pollInterval) {
        this.enabled = enabled;
        this.pollInterval = pollInterval;
    }

    /**
     * Blocks until the document leaves the Windows queue.
     *
     * @param attention called with a message while the printer needs a person
     *                  (paper out, jam, offline), and with null once it is fine again
     */
    public Result awaitCompletion(String printer, String jobName, Consumer<String> attention) {
        if (!enabled || !isWindows()) {
            return new Result(Outcome.UNKNOWN, "queue watching is switched off");
        }
        String last = null;
        boolean sawTrouble = false;
        boolean sawDeleting = false;
        long failingSince = 0;
        while (true) {
            String status;
            try {
                status = queryStatus(printer, jobName);
                failingSince = 0;
            } catch (Exception e) {
                // Get-PrintJob fails now and then, typically while the printer
                // reports a problem (paper out) or a job is leaving the queue.
                // Giving up at once would report "printed" for a document that
                // is still waiting, so keep watching unless it stays unreadable.
                long now = System.currentTimeMillis();
                if (failingSince == 0) {
                    failingSince = now;
                    log.warn("Cannot read the Windows queue for \"{}\", trying again: {}", printer, e.getMessage());
                } else if (now - failingSince >= QUEUE_UNREADABLE_LIMIT.toMillis()) {
                    log.warn("Windows queue for \"{}\" unreadable for {}s: {}", printer,
                            QUEUE_UNREADABLE_LIMIT.toSeconds(), e.getMessage());
                    return new Result(Outcome.UNKNOWN, "Windows queue could not be read");
                }
                if (!pause()) return new Result(Outcome.UNKNOWN, "stopped while waiting");
                continue;
            }
            if (status == null) {
                attention.accept(null);
                if (sawDeleting || (sawTrouble && last != null && last.toLowerCase().contains("error"))) {
                    return new Result(Outcome.REMOVED, "removed from the printer queue after: " + last);
                }
                return new Result(Outcome.COMPLETED, last == null ? "printed" : "printed (last seen: " + last + ")");
            }
            last = status;
            String s = status.toLowerCase();
            if (s.contains("printed") || s.contains("complete")) {
                // Some PCs keep finished documents in the queue ("Keep printed documents").
                attention.accept(null);
                return new Result(Outcome.COMPLETED, "printed");
            }
            if (s.contains("delet")) {
                sawDeleting = true;
            }
            boolean trouble = s.contains("error") || s.contains("paperout") || s.contains("offline")
                    || s.contains("userintervention") || s.contains("blocked") || s.contains("paused");
            if (trouble) {
                if (!sawTrouble) log.warn("Printer \"{}\" needs attention: {}", printer, status);
                sawTrouble = true;
                attention.accept(status);
            } else if (sawTrouble) {
                attention.accept(null);
            }
            if (!pause()) return new Result(Outcome.UNKNOWN, "stopped while waiting");
        }
    }

    /** Waits one poll interval. False if the thread was interrupted (agent stopping). */
    private boolean pause() {
        try {
            TimeUnit.MILLISECONDS.sleep(pollInterval.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The document's status text in the Windows queue, or null if it is no longer there. */
    private static String queryStatus(String printer, String jobName) throws Exception {
        String ps = "$ErrorActionPreference='Stop'; "
                + "$j = Get-PrintJob -PrinterName '" + esc(printer) + "' | "
                + "Where-Object { $_.DocumentName -like '*" + esc(jobName) + "*' -and $_.DocumentName -notlike '*-cover*' } | "
                + "Select-Object -First 1; "
                + "if ($j) { 'JOB:' + [string]$j.JobStatus } else { 'NONE' }";
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps)
                .redirectErrorStream(true)
                .start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("Get-PrintJob timed out");
        }
        if (p.exitValue() != 0) {
            String why = new String(out, StandardCharsets.UTF_8).trim().replaceAll("\\s+", " ");
            throw new IllegalStateException("Get-PrintJob failed: "
                    + (why.length() > 300 ? why.substring(0, 300) : why));
        }
        String all = new String(out, StandardCharsets.UTF_8).trim();
        String text = all.lines().map(String::trim)
                .filter(l -> l.startsWith("JOB:") || l.startsWith("NONE"))
                .reduce((first, second) -> second).orElse(all);
        if (text.startsWith("NONE")) return null;
        if (!text.startsWith("JOB:")) throw new IllegalStateException("Unexpected answer: " + text);
        String status = text.substring(4).trim();
        return status.isEmpty() ? "Normal" : status;
    }

    private static String esc(String s) {
        return s.replace("'", "''");
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
