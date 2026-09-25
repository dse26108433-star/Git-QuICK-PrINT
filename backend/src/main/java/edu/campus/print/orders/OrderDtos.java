package edu.campus.print.orders;

import edu.campus.print.domain.FileType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** What the student's phone or browser sends and receives. */
public final class OrderDtos {

    private OrderDtos() {
    }

    /**
     * Step 1: "I want to print this file, in colour or B/W, this many copies."
     * pages: which pages of a PDF, e.g. "102" or "333-390" or "1-5, 8"; empty = all.
     */
    public record CreateOrderRequest(
            @NotBlank @Size(max = 200) String fileName,
            @NotNull FileType fileType,
            @Positive long fileSizeBytes,
            boolean color,
            @Min(1) int copies,
            @Size(max = PageRanges.MAX_LENGTH) String pages
    ) {}

    /**
     * accessKey is shown only here. The device keeps it and sends it back in
     * the X-Order-Key header to see or pay for this order. There is no login.
     */
    public record CreateOrderResponse(
            UUID orderId,
            String accessKey,
            String pickupCode,
            String uploadUrl,
            String uploadContentType
    ) {}

    public record ConfirmPaymentRequest(String paymentId, String signature) {}

    public record OrderView(
            UUID orderId,
            String pickupCode,
            String status,
            String stage,          // short words for the screen: "Printing on Printer 2"
            String message,        // longer help, or null
            String fileName,
            String fileType,
            Integer pageCount,     // pages in the file
            String pages,          // chosen pages, e.g. "333-390"; null = all
            Integer printPages,    // how many pages print (per copy)
            int copies,
            boolean color,
            Integer amountPaise,
            String currency,
            String printerName,
            Integer ordersAhead,
            boolean collected,
            Instant createdAt,
            Instant paidAt,
            Instant completedAt
    ) {}

    public record ShopView(
            String centerName,
            int priceBwPaise,
            int priceColorPaise,
            String currency,
            long maxFileSizeBytes,
            int maxPages,          // most pages printed per copy
            int maxFilePages,      // most pages a PDF may have (choose fewer to print)
            int maxCopies,
            String paymentMode,    // "razorpay" or "demo"
            boolean bwAvailable,
            boolean colorAvailable,
            boolean bwOnline,
            boolean colorOnline,
            long ordersWaiting
    ) {}
}
