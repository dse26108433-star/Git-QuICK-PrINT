package edu.campus.print.domain;

import com.fasterxml.jackson.databind.JsonNode;
import edu.campus.print.printing.EffectiveFeatures;
import edu.campus.print.printing.OfferedFeatures;
import edu.campus.print.printing.PrinterFeatures;
import edu.campus.print.printing.PrinterRules;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A printer connected to a Xerox center PC. Added by the XeoGo Station
 * app when staff tick it after the printer scan (or by hand in seed.sql).
 *
 * capabilities: what the printer can do, as Windows reports it (paper sizes,
 *               two-sided, stapling, paper types...). The Station finds it and
 *               sends it again whenever it changes.
 * offered:      what staff let students choose here (null = sensible defaults).
 * effective:    worked out from the two (EffectiveFeatures) whenever either
 *               changes; the print queue and the website use only this.
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capabilities")
    private JsonNode capabilities;

    @Column(name = "capabilities_hash") private String capabilitiesHash;
    @Column(name = "capabilities_at") private Instant capabilitiesAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "offered")
    private OfferedFeatures offered;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "effective")
    private PrinterFeatures effective;

    @Column(name = "rescan_requested", nullable = false) private boolean rescanRequested;
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
        p.effective = PrinterFeatures.PLAIN_A4;
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

    /** What students can get here; never null. */
    public PrinterFeatures features() {
        return effective == null ? PrinterFeatures.PLAIN_A4 : effective;
    }

    public PrinterRules.Candidate candidate() {
        return new PrinterRules.Candidate(name, supportsColor, acceptsBw, features());
    }

    /** New capabilities from the Station, or new choices by staff: work out what students can get again. */
    public void recompute() {
        this.effective = EffectiveFeatures.compute(capabilities, offered);
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
    public JsonNode getCapabilities() { return capabilities; }
    public void setCapabilities(JsonNode v) { this.capabilities = v; }
    public String getCapabilitiesHash() { return capabilitiesHash; }
    public void setCapabilitiesHash(String v) { this.capabilitiesHash = v; }
    public Instant getCapabilitiesAt() { return capabilitiesAt; }
    public void setCapabilitiesAt(Instant v) { this.capabilitiesAt = v; }
    public OfferedFeatures getOffered() { return offered; }
    public void setOffered(OfferedFeatures v) { this.offered = v; }
    public PrinterFeatures getEffective() { return effective; }
    public boolean isRescanRequested() { return rescanRequested; }
    public void setRescanRequested(boolean v) { this.rescanRequested = v; }
}
