package edu.campus.print.storage;

import edu.campus.print.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The files on the server's own disk: for a server that runs on the Xerox
 * center's PC (STORAGE_MODE=local).
 *
 * Nothing changes for phones and for the Xerox PC's printing part: they still
 * get a link to send or fetch one file. The link now points at this server
 * (LocalFilesController) and carries a signed ticket instead of Supabase's:
 * which file, send or fetch, and until when. Nobody can make such a ticket
 * without the server's secret, and a ticket opens exactly one file.
 *
 * A file being sent is written next to its final place as "<name>.part" and
 * moved into place when the last piece arrived, so a half-sent file is never
 * mistaken for a whole one.
 */
@Component
@ConditionalOnProperty(name = "campus.storage.mode", havingValue = "local")
public class LocalFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);
    static final String PART = ".part";
    private static final int DEFAULT_CHUNK = 2 * 1024 * 1024;

    /** What a ticket allows. */
    public enum Use { UPLOAD, DOWNLOAD }

    /** A checked ticket: the file it is for. */
    public record Ticket(Use use, String path) {
    }

    private final Path root;
    private final byte[] key;
    private final String publicUrl;
    private final int port;
    private final int chunkBytes;
    private final int uploadTtl;
    private final int downloadTtl;
    private final long minFreeBytes;

    public LocalFileStorage(StorageProperties props, AgentProperties agent, SupabaseProperties links,
                            @Value("${server.port:8080}") int port) {
        if (props.dir() == null || props.dir().isBlank()) {
            throw new IllegalStateException("STORAGE_MODE=local needs STORAGE_DIR: the folder the files are kept in.");
        }
        this.root = Path.of(props.dir().trim()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("The files folder " + root + " cannot be made: " + e.getMessage(), e);
        }
        // A key of its own, made from the server's secret: a file ticket can never pass for a Xerox PC sign-in.
        this.key = sha256(("xeogo.files|" + agent.tokenSecret()).getBytes(StandardCharsets.UTF_8));
        this.publicUrl = props.publicUrl() == null ? "" : props.publicUrl().trim().replaceAll("/+$", "");
        this.port = port;
        this.chunkBytes = props.chunkBytesOr(DEFAULT_CHUNK);
        this.uploadTtl = props.uploadTtlSecondsOr(2 * 60 * 60);
        this.downloadTtl = links.downloadUrlTtlSeconds() > 0 ? links.downloadUrlTtlSeconds() : 300;
        this.minFreeBytes = props.minFreeMbOr(2048) * 1024L * 1024L;
        log.info("Files are kept on this computer: {} ({} GB free)", root, freeBytes() / (1024 * 1024 * 1024));
    }

    // ------------------------------------------------------------------ links

    @Override
    public SignedUpload createSignedUpload(String objectPath) {
        String token = sign(Use.UPLOAD, objectPath, Instant.now().getEpochSecond() + uploadTtl);
        return new SignedUpload(base() + "/api/v1/files/up/" + token, token, objectPath);
    }

    @Override
    public String createSignedDownload(String objectPath) {
        return base() + "/api/v1/files/dl/" + sign(Use.DOWNLOAD, objectPath, Instant.now().getEpochSecond() + downloadTtl);
    }

    @Override
    public int uploadChunkBytes() {
        return chunkBytes;
    }

    /**
     * This server's address as the caller reached it: a phone coming in through
     * the public address gets a link on that address, the Xerox PC's printing
     * part (on this same computer) gets a link on 127.0.0.1.
     */
    private String base() {
        if (!publicUrl.isEmpty() && !fromThisComputer()) return publicUrl;
        if (RequestContextHolder.getRequestAttributes() != null) {
            return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString().replaceAll("/+$", "");
        }
        return publicUrl.isEmpty() ? "http://127.0.0.1:" + port : publicUrl;
    }

    private static boolean fromThisComputer() {
        if (!(RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes a)) return false;
        String host = a.getRequest().getServerName();
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
    }

    // ------------------------------------------------------------------ tickets

    private String sign(Use use, String path, long expires) {
        String payload = (use == Use.UPLOAD ? "u" : "d") + "|" + expires + "|" + path;
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        return enc.encodeToString(body) + "." + enc.encodeToString(mac(body));
    }

    /** The file a ticket is for, if the ticket is ours, is for this use and has not run out. */
    public Optional<Ticket> check(String token, Use use) {
        try {
            int dot = token == null ? -1 : token.indexOf('.');
            if (dot < 1) return Optional.empty();
            Base64.Decoder dec = Base64.getUrlDecoder();
            byte[] body = dec.decode(token.substring(0, dot));
            byte[] given = dec.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(mac(body), given)) return Optional.empty();
            String[] parts = new String(body, StandardCharsets.UTF_8).split("\\|", 3);
            if (parts.length != 3) return Optional.empty();
            if (!(use == Use.UPLOAD ? "u" : "d").equals(parts[0])) return Optional.empty();
            if (Long.parseLong(parts[1]) < Instant.now().getEpochSecond()) return Optional.empty();
            resolve(parts[2]);                             // refuses a path that leaves the folder
            return Optional.of(new Ticket(use, parts[2]));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private byte[] mac(byte[] body) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ the files

    /** Where an object lives on the disk. Never outside the files folder. */
    public Path resolve(String objectPath) {
        if (objectPath == null || objectPath.isBlank() || objectPath.contains("\\") || objectPath.contains(":")
                || objectPath.startsWith("/")) {
            throw new IllegalArgumentException("Not a file path: " + objectPath);
        }
        Path p = root.resolve(objectPath).normalize();
        if (!p.startsWith(root) || p.equals(root)) throw new IllegalArgumentException("Not a file path: " + objectPath);
        return p;
    }

    /** The half-sent copy of an object. */
    public Path part(String objectPath) {
        Path p = resolve(objectPath);
        return p.resolveSibling(p.getFileName() + PART);
    }

    @Override
    public Optional<Probe> probe(String objectPath, int maxBytes) {
        Path p = resolve(objectPath);
        try {
            if (!Files.isRegularFile(p)) return Optional.empty();
            long size = Files.size(p);
            int want = (int) Math.min(size, Math.max(0, maxBytes));
            byte[] prefix = new byte[want];
            try (InputStream in = Files.newInputStream(p)) {
                int have = 0;
                while (have < want) {
                    int n = in.read(prefix, have, want - have);
                    if (n < 0) break;
                    have += n;
                }
                if (have < want) prefix = java.util.Arrays.copyOf(prefix, have);
            }
            return Optional.of(new Probe(size, prefix));
        } catch (IOException e) {
            log.warn("Could not read {}: {}", objectPath, e.toString());
            throw new edu.campus.print.common.ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "STORAGE_UNAVAILABLE", "Could not read the uploaded file. Try again in a moment.");
        }
    }

    @Override
    public void delete(String objectPath) {
        try {
            Path p = resolve(objectPath);
            Files.deleteIfExists(p);
            Files.deleteIfExists(part(objectPath));
            // the order's folder, once its last file is gone
            Path dir = p.getParent();
            if (dir != null && !dir.equals(root) && Files.isDirectory(dir)) {
                try (Stream<Path> left = Files.list(dir)) {
                    if (left.findAny().isEmpty()) Files.deleteIfExists(dir);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete {}: {}", objectPath, e.toString());
        }
    }

    /** Moves a completely received file into place (replacing an older upload of the same file). */
    void finish(String objectPath) throws IOException {
        Path part = part(objectPath);
        Path file = resolve(objectPath);
        try {
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public long freeBytes() {
        try {
            FileStore store = Files.getFileStore(root);
            return store.getUsableSpace();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    /** Is there room for another file? (The disk is never filled to the brim: Windows and the database need it too.) */
    public boolean hasRoom(long bytes) {
        return freeBytes() - Math.max(0, bytes) >= minFreeBytes;
    }

    /**
     * Files of orders that no longer need them (the order is gone, or all its
     * files were printed or given up) and that the usual delete missed, and
     * half-sent files nobody touched for a day.
     */
    @Override
    public int removeAbandoned(Predicate<String> stillNeeded) {
        Path orders = root.resolve("orders");
        if (!Files.isDirectory(orders)) return 0;
        int gone = 0;
        long dayAgo = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(orders)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                // An order made a moment ago may not have its first file's row yet: leave young folders alone.
                if (Files.getLastModifiedTime(dir).toMillis() > dayAgo) {
                    gone += removeOldParts(dir, dayAgo);
                    continue;
                }
                boolean needed;
                try {
                    needed = stillNeeded.test(dir.getFileName().toString());
                } catch (RuntimeException e) {
                    needed = true;                     // not sure: keep
                }
                if (needed) {
                    gone += removeOldParts(dir, dayAgo);
                    continue;
                }
                try (Stream<Path> walk = Files.walk(dir)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                        boolean file = Files.isRegularFile(p);
                        if (Files.deleteIfExists(p) && file) gone++;
                    }
                }
            }
        } catch (IOException | UncheckedIOException e) {
            log.warn("Cleaning the files folder did not finish: {}", e.toString());
        }
        return gone;
    }

    private static int removeOldParts(Path dir, long olderThan) throws IOException {
        int gone = 0;
        try (DirectoryStream<Path> parts = Files.newDirectoryStream(dir, "*" + PART)) {
            for (Path p : parts) {
                if (Files.getLastModifiedTime(p).toMillis() < olderThan && Files.deleteIfExists(p)) gone++;
            }
        }
        return gone;
    }
}
