package edu.campus.agent.server;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Running the programs the server is made of (the database tools, Java, the
 * tunnel) the careful way: their output always goes to a file, never to a
 * pipe that could fill up or be kept open by a program they start; every run
 * has a time limit.
 */
final class Proc {

    private Proc() {
    }

    /** How a short-lived tool ended. */
    record Result(int exit, String output, boolean timedOut) {
        boolean ok() {
            return exit == 0 && !timedOut;
        }

        /** The last lines of what it printed, for a log line or an error message. */
        String tail() {
            String t = output == null ? "" : output.strip();
            return t.length() > 600 ? "…" + t.substring(t.length() - 600) : t;
        }
    }

    /**
     * Runs a tool and waits for it (at most seconds). Its output is collected
     * through a temporary file: a tool that starts a long-running program
     * (pg_ctl starts the database) would otherwise keep a pipe open for ever.
     */
    static Result run(List<String> command, Map<String, String> env, Path workDir, int seconds) {
        Path out = null;
        try {
            out = Files.createTempFile("xeogo-run", ".log");
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(out.toFile()).redirectInput(ProcessBuilder.Redirect.from(nul()));
            if (workDir != null) pb.directory(workDir.toFile());
            if (env != null) pb.environment().putAll(env);
            Process p = pb.start();
            boolean done = p.waitFor(seconds, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
            String text = read(out);
            return new Result(done ? p.exitValue() : -1, text, !done);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "interrupted", true);
        } catch (IOException e) {
            return new Result(-1, e.getMessage(), false);
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // a file left in the temp folder harms nothing
                }
            }
        }
    }

    /** Starts a program that keeps running. Everything it prints goes to logFile (started empty each time). */
    static Process start(List<String> command, Map<String, String> env, Path workDir, Path logFile) throws IOException {
        Files.createDirectories(logFile.getParent());
        Files.write(logFile, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                .redirectInput(ProcessBuilder.Redirect.from(nul()));
        if (workDir != null) pb.directory(workDir.toFile());
        if (env != null) pb.environment().putAll(env);
        return pb.start();
    }

    /** Ends a program we started: asked first, then made to (a moment later). */
    static void stop(Process p, int graceSeconds) {
        if (p == null || !p.isAlive()) return;
        p.destroy();
        try {
            if (!p.waitFor(graceSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /** The end of a log file, for an error message ("why did it not start?"). */
    static String tailOf(Path file, int chars) {
        String t = read(file).strip();
        return t.length() > chars ? "…" + t.substring(t.length() - chars) : t;
    }

    private static String read(Path file) {
        try {
            if (file == null || !Files.exists(file)) return "";
            byte[] b = Files.readAllBytes(file);
            // tools print in the console's own encoding; anything unreadable must not throw
            return new String(b, Charset.defaultCharset());
        } catch (IOException e) {
            return "";
        }
    }

    private static java.io.File nul() {
        return new java.io.File(System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null");
    }
}
