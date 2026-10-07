package edu.campus.print.orders;

import edu.campus.print.common.ApiException;
import edu.campus.print.config.AgentProperties;
import edu.campus.print.config.ShopProperties;
import edu.campus.print.domain.DocumentStatus;
import edu.campus.print.domain.FileType;
import edu.campus.print.domain.OrderDocument;
import edu.campus.print.repo.AgentRepository;
import edu.campus.print.repo.OrderDocumentRepository;
import edu.campus.print.repo.OrderRepository;
import edu.campus.print.storage.DocxInspector;
import edu.campus.print.storage.FileInspector;
import edu.campus.print.storage.SupabaseStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Word files (.docx).
 *
 * A student adds a Word file like any other file. The server looks inside it
 * first (DocxInspector: nothing that runs, fetches or prompts). Then the
 * Xerox center's own computer turns it into a PDF with its Microsoft Word,
 * exactly as it would look if the student had brought the file on a pen
 * drive: the document waits in status CONVERTING, a Station takes it
 * (claim), opens it, saves the pages as PDF, sends that back (done). From
 * then on the document IS that PDF: page count, preview, every print setting,
 * the price and the printing work as for any PDF, and what the student sees
 * before paying is exactly what is printed.
 *
 * Nothing waits for ever: a file in line for more than ten minutes, a file
 * two PCs gave up on, or a file whose PC went offline is refused with the
 * advice to save it as PDF.
 */
@Service
public class WordFiles {

    private static final Logger log = LoggerFactory.getLogger(WordFiles.class);

    /** The longest a Word file waits in line for the Xerox PC. */
    static final Duration WAIT_LIMIT = Duration.ofMinutes(10);
    /** A PC that took a file and did not finish it within this time lost it. */
    static final int RETRY_SECONDS = 90;
    /** How long a PC has for one file (it is told; its own watchdog is a little shorter). */
    static final int SECONDS_ALLOWED = 75;

    public static final String NOT_AVAILABLE = "WORD_NOT_AVAILABLE";
    static final String NOT_AVAILABLE_TEXT =
            "Word files are turned into pages by the Xerox center's computer, and it is not online right now. "
                    + "Save the file as PDF and add that, or try again when the center is open.";
    private static final String TIMEOUT_TEXT =
            "The Xerox center's computer did not finish preparing this Word file. Add it again, or save it as PDF and add that.";

    /** One Word file for a Station to turn into a PDF. */
    public record Job(UUID documentId, String fileName, long fileSizeBytes, String sha256, String downloadUrl,
                      String uploadUrl, String uploadContentType, int secondsAllowed) {}

    private final OrderDocumentRepository documents;
    private final OrderRepository orders;
    private final AgentRepository agents;
    private final SupabaseStorage storage;
    private final FileInspector inspector;
    private final ShopProperties limits;
    private final AgentProperties agentProps;
    private final TransactionTemplate tx;

    public WordFiles(OrderDocumentRepository documents, OrderRepository orders, AgentRepository agents,
                     SupabaseStorage storage, FileInspector inspector, ShopProperties limits, AgentProperties agentProps,
                     PlatformTransactionManager txManager) {
        this.documents = documents;
        this.orders = orders;
        this.agents = agents;
        this.storage = storage;
        this.inspector = inspector;
        this.limits = limits;
        this.agentProps = agentProps;
        this.tx = new TransactionTemplate(txManager);
    }

    /** A Xerox PC whose Microsoft Word proved it can make PDFs is online right now. */
    public boolean available() {
        return agents.countWordReady((int) agentProps.offlineAfter().toSeconds()) > 0;
    }

    /** How many Word files are in line before this one. */
    public int ahead(OrderDocument d) {
        Instant since = d.getConvertRequestedAt();
        return since == null ? 0 : (int) documents.countConvertingBefore(since);
    }

    // ------------------------------------------------------------------ the Xerox PC's side

