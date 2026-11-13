package edu.campus.print.storage;

import edu.campus.print.common.ApiException;
import edu.campus.print.config.ShopProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Receiving and handing out files when they are kept on this computer
 * (STORAGE_MODE=local). The links come from LocalFileStorage; the ticket in
 * the link says which one file it is for.
 *
 *   PUT /api/v1/files/up/{ticket}    send the file: all at once, or in pieces
 *                                    (Content-Range: bytes 0-2097151/7340032)
 *   GET /api/v1/files/up/{ticket}    how much of it has arrived
 *   GET /api/v1/files/dl/{ticket}    fetch the file
 *
 * In pieces is what the website and the apps do: on a phone connection that
 * drops in the middle of a 50 MB file, the pieces that arrived stay, and the
 * upload carries on with the next one instead of starting again.
 *
 *   - a piece must start where the file so far ends (or earlier: sending a
 *     piece again is harmless). A gap is refused with 409 and "received", so
 *     the device knows where to carry on.
 *   - nothing larger than the file limit is ever written, whatever the
 *     device claims.
 *   - the file counts as uploaded only when its last byte arrived: until then
 *     it is "<name>.part", which nothing else reads.
 */
@RestController
@RequestMapping("/api/v1/files")
@ConditionalOnProperty(name = "campus.storage.mode", havingValue = "local")
public class LocalFilesController {

