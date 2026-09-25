package edu.campus.agent.net;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Messages exchanged with the backend (same shapes as backend AgentDtos). */
public final class Messages {

    private Messages() {
    }

    /** A printer to run, as configured in the database. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrinterConfig(String id, String name, String windowsPrinterName,
                                boolean supportsColor, boolean acceptsBw, boolean enabled) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HeartbeatResult(List<PrinterConfig> printers, int leaseSeconds, String centerName) {}

    /** What this PC sees for one printer. status: READY, MISSING or ERROR. */
    public record PrinterReport(String printerId, String status, String detail) {}

    /** One paid order handed to one printer. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClaimedOrder(
            String orderId,
            String claimToken,
            String printerId,
            String windowsPrinterName,
            String pickupCode,
            String fileName,
            String fileType,        // PDF, PNG or JPEG
            long fileSizeBytes,
            String sha256,
            int pageCount,          // pages in the file
            String pages,           // pages the student chose, e.g. "333-390"; empty = all
            int printPages,         // pages printed per copy
            Boolean stampCode,      // pickup code on the first page (counter switch); null = older backend = yes
            boolean color,
            int copies,
            int attempt,
            int maxAttempts,
            int leaseSeconds
    ) {}
}
