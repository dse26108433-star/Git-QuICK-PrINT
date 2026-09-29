package edu.campus.print.orders;

import com.fasterxml.jackson.databind.JsonNode;
import edu.campus.print.domain.FileType;
import edu.campus.print.printing.ImageInfo;
import edu.campus.print.printing.PrintSettings;
import edu.campus.print.printing.PricingRules;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the student's phone or browser sends and receives. */
public final class OrderDtos {

    private OrderDtos() {
    }

    /**
     * Start an order.
     *
     * Website (several documents): send {} and add documents one by one with
     * AddDocumentRequest.
     *
     * One-file apps (the Android app, older websites): send the file and the
     * choices here, as before; the answer carries the upload link.
     * pages: which pages of a PDF, e.g. "102" or "333-390" or "1-5, 8"; empty = all.
     */
    public record CreateOrderRequest(
            @Size(max = 200) String fileName,
            FileType fileType,
            Long fileSizeBytes,
            Boolean color,
            Integer copies,
            @Size(max = PageRanges.MAX_LENGTH) String pages
    ) {
        public boolean singleFile() {
            return fileName != null && !fileName.isBlank();
        }
    }

    /**
     * accessKey is shown only here. The device keeps it and sends it back in
     * the X-Order-Key header to see or pay for this order. There is no login.
     * uploadUrl / documentId: only for a one-file order.
     */
    public record CreateOrderResponse(
            UUID orderId,
            String accessKey,
            String pickupCode,
            String uploadUrl,
            String uploadContentType,
            UUID documentId
    ) {}

    /** Add one file to a draft order. */
    public record AddDocumentRequest(
            @Size(max = 200) String fileName,
            FileType fileType,
            long fileSizeBytes
    ) {}

    /** Where to send the file (PUT, with this Content-Type), and the document so far. */
    public record UploadTicket(DocumentView document, String uploadUrl, String uploadContentType) {}

    /**
     * "Check and price my order." One entry per document, in the order they
     * should print. settings: the student's choices (see PrintSettings); the
     * answer shows them as the server will print them.
     */
    public record ReviewRequest(List<DocumentChoice> documents) {}

    public record DocumentChoice(UUID id, JsonNode settings) {}

    public record ConfirmPaymentRequest(String paymentId, String signature) {}

    public record FileUrl(String url) {}

    /** One document of an order. */
    public record DocumentView(
            UUID id,
            int position,
            String fileName,
            String fileType,
            Long sizeBytes,
            String status,
            String stage,            // words for the screen: "Printing on Printer 2"
            String problem,          // why it cannot be printed, or null
            Integer pageCount,       // pages in the file
            ImageInfo image,         // pictures only
            PrintSettings settings,  // as the server will print it (after pricing)
            String pagesText,        // "3, 7, 10–12" or "All 24 pages"
            Integer printPages,      // pages chosen, per copy
            Integer sides,           // printed sides, per copy
            Integer sheets,          // sheets of paper, per copy
            Integer amountPaise,
            String printerName
    ) {}

    public record OrderView(
            UUID orderId,
            String pickupCode,
            String status,
            String stage,          // short words for the screen: "Printing 1 of 3 documents"
            String message,        // longer help, or null
            // The first document, for one-file apps (Android): same fields as before.
            String fileName,
            String fileType,
            Integer pageCount,
            String pages,
            Integer printPages,
            int copies,
            boolean color,
            Integer amountPaise,
            String currency,
            String printerName,
            Integer ordersAhead,
            boolean collected,
            Instant createdAt,
            Instant paidAt,
            Instant completedAt,
            // Several documents:
            List<DocumentView> documents,
            boolean editable,        // documents can still be added, removed or changed
            int documentsDone,
            Integer totalSheets,     // all documents, all copies
            Integer refundDuePaise   // documents cancelled at the counter after payment
    ) {}

    /** Everything the website needs to show only what the printers can really do. */
    public record ShopView(
            String centerName,
            int priceBwPaise,
            int priceColorPaise,
            String currency,
            long maxFileSizeBytes,
            int maxPages,          // most pages printed per copy of one document
            int maxFilePages,      // most pages a PDF may have (choose fewer to print)
            int maxCopies,
            int maxDocuments,      // most documents in one order
            String paymentMode,    // "razorpay" or "demo"
            boolean bwAvailable,
            boolean colorAvailable,
            boolean bwOnline,
            boolean colorOnline,
            long ordersWaiting,
            Printing printing
    ) {}

    /**
     * The printers students can use (switched on), what each can do, and the
     * words and prices for every option.
     */
    public record Printing(
            List<PrinterOption> printers,
            List<PaperOption> paperSizes,
            Map<String, String> finishing,
            Map<String, String> mediaTypes,
            PricingRules pricing,
            PrintSettings defaults
    ) {}

    public record PrinterOption(
            UUID id,
            String name,
            boolean online,
            boolean color,
            boolean bw,
            List<String> paperSizes,
            boolean duplex,
            List<String> finishing,
            List<String> mediaTypes,
            boolean borderless,
            boolean highQuality
    ) {}

    public record PaperOption(String id, String label, double widthMm, double heightMm) {}
}
