package edu.campus.print.domain;

import edu.campus.print.printing.ImageInfo;
import edu.campus.print.printing.PrintSettings;
import edu.campus.print.printing.Requirements;
import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * One file of an order, with its own print settings (table "order_documents").
 * Each document is printed as its own job, on a printer that can do
 * everything its settings need. The claim/lease/journal protection against
 * double printing works per document, exactly as it did per order before.
 *
 * DynamicUpdate writes only changed columns, so an update here can never undo
 * what the Xerox PC just wrote.
 */
@Entity
@Table(name = "order_documents")
@DynamicUpdate
public class OrderDocument implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean isNew;

    @Column(name = "order_id", nullable = false) private UUID orderId;
    @Column(nullable = false) private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status = DocumentStatus.UPLOADING;

    @Column(name = "file_name", nullable = false) private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false)
    private FileType fileType;

    @Column(name = "storage_path", nullable = false) private String storagePath;
    @Column(name = "file_size_bytes") private Long fileSizeBytes;
    @Column(name = "sha256")          private String sha256;
    @Column(name = "page_count")      private Integer pageCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_info")
    private ImageInfo imageInfo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings")
    private PrintSettings settings;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requirements")
    private Requirements requirements;

    @Column(name = "legacy_ok", nullable = false) private boolean legacyOk;
    @Column(name = "print_pages")  private Integer printPages;
    @Column(name = "sides")        private Integer sides;
    @Column(name = "sheets")       private Integer sheets;
    @Column(name = "amount_paise") private Integer amountPaise;

    @Column(name = "printer_id")       private UUID printerId;
    @Column(name = "agent_id")         private UUID agentId;
    @Column(name = "claim_token")      private UUID claimToken;
    @Column(name = "claimed_at")       private Instant claimedAt;
    @Column(name = "lease_expires_at") private Instant leaseExpiresAt;
    @Column(nullable = false)          private int attempts;
    @Column(name = "max_attempts", nullable = false) private int maxAttempts = 3;

    @Column(name = "submitted_at") private Instant submittedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "failed_at")    private Instant failedAt;
    @Column(name = "file_deleted", nullable = false) private boolean fileDeleted;
    @Column(name = "error_code")    private String errorCode;
    @Column(name = "error_message") private String errorMessage;

    /** What the student sent, when that is not what is printed: DOCX for a Word file that was turned into a PDF. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type")
    private FileType sourceType;
    @Column(name = "convert_requested_at") private Instant convertRequestedAt;   // in line for the Xerox PC since
    @Column(name = "convert_claimed_at")   private Instant convertClaimedAt;     // a PC took it at
    @Column(name = "convert_agent_id")     private UUID convertAgentId;          // ...this PC
    @Column(name = "convert_attempts", nullable = false, insertable = false, updatable = false)
    private int convertAttempts;                                                  // counted by claim_next_conversion()

    @Column(name = "created_at", insertable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", insertable = false, updatable = false) private Instant updatedAt;

    /** A new document of an order, with a fresh id and its own storage path. */
    public static OrderDocument create(UUID orderId, int position, String fileName, FileType type, long size) {
        OrderDocument d = new OrderDocument();
        d.id = UUID.randomUUID();
        d.isNew = true;
        d.orderId = orderId;
        d.position = position;
        d.fileName = fileName;
        d.fileType = type;
        d.fileSizeBytes = size;
        d.storagePath = "orders/" + orderId + "/" + d.id + type.extension();
        return d;
    }

    @Override public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }

    @Override public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public int getPosition() { return position; }
    public void setPosition(int v) { this.position = v; }
    public DocumentStatus getStatus() { return status; }
    public void setStatus(DocumentStatus v) { this.status = v; }
    public String getFileName() { return fileName; }
    public FileType getFileType() { return fileType; }
    public void setFileType(FileType v) { this.fileType = v; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String v) { this.storagePath = v; }
    /** Where the PDF made from a Word file goes: never the place of the file the student sent. */
    public String convertedPath() { return "orders/" + orderId + "/" + id + "-pages.pdf"; }
    public FileType getSourceType() { return sourceType; }
    public void setSourceType(FileType v) { this.sourceType = v; }
    public Instant getConvertRequestedAt() { return convertRequestedAt; }
    public void setConvertRequestedAt(Instant v) { this.convertRequestedAt = v; }
    public Instant getConvertClaimedAt() { return convertClaimedAt; }
    public UUID getConvertAgentId() { return convertAgentId; }
    public int getConvertAttempts() { return convertAttempts; }
    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long v) { this.fileSizeBytes = v; }
    public String getSha256() { return sha256; }
    public void setSha256(String v) { this.sha256 = v; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer v) { this.pageCount = v; }
    public ImageInfo getImageInfo() { return imageInfo; }
    public void setImageInfo(ImageInfo v) { this.imageInfo = v; }
    public PrintSettings getSettings() { return settings; }
    public void setSettings(PrintSettings v) { this.settings = v; }
    public Requirements getRequirements() { return requirements; }
    public void setRequirements(Requirements v) { this.requirements = v; }
    public boolean isLegacyOk() { return legacyOk; }
    public void setLegacyOk(boolean v) { this.legacyOk = v; }
    public Integer getPrintPages() { return printPages; }
    public void setPrintPages(Integer v) { this.printPages = v; }
    public Integer getSides() { return sides; }
    public void setSides(Integer v) { this.sides = v; }
    public Integer getSheets() { return sheets; }
    public void setSheets(Integer v) { this.sheets = v; }
    public Integer getAmountPaise() { return amountPaise; }
    public void setAmountPaise(Integer v) { this.amountPaise = v; }
    public UUID getPrinterId() { return printerId; }
    public UUID getAgentId() { return agentId; }
    public UUID getClaimToken() { return claimToken; }
    public Instant getClaimedAt() { return claimedAt; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getFailedAt() { return failedAt; }
    public boolean isFileDeleted() { return fileDeleted; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String v) { this.errorCode = v; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