    /** The next Word file for this PC, if any is waiting. */
    public Optional<Job> claim(UUID agentId) {
        OrderDocument d = documents.claimNextConversion(agentId, RETRY_SECONDS).orElse(null);
        if (d == null) return Optional.empty();
        String download = storage.createSignedDownload(d.getStoragePath());
        SupabaseStorage.SignedUpload upload = storage.createSignedUpload(d.convertedPath());
        log.info("Word file \"{}\" ({}) handed to a Xerox PC", d.getFileName(), d.getId());
        return Optional.of(new Job(d.getId(), d.getFileName(), d.getFileSizeBytes() == null ? 0 : d.getFileSizeBytes(),
                d.getSha256() == null ? "" : d.getSha256(), download, upload.url(), FileType.PDF.mimeType(), SECONDS_ALLOWED));
    }

    /**
     * The PC sent the PDF. It is checked like any uploaded PDF (the PC is
     * trusted to print, not to tell the truth about a file); then the
     * document becomes that PDF.
     */
    public String done(UUID agentId, UUID documentId) {
        OrderDocument d = holder(agentId, documentId);
        String pdfPath = d.convertedPath();
        String sourcePath = d.getStoragePath();

        String problemCode = null;
        String problem = null;
        FileInspector.Result r = null;
        long size = 0;
        int readLimit = (int) Math.min(Integer.MAX_VALUE - 16L, limits.maxFileSizeBytes() + 1);
        SupabaseStorage.Probe probe = storage.probe(pdfPath, readLimit).orElse(null);
        if (probe == null || probe.totalSize() == 0) {
            problemCode = "WORD_FAILED";
            problem = TIMEOUT_TEXT;
        } else if (probe.totalSize() > limits.maxFileSizeBytes()) {
            problemCode = "FILE_TOO_LARGE";
            problem = "As pages, this Word file is larger than " + (limits.maxFileSizeBytes() / 1024 / 1024) + " MB. "
                    + "Print it in parts, or make its pictures smaller.";
        } else {
            size = probe.totalSize();
            r = inspector.inspect(probe.prefix());
            if (!r.ok() || r.type() != FileType.PDF) {
                problemCode = "WORD_FAILED";
                problem = "This Word file could not be turned into pages." + DocxInspector.SAVE_AS_PDF;
            } else if (r.pages() > limits.maxFilePages()) {
                problemCode = "TOO_MANY_PAGES";
                problem = "This file has " + r.pages() + " pages. Files can have up to " + limits.maxFilePages() + ".";
            }
        }

        final String code = problemCode;
        final String text = problem;
        final FileInspector.Result result = r;
        final long pdfSize = size;
        OrderDocument saved = tx.execute(t -> {
            orders.lockById(d.getOrderId());
            OrderDocument doc = documents.findById(documentId).orElse(null);
            if (doc == null || doc.getStatus() != DocumentStatus.CONVERTING || !agentId.equals(doc.getConvertAgentId())) {
                return null;                          // the student removed it meanwhile, or it was given up
            }
            if (code != null) {
                doc.setStatus(DocumentStatus.REJECTED);
                doc.setErrorCode(code);
                doc.setErrorMessage(text);
            } else {
                doc.setSourceType(FileType.DOCX);
                doc.setFileType(FileType.PDF);
                doc.setStoragePath(pdfPath);
                doc.setPageCount(result.pages());
                doc.setSha256(result.sha256());
                doc.setFileSizeBytes(pdfSize);
                doc.setStatus(DocumentStatus.READY);
            }
            documents.save(doc);
            orders.touch(doc.getOrderId());
            return doc;
        });

        if (saved == null) {
            storage.delete(pdfPath);
            throw ApiException.conflict("NOT_YOURS", "This Word file is no longer waiting.");
        }
        if (saved.getStatus() == DocumentStatus.READY) {
            storage.delete(sourcePath);               // the Word file itself is not needed any more
            log.info("Word file \"{}\" is ready: {} page(s)", saved.getFileName(), saved.getPageCount());
        } else {
            storage.delete(pdfPath);
            storage.delete(sourcePath);
            documents.markFileDeleted(saved.getId());
            log.info("Word file \"{}\" refused after it was turned into pages ({})", saved.getFileName(), code);
        }
        return saved.getStatus().name();
    }

