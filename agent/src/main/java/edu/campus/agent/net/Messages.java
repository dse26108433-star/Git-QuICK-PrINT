package edu.campus.agent.net;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Messages exchanged with the backend (same shapes as backend AgentDtos). */
public final class Messages {

    private Messages() {
    }

    /**
     * A printer to run, as configured in the database.
     * capabilitiesHash: the version of this printer's features the server has; when it differs
     *                   from what this PC finds, the PC sends its own. rescan: staff asked for a fresh look.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrinterConfig(String id, String name, String windowsPrinterName,
                                boolean supportsColor, boolean acceptsBw, boolean enabled,
                                String capabilitiesHash, boolean rescan) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HeartbeatResult(List<PrinterConfig> printers, int leaseSeconds, String centerName) {}

    /** What this PC sees for one printer. status: READY, MISSING or ERROR. */
    public record PrinterReport(String printerId, String status, String detail) {}

    /**
     * How one document is printed: exactly what the student saw on the summary
     * before paying (backend PrintSettings).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JobSettings(
            int copies,
            boolean color,
            String pages,           // "3,7,10-12"; null = all
            String duplex,          // ONE_SIDED, LONG_EDGE, SHORT_EDGE
            String paperSize,       // A4, A3, LETTER...
            String orientation,     // AUTO, PORTRAIT, LANDSCAPE
            String scaling,         // FIT, FILL, ACTUAL, CUSTOM
            int scalePercent,
            int pagesPerSheet,
            int marginMm,           // 0 = borderless
            int rotation,           // pictures: 0, 90, 180, 270 clockwise
            boolean center,         // pictures: centred, or from the top-left margin
            boolean collate,
            String staple,          // null or TOP_LEFT, DUAL_LEFT...
            String punch,           // null or LEFT, TOP...
            String bind,            // null or LEFT, TOP...
            String mediaType,       // null or a paper type id this PC reported
            String quality          // STANDARD or HIGH
    ) {
        /** What an older server (one file per order) meant: A4, one side, fitted. */
        public static JobSettings plain(int copies, boolean color, String pages) {
            return new JobSettings(copies, color, pages, "ONE_SIDED", "A4", "AUTO", "FIT", 100, 1, 5, 0, true,
                    true, null, null, null, null, "STANDARD");
        }

        public boolean twoSided() {
            return duplex != null && !"ONE_SIDED".equals(duplex);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Paper(String id, double widthMm, double heightMm) {}

    /** A picture's size, dots per inch and camera "turn me" note, as the server measured it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImageInfo(int widthPx, int heightPx, double dpi, int exifOrientation) {}

    /** One document of a paid order, handed to one printer. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClaimedJob(
            String jobId,
            String orderId,
            String claimToken,
            String printerId,
            String windowsPrinterName,
            String pickupCode,
            int documentNumber,     // 1, 2, 3... within the order
            int documentCount,
            String fileName,
            String fileType,        // PDF, PNG or JPEG
            long fileSizeBytes,
            String sha256,
            int pageCount,          // pages in the file
            int printPages,         // pages chosen, per copy
            int sides,              // printed sides, per copy
            int sheets,             // sheets of paper, per copy
            JobSettings settings,
            Paper paper,
            ImageInfo image,        // pictures only
            Boolean stampCode,      // pickup code on the first page (counter switch); null = yes
            int attempt,
            int maxAttempts,
            int leaseSeconds
    ) {}
}
