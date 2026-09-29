package edu.campus.print.agentapi;

import com.fasterxml.jackson.databind.JsonNode;
import edu.campus.print.printing.ImageInfo;
import edu.campus.print.printing.PrintSettings;

import java.util.List;
import java.util.UUID;

/** Messages between the backend and the Xerox center PC. */
public final class AgentDtos {

    private AgentDtos() {
    }

    /** What the PC saw for one printer: READY, MISSING (not in Windows) or ERROR. */
    public record PrinterReport(UUID printerId, String status, String detail) {}

    public record HeartbeatRequest(String agentVersion, String hostName, List<PrinterReport> printers) {}

    /**
     * A printer the PC should run, as set up in the database.
     * capabilitiesHash: the version of the printer's features the server has; the PC sends
     *                   its own again when they differ. rescan: staff asked for a fresh look.
     */
    public record PrinterConfig(UUID id, String name, String windowsPrinterName,
                                boolean supportsColor, boolean acceptsBw, boolean enabled,
                                String capabilitiesHash, boolean rescan) {}

    public record HeartbeatResponse(List<PrinterConfig> printers, int leaseSeconds, String centerName) {}

    /** What one printer can do, found by the PC (see the agent's CapabilityDiscovery). */
    public record CapabilitiesReport(JsonNode capabilities, String hash) {}

    public record ClaimRequest(UUID printerId) {}

    /** The paper a job prints on. */
    public record Paper(String id, double widthMm, double heightMm) {}

    /**
     * One document of a paid order, handed to one printer (Station 4 and newer).
     * settings are exactly what the student saw on the summary before paying.
     */
    public record ClaimedJob(
            String jobId,
            String orderId,
            String claimToken,
            String printerId,
            String windowsPrinterName,
            String pickupCode,
            int documentNumber,    // 1, 2, 3... within the order
            int documentCount,     // documents in the order
            String fileName,
            String fileType,
            long fileSizeBytes,
            String sha256,
            int pageCount,
            int printPages,        // pages chosen, per copy
            int sides,             // printed sides, per copy
            int sheets,            // sheets of paper, per copy
            PrintSettings settings,
            Paper paper,
            ImageInfo image,       // pictures only
            boolean stampCode,     // print the pickup code on the first page (counter switch)
            int attempt,
            int maxAttempts,
            int leaseSeconds
    ) {}

    /**
     * The shape a Station before version 4 understands. It only ever gets
     * plain documents (A4, one-sided, fitted), which it prints correctly.
     */
    public record ClaimedOrder(
            String orderId,        // the document id: status reports come back with it
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
            boolean stampCode,
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