    /**
     * The PC could not do it. code: PASSWORD, TOO_SLOW, TOO_LARGE, CHANGED,
     * FAILED (it is the file), or ENGINE (Word on that PC stopped working: the
     * file goes back in line once, for another PC or another try).
     */
    public String failed(UUID agentId, UUID documentId, String code, String message) {
        OrderDocument d = holder(agentId, documentId);
        String c = code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
        log.warn("A Xerox PC could not prepare Word file \"{}\" ({}): {} {}", d.getFileName(), d.getId(), c,
                message == null ? "" : message.length() > 300 ? message.substring(0, 300) : message);
        if (c.equals("ENGINE") && d.getConvertAttempts() < 2 && documents.requeueConversion(documentId, agentId) == 1) {
            return DocumentStatus.CONVERTING.name();
        }
        String text = switch (c) {
            case "PASSWORD" -> "This Word file is locked with a password. Save a copy without the password and try again.";
            case "TOO_SLOW" -> "This Word file took too long to prepare." + DocxInspector.SAVE_AS_PDF;
            case "CHANGED" -> "The file changed while it was being sent. Remove it and add it again.";
            case "TOO_LARGE" -> "As pages, this Word file is larger than " + (limits.maxFileSizeBytes() / 1024 / 1024)
                    + " MB. Print it in parts, or make its pictures smaller.";
            case "ENGINE" -> "The Xerox center's computer could not prepare Word files just now. Try again in a few "
                    + "minutes, or save the file as PDF and add that.";
            default -> "Microsoft Word at the Xerox center could not open this file." + DocxInspector.SAVE_AS_PDF;
        };
        reject(d, "WORD_" + (c.isEmpty() ? "FAILED" : c), text);
        return DocumentStatus.REJECTED.name();
    }

    // ------------------------------------------------------------------ nothing waits for ever

    /** Called every few seconds. Returns how many waiting Word files were given up. */
    public int expire() {
        var waiting = documents.findConverting();
        if (waiting.isEmpty()) return 0;
        boolean online = available();
        Instant now = Instant.now();
        int n = 0;
        for (OrderDocument d : waiting) {
            Instant asked = d.getConvertRequestedAt() == null ? now : d.getConvertRequestedAt();
            Instant claimed = d.getConvertClaimedAt();
            boolean nobodyHasIt = claimed == null || claimed.isBefore(now.minusSeconds(RETRY_SECONDS));
            if (asked.isBefore(now.minus(WAIT_LIMIT))
                    || (d.getConvertAttempts() >= 2 && nobodyHasIt)) {
                n += reject(d, "WORD_TIMEOUT", TIMEOUT_TEXT);
            } else if (!online && nobodyHasIt && asked.isBefore(now.minusSeconds(45))) {
                n += reject(d, NOT_AVAILABLE, NOT_AVAILABLE_TEXT);
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ helpers

    private OrderDocument holder(UUID agentId, UUID documentId) {
        OrderDocument d = documents.findById(documentId).orElse(null);
        if (d == null || d.getStatus() != DocumentStatus.CONVERTING || !agentId.equals(d.getConvertAgentId())) {
            throw ApiException.conflict("NOT_YOURS", "This Word file is not waiting for this PC.");
        }
        return d;
    }

    /** CONVERTING -> REJECTED with the reason, and both files gone. 1 if it was still waiting. */
    private int reject(OrderDocument d, String code, String text) {
        Integer changed = tx.execute(t -> {
            orders.lockById(d.getOrderId());          // the order first, then its document: the same order as everywhere
            return documents.rejectConversion(d.getId(), code, text);
        });
        if (changed == null || changed != 1) return 0;
        storage.delete(d.getStoragePath());
        storage.delete(d.convertedPath());
        documents.markFileDeleted(d.getId());
        orders.touch(d.getOrderId());
        return 1;
    }
}
