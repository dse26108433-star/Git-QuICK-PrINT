package edu.campus.print.agentapi;

import java.util.List;
import java.util.UUID;

/** Messages between the backend and the Xerox center PC. */
public final class AgentDtos {

    private AgentDtos() {
    }

    /** What the PC saw for one printer: READY, MISSING (not in Windows) or ERROR. */
    public record PrinterReport(UUID printerId, String status, String detail) {}

    public record HeartbeatRequest(String agentVersion, String hostName, List<PrinterReport> printers) {}

    /** A printer the PC should run, as set up in the database. */
    public record PrinterConfig(UUID id, String name, String windowsPrinterName,
                                boolean supportsColor, boolean acceptsBw, boolean enabled) {}

    public record HeartbeatResponse(List<PrinterConfig> printers, int leaseSeconds, String centerName) {}

    public record ClaimRequest(UUID printerId) {}

    public record ClaimedOrder(
            String orderId,
            String claimToken,
            String printerId,
            String windowsPrinterName,
            String pickupCode,
            String fileName,
            String fileType,
            long fileSizeBytes,
            String sha256,
            int pageCount,
            String pages,          // chosen pages, e.g. "333-390"; "" = all
            int printPages,        // pages printed per copy
            boolean stampCode,     // print the pickup code on the first page (counter switch)
            boolean color,
            int copies,
            int attempt,
            int maxAttempts,
            int leaseSeconds
    ) {}

    public record StatusReport(String claimToken, String status, String errorCode, String message) {}

    public record ReleaseRequest(String claimToken, String errorCode, String message) {}

    public record LeaseRequest(String claimToken) {}
}
