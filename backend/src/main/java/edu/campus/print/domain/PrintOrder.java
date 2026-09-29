package edu.campus.print.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * One student's print order (table "orders"): a pickup code, one payment,
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

    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "failed_at")    private Instant failedAt;
    @Column(name = "collected_at") private Instant collectedAt;
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
    public Instant getCompletedAt() { return completedAt; }
    public Instant getFailedAt() { return failedAt; }
    public Instant getCollectedAt() { return collectedAt; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String v) { this.errorCode = v; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