    private static final Logger log = LoggerFactory.getLogger(LocalFilesController.class);
    private static final Pattern RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+)");

    private final LocalFileStorage storage;
    private final long maxFileBytes;
    private final int maxPieceBytes;
    private final Semaphore receiving;
    /**
     * Two requests for the same file take turns: a device that gave up on a
     * piece and sends it again while the first try is still being read. One
     * gate per file being received, gone when nobody is at it.
     */
    private final Map<String, Gate> gates = new HashMap<>();

    private static final class Gate {
        final ReentrantLock lock = new ReentrantLock();
        int users;
    }

    public LocalFilesController(LocalFileStorage storage, ShopProperties limits, StorageProperties props) {
        this.storage = storage;
        this.maxFileBytes = limits.maxFileSizeBytes();
        // a device may send bigger pieces than we suggest, within reason
        this.maxPieceBytes = Math.max(storage.uploadChunkBytes() * 4, 8 * 1024 * 1024);
        this.receiving = new Semaphore(props.maxUploadsOr(200), true);
    }

    // ------------------------------------------------------------------ sending a file

    @PutMapping("/up/{ticket}")
    public ResponseEntity<Map<String, Object>> upload(@PathVariable String ticket, HttpServletRequest req) throws IOException {
        String path = fileOf(ticket, LocalFileStorage.Use.UPLOAD);

        long declared = req.getContentLengthLong();                 // -1: not said
        String rangeHeader = req.getHeader("Content-Range");
        long start, end, total;
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            Matcher m = RANGE.matcher(rangeHeader.trim());
            if (!m.matches()) throw notUnderstood();
            start = Long.parseLong(m.group(1));
            end = Long.parseLong(m.group(2));
            total = Long.parseLong(m.group(3));
            long length = end - start + 1;
            if (end < start || end >= total || (declared >= 0 && declared != length)) throw notUnderstood();
            if (length > maxPieceBytes) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PIECE_TOO_LARGE",
                        "The upload could not be understood. Try again.");
            }
        } else {
            start = 0;
            total = declared;                                       // may be -1: counted while reading
            end = declared < 0 ? -1 : declared - 1;
        }
        if (total > maxFileBytes) throw tooLarge();
        if (total == 0) throw ApiException.badRequest("EMPTY_FILE", "The file is empty.");
        if (!storage.hasRoom(total < 0 ? maxFileBytes : total - start)) {
            log.error("The disk is nearly full ({} MB free): uploads are refused until there is room",
                    storage.freeBytes() / (1024 * 1024));
            throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE, "DISK_FULL",
                    "The Xerox center's computer is out of space right now. Please tell the counter.");
        }

        boolean slot;
        try {
            slot = receiving.tryAcquire(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            slot = false;
        }
        if (!slot) throw busy();
        Gate gate;
        synchronized (gates) {
            gate = gates.computeIfAbsent(path, k -> new Gate());
            gate.users++;
        }
        try {
            boolean mine;
            try {
                // An earlier try for this file may still be waiting for bytes that will never come: it gives up
                // after the server's read time limit (20 s), then this one carries on.
                mine = gate.lock.tryLock(25, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mine = false;
            }
            if (!mine) throw busy();
            try {
                return receive(path, start, end, total, req.getInputStream());
            } finally {
                gate.lock.unlock();
            }
        } finally {
            synchronized (gates) {
                if (--gate.users == 0) gates.remove(path);
            }
            receiving.release();
        }
    }

    private ResponseEntity<Map<String, Object>> receive(String path, long start, long end, long total, InputStream in)
            throws IOException {
        Path file = storage.resolve(path);
        Path part = storage.part(path);
        long have = Files.isRegularFile(part) ? Files.size(part) : 0;

        // The last piece again, after the answer to it was lost: the file is already whole.
        if (!Files.exists(part) && Files.isRegularFile(file) && total > 0 && Files.size(file) == total && start > 0) {
            return answer(total, true);
        }
        if (start > have) {
            // a piece is missing in between: tell the device where to carry on
            return ResponseEntity.status(HttpStatus.CONFLICT).body(state("GAP", have, false));
        }

        Files.createDirectories(part.getParent());
        long written = 0;
        boolean dropped = false;                                        // the connection went away while we read
        long room = total < 0 ? maxFileBytes + 1 : end - start + 1;      // never more than this piece may hold
        try (FileChannel out = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            out.truncate(start);                                        // a piece sent again replaces what was there
            out.position(start);
            byte[] buffer = new byte[64 * 1024];
            while (written < room) {
                int n;
                try {
                    n = in.read(buffer, 0, (int) Math.min(buffer.length, room - written));
                } catch (IOException e) {
                    dropped = true;                                     // a phone losing its signal: nothing unusual
                    break;
                }
                if (n < 0) break;
                ByteBuffer b = ByteBuffer.wrap(buffer, 0, n);
                while (b.hasRemaining()) out.write(b);
                written += n;
            }
            if (total < 0 && written > maxFileBytes) {
                out.truncate(0);
                throw tooLarge();
            }
            if (!dropped && total >= 0 && written == room) {
                int extra;
                try {
                    extra = in.read();
                } catch (IOException e) {
                    extra = -1;
                }
                if (extra >= 0) throw notUnderstood();                  // the device sent more than it announced
            }
            boolean whole = !dropped && (total < 0 ? written > 0 : start + written == total);
            if (whole) out.force(false);                                // on the disk before it counts as uploaded
        }

        long now = start + written;
        if (dropped || (total >= 0 && written < end - start + 1)) {
            // the connection dropped inside this piece: what arrived stays, the device carries on from there
            log.debug("Upload of {} stopped at {} of {}", path, now, total);
            throw ApiException.badRequest("INCOMPLETE", "The upload stopped. Check your internet and try again.");
        }
        boolean complete = total < 0 ? written > 0 : now == total;
        if (complete) storage.finish(path);
        return answer(now, complete);
    }

    /** How much of the file has arrived, so an upload that stopped carries on instead of starting again. */
    @GetMapping("/up/{ticket}")
    public ResponseEntity<Map<String, Object>> progress(@PathVariable String ticket) throws IOException {
        String path = fileOf(ticket, LocalFileStorage.Use.UPLOAD);
        Path part = storage.part(path);
        if (Files.isRegularFile(part)) return answer(Files.size(part), false);
        Path file = storage.resolve(path);
        if (Files.isRegularFile(file)) return answer(Files.size(file), true);
        return answer(0, false);
    }

    // ------------------------------------------------------------------ fetching a file

    @GetMapping("/dl/{ticket}")
    public ResponseEntity<Resource> download(@PathVariable String ticket) {
        String path = fileOf(ticket, LocalFileStorage.Use.DOWNLOAD);
        Path file = storage.resolve(path);
        if (!Files.isRegularFile(file)) throw ApiException.notFound("That file");
        return ResponseEntity.ok()
                .contentType(typeOf(path))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "attachment")       // never shown as a page of this server
                .body(new FileSystemResource(file));
    }

    // ------------------------------------------------------------------ helpers

    private String fileOf(String ticket, LocalFileStorage.Use use) {
        return storage.check(ticket, use).map(LocalFileStorage.Ticket::path)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "LINK_EXPIRED",
                        "This link is no longer valid. Try again."));
    }

    private static ResponseEntity<Map<String, Object>> answer(long received, boolean complete) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(state(null, received, complete));
    }

    private static Map<String, Object> state(String error, long received, boolean complete) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (error != null) {
            m.put("error", error);
            m.put("message", "The upload carries on from where it stopped.");
        }
        m.put("received", received);
        m.put("complete", complete);
        return m;
    }

    private static ApiException notUnderstood() {
        return ApiException.badRequest("BAD_RANGE", "The upload could not be understood. Try again.");
    }

    private ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                "Files must be smaller than " + (maxFileBytes / 1024 / 1024) + " MB.");
    }

    private static ApiException busy() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BUSY",
                "Many files are arriving right now. Try again in a moment.");
    }

    private static MediaType typeOf(String path) {
        String p = path.toLowerCase(java.util.Locale.ROOT);
        if (p.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        if (p.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
