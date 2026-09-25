package edu.campus.print.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * One student's print order (table "orders").
 *
 * Named PrintOrder because ORDER is a reserved word in JPA queries.
 * The id is chosen before the row exists (the storage path is built from it),
 * so Persistable tells Spring Data when a row is new. DynamicUpdate writes only
 * changed columns, so an update here can never undo what the PC just wrote.
 */
@Entity(name = "PrintOrder")
@Table(name = "orders")
@DynamicUpdate
public class PrintOrder implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean isNew;

    @Column(name = "pickup_code", nullable = false)
    private String pickupCode;

    @Column(name = "access_key_hash", nullable = false)
    private String accessKeyHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.AWAITING_UPLOAD;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false)
    private FileType fileType;

    @Column(name = "storage_path", nullable = false)
    private String storagePath;

    @Column(name = "file_size_bytes") private Long fileSizeBytes;
    @Column(name = "page_count")      private Integer pageCount;     // pages in the file
    @Column(name = "page_ranges")     private String pageRanges;     // chosen pages "333-390"; null = all
    @Column(name = "print_pages")     private Integer printPages;    // pages printed per copy
    @Column(name = "sha256")          private String sha256;

    @Column(nullable = false) private boolean color;
    @Column(nullable = false) private int copies = 1;

    @Column(name = "amount_paise")       private Integer amountPaise;
    @Column(nullable = false)            private String currency = "INR";
    @Column(name = "payment_provider")   private String paymentProvider;
    @Column(name = "gateway_order_id")   private String gatewayOrderId;
    @Column(name = "gateway_payment_id") private String gatewayPaymentId;
    @Column(name = "paid_at")            private Instant paidAt;
    @Column(name = "payment_checked_at") private Instant paymentCheckedAt;

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
    @Column(name = "collected_at") private Instant collectedAt;
    @Column(name = "file_deleted", nullable = false) private boolean fileDeleted;
    @Column(name = "error_code")    private String errorCode;
    @Column(name = "error_message") private String errorMessage;

    @Column(name = "created_at", insertable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", insertable = false, updatable = false) private Instant updatedAt;

    /** A brand-new order with a fresh id. */
    public static PrintOrder create() {
        PrintOrder o = new PrintOrder();
        o.id = UUID.randomUUID();
        o.isNew = true;
        return o;
    }

    @Override public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }

    @Override public UUID getId() { return id; }
    public String getPickupCode() { return pickupCode; }
    public void setPickupCode(String v) { this.pickupCode = v; }
    public String getAccessKeyHash() { return accessKeyHash; }
    public void setAccessKeyHash(String v) { this.accessKeyHash = v; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus v) { this.status = v; }
    public String getFileName() { return fileName; }
    public void setFileName(String v) { this.fileName = v; }
    public FileType getFileType() { return fileType; }
    public void setFileType(FileType v) { this.fileType = v; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String v) { this.storagePath = v; }
    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long v) { this.fileSizeBytes = v; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer v) { this.pageCount = v; }
    public String getPageRanges() { return pageRanges; }
    public void setPageRanges(String v) { this.pageRanges = v; }
    public Integer getPrintPages() { return printPages != null ? printPages : pageCount; }
    public void setPrintPages(Integer v) { this.printPages = v; }
    public String getSha256() { return sha256; }
    public void setSha256(String v) { this.sha256 = v; }
    public boolean isColor() { return color; }
    public void setColor(boolean v) { this.color = v; }
    public int getCopies() { return copies; }
    public void setCopies(int v) { this.copies = v; }
    public Integer getAmountPaise() { return amountPaise; }
    public void setAmountPaise(Integer v) { this.amountPaise = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public String getPaymentProvider() { return paymentProvider; }
    public void setPaymentProvider(String v) { this.paymentProvider = v; }
    public String getGatewayOrderId() { return gatewayOrderId; }
    public void setGatewayOrderId(String v) { this.gatewayOrderId = v; }
    public String getGatewayPaymentId() { return gatewayPaymentId; }
    public Instant getPaidAt() { return paidAt; }
    public Instant getPaymentCheckedAt() { return paymentCheckedAt; }
    public UUID getPrinterId() { return printerId; }
    public UUID getAgentId() { return agentId; }
    public UUID getClaimToken() { return claimToken; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getFailedAt() { return failedAt; }
    public Instant getCollectedAt() { return collectedAt; }
    public boolean isFileDeleted() { return fileDeleted; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String v) { this.errorCode = v; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
