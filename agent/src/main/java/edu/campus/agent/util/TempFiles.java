package edu.campus.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

/**
 * Students' files live on this PC only while printing, in a folder only this
 * program (and administrators) can read, and are overwritten before deletion.
 */
public class TempFiles {

    private static final Logger log = LoggerFactory.getLogger(TempFiles.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path dir;
    private final Duration retention;

    public TempFiles(Path root, Duration retention) throws IOException {
        this.dir = root.resolve("spool");
        this.retention = retention;
        Files.createDirectories(dir);
        harden(dir);
    }

    public Path create(String orderId, String extension) throws IOException {
        Path f = dir.resolve(orderId + extension);
        Files.deleteIfExists(f);
        return Files.createFile(f);
    }

    /** Overwrite, then delete. Never lets clean-up break a print. */
    public void shred(Path file) {
        if (file == null) return;
        try {
            if (Files.exists(file)) {
                long size = Files.size(file);
                if (size > 0 && size < 64L * 1024 * 1024) {
                    byte[] noise = new byte[8192];
                    try (var out = Files.newOutputStream(file, StandardOpenOption.WRITE)) {
                        for (long written = 0; written < size; ) {
                            RANDOM.nextBytes(noise);
                            int n = (int) Math.min(noise.length, size - written);
                            out.write(noise, 0, n);
                            written += n;
                        }
                    }
                }
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            log.warn("Could not remove temp file {}: {}", file, e.toString());
        }
    }

    /** Removes anything a crash left behind. Runs at start and every hour. */
    public void sweepStale() {
        int removed = 0;
        Instant cutoff = Instant.now().minus(retention);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                try {
                    FileTime t = Files.getLastModifiedTime(p);
                    if (t.toInstant().isBefore(cutoff)) {
                        shred(p);
                        removed++;
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            log.warn("Temp clean-up failed: {}", e.toString());
        }
        if (removed > 0) log.info("Cleaned {} old temp file(s)", removed);
    }

    /**
     * Only SYSTEM, Administrators and the account this program runs as may
     * open the folder. (The account must be included, or the program would
     * lock itself out.)
     */
    private static void harden(Path path) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            try {
                Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            } catch (Exception ignored) {
            }
            return;
        }
        try {
            String me = System.getProperty("user.name", "");
            var cmd = new java.util.ArrayList<>(java.util.List.of("icacls", path.toString(), "/inheritance:r",
                    "/grant:r", "*S-1-5-18:(OI)(CI)F",           // SYSTEM
                    "/grant:r", "*S-1-5-32-544:(OI)(CI)F"));     // Administrators
            if (!me.isBlank() && !me.endsWith("$")) {             // "PC$" = SYSTEM itself
                cmd.add("/grant:r");
                cmd.add(me + ":(OI)(CI)F");
            }
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() != 0) {
                log.warn("Could not tighten permissions on {} (not fatal)", path);
            }
        } catch (Exception e) {
            log.debug("Could not tighten permissions on {}: {}", path, e.toString());
        }
    }
}
