package edu.campus.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The PC's own memory of "I already sent this order to a printer".
 *
 * Written to disk (and forced onto the disk) BEFORE anything goes to the
 * Windows spooler. If the PC loses power or internet after that point, the
 * entry is found again and the order is reported, never printed a second time.
 * Deleted only after the backend has accepted the final result.
 */
public class Journal {

    private static final Logger log = LoggerFactory.getLogger(Journal.class);

    private final Path dir;

    public Journal(Path root) throws IOException {
        this.dir = root.resolve("journal");
        Files.createDirectories(dir);
    }

    /**
     * outcomeStatus is null until the printer finished (COMPLETED / FAILED).
     */
    public record Entry(String orderId, String claimToken, String pickupCode, Instant at,
                        String outcomeStatus, String outcomeCode, String outcomeMessage) {}

    /** Call immediately before sending to the spooler. Durable when it returns. */
    public void recordSent(String orderId, String claimToken, String pickupCode) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("orderId", orderId);
        m.put("claimToken", claimToken);
        m.put("pickupCode", pickupCode);
        m.put("at", Instant.now().toString());
        write(orderId, m);
    }

    /** The printer finished but the backend could not be told yet: remember the result. */
    public void recordOutcome(Entry e, String status, String code, String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("orderId", e.orderId());
        m.put("claimToken", e.claimToken());
        m.put("pickupCode", e.pickupCode());
        m.put("at", e.at().toString());
        m.put("outcomeStatus", status);
        m.put("outcomeCode", code == null ? "" : code);
        m.put("outcomeMessage", message == null ? "" : message.replace('\n', ' '));
        try {
            write(e.orderId(), m);
        } catch (IOException io) {
            log.warn("Could not record the outcome of order {}: {}", e.pickupCode(), io.toString());
        }
    }

    public void clear(String orderId) {
        try {
            Files.deleteIfExists(dir.resolve(orderId + ".sent"));
        } catch (IOException e) {
            log.warn("Could not clear journal entry {}: {}", orderId, e.toString());
        }
    }

    public boolean has(String orderId) {
        return Files.exists(dir.resolve(orderId + ".sent"));
    }

    public Entry get(String orderId) {
        return read(dir.resolve(orderId + ".sent"));
    }

    public List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.sent")) {
            for (Path p : ds) {
                Entry e = read(p);
                if (e != null) out.add(e);
            }
        } catch (IOException e) {
            log.error("Cannot read journal folder {}", dir, e);
        }
        return out;
    }

    private Entry read(Path p) {
        try {
            Map<String, String> m = new LinkedHashMap<>();
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq > 0) m.put(line.substring(0, eq), line.substring(eq + 1));
            }
            if (m.get("orderId") == null) return null;
            String at = m.get("at");
            String outcome = m.get("outcomeStatus");
            return new Entry(m.get("orderId"), m.get("claimToken"), m.getOrDefault("pickupCode", "?"),
                    at == null ? Instant.EPOCH : Instant.parse(at),
                    outcome == null || outcome.isBlank() ? null : outcome,
                    m.getOrDefault("outcomeCode", ""), m.getOrDefault("outcomeMessage", ""));
        } catch (Exception e) {
            log.warn("Unreadable journal file {}: {}", p, e.toString());
            return null;
        }
    }

    private void write(String orderId, Map<String, String> m) throws IOException {
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        Path tmp = Files.createTempFile(dir, orderId, ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
        Files.move(tmp, dir.resolve(orderId + ".sent"),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
