package edu.campus.print.domain;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A printer connected to a Xerox center PC. Added by the Campus Print Station
 * app when staff tick it after the printer scan (or by hand in seed.sql).
 */
@Entity
@Table(name = "printers")
public class Printer {

    @Id
    private UUID id;

    @Column(nullable = false) private String name;
    @Column(name = "windows_printer_name", nullable = false) private String windowsPrinterName;
    @Column(name = "supports_color", nullable = false) private boolean supportsColor;
    @Column(name = "accepts_bw", nullable = false) private boolean acceptsBw = true;
    @Column(nullable = false) private boolean enabled = true;
    @Column(name = "agent_id") private UUID agentId;
    @Column(nullable = false) private String status = "UNKNOWN";
    @Column(name = "status_detail") private String statusDetail;
    @Column(name = "status_at") private Instant statusAt;
    @Column(name = "created_at", insertable = false, updatable = false) private Instant createdAt;

    /** A new printer for one PC. */
    public static Printer create(String name, String windowsPrinterName, boolean supportsColor, boolean acceptsBw,
                                 UUID agentId) {
        Printer p = new Printer();
        p.id = UUID.randomUUID();
        p.name = name;
        p.windowsPrinterName = windowsPrinterName;
        p.supportsColor = supportsColor;
        p.acceptsBw = acceptsBw;
        p.agentId = agentId;
        return p;
    }

    /** Ready = the PC saw it in Windows during the last minute. */
    public boolean isOnline(Duration within) {
        return enabled && "READY".equals(status) && statusAt != null
                && statusAt.isAfter(Instant.now().minus(within));
    }

    public boolean canPrint(boolean colorOrder) {
        return colorOrder ? supportsColor : acceptsBw;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getWindowsPrinterName() { return windowsPrinterName; }
    public void setWindowsPrinterName(String v) { this.windowsPrinterName = v; }
    public boolean isSupportsColor() { return supportsColor; }
    public void setSupportsColor(boolean v) { this.supportsColor = v; }
    public boolean isAcceptsBw() { return acceptsBw; }
    public void setAcceptsBw(boolean v) { this.acceptsBw = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public UUID getAgentId() { return agentId; }
    public String getStatus() { return status; }
    public String getStatusDetail() { return statusDetail; }
    public Instant getStatusAt() { return statusAt; }
}
