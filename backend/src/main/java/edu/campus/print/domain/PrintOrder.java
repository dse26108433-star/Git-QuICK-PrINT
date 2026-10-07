package edu.campus.print.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * One student's print order (table "orders"): an order number, one payment,
 * and one or more documents (OrderDocument), each with its own settings.
 *
 * Named PrintOrder because ORDER is a reserved word in JPA queries.
 * The id is chosen before the row exists, so Persistable tells Spring Data
 * when a row is new. DynamicUpdate writes only changed columns, so an update
 * here can never undo what the database's own rules just wrote.
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

    @Column(name = "document_count")     private Integer documentCount;
    @Column(name = "amount_paise")       private Integer amountPaise;
    @Column(nullable = false)            private String currency = "INR";
    @Column(name = "payment_provider")   private String paymentProvider;
    @Column(name = "gateway_order_id")   private String gatewayOrderId;
    @Column(name = "gateway_payment_id") private String gatewayPaymentId;
    @Column(name = "paid_at")            private Instant paidAt;
    @Column(name = "payment_checked_at") private Instant paymentCheckedAt;

    // XeoGo Pay (direct UPI). Written only by the upi_* functions in db/setup.sql.
    @Column(name = "payment_started_at", insertable = false, updatable = false)  private Instant paymentStartedAt;
    @Column(name = "upi_tag_paise", insertable = false, updatable = false)       private Integer upiTagPaise;
    @Column(name = "payment_claim_ref", insertable = false, updatable = false)   private String paymentClaimRef;
    @Column(name = "payment_claimed_at", insertable = false, updatable = false)  private Instant paymentClaimedAt;
    @Column(name = "payment_note", insertable = false, updatable = false)        private String paymentNote;
    @Column(name = "payment_verified_by", insertable = false, updatable = false) private String paymentVerifiedBy;

    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "failed_at")    private Instant failedAt;
    @Column(name = "collected_at") private Instant collectedAt;
    /** The student's phone said "I am at the counter". Written only by OrderRepository.markArrived. */
    @Column(name = "arrived_at", insertable = false, updatable = false) private Instant arrivedAt;
    @Column(name = "error_code")    private String errorCode;
    @Column(name = "error_message") private String errorMessage;

    /** Free printing for college staff: the staff ID this order belongs to. Null: an ordinary order, paid for. */
    @Column(name = "staff_id", updatable = false) private UUID staffId;
    /** A staff order: the printed sides it takes from the month's free pages (set when it is reviewed). */
    @Column(name = "staff_pages") private Integer staffPages;

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
    public Integer getDocumentCount() { return documentCount; }
    public void setDocumentCount(Integer v) { this.documentCount = v; }
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
    public Instant getPaymentStartedAt() { return paymentStartedAt; }
    public Integer getUpiTagPaise() { return upiTagPaise; }
    public String getPaymentClaimRef() { return paymentClaimRef; }
    public Instant getPaymentClaimedAt() { return paymentClaimedAt; }
    public String getPaymentNote() { return paymentNote; }
    public String getPaymentVerifiedBy() { return paymentVerifiedBy; }
    /** The student opened the XeoGo Pay (UPI) payment screen for this order. */
    public boolean isUpiStarted() { return "upi".equals(paymentProvider) && paymentStartedAt != null; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getFailedAt() { return failedAt; }
    public Instant getCollectedAt() { return collectedAt; }
    public Instant getArrivedAt() { return arrivedAt; }
    /** The student opened this paid order at the counter within the last few minutes, and has not been handed it yet. */
    public boolean isAtCounter(java.time.Duration within) {
        return arrivedAt != null && collectedAt == null && paidAt != null
                && arrivedAt.isAfter(Instant.now().minus(within));
    }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String v) { this.errorCode = v; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }
    public UUID getStaffId() { return staffId; }
    public void setStaffId(UUID v) { this.staffId = v; }
    public Integer getStaffPages() { return staffPages; }
    public void setStaffPages(Integer v) { this.staffPages = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
