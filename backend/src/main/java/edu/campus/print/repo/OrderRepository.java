package edu.campus.print.repo;

import edu.campus.print.domain.OrderStatus;
import edu.campus.print.domain.PrintOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every status change that could race with something else is ONE SQL
 * statement with the expected current status in its WHERE clause. If the
 * order already moved on, 0 rows change and the caller knows it lost.
 *
 * Note: never pass null into these native queries (PostgreSQL cannot tell
 * the type of a bare null). Pass "" and let nullif() turn it into NULL.
 */
public interface OrderRepository extends JpaRepository<PrintOrder, UUID> {

    boolean existsByPickupCode(String pickupCode);

    List<PrintOrder> findTop200ByOrderByCreatedAtDesc();

    List<PrintOrder> findTop200ByStatusInOrderByCreatedAtDesc(Collection<OrderStatus> statuses);

    List<PrintOrder> findByPickupCodeIgnoreCase(String pickupCode);

    long countByStatusIn(Collection<OrderStatus> statuses);

    @Query("select o from PrintOrder o where o.status = edu.campus.print.domain.OrderStatus.COMPLETED "
            + "and o.collectedAt is null order by o.completedAt desc")
    List<PrintOrder> findReadyToCollect();

    @Query("select o from PrintOrder o where o.status = edu.campus.print.domain.OrderStatus.FAILED "
            + "and o.paidAt is not null and o.collectedAt is null order by o.failedAt desc")
    List<PrintOrder> findPaidButFailed();

    /** How many paid orders are waiting ahead of this one. */
    @Query(value = "select count(*) from orders where status = 'QUEUED' and paid_at < :paidAt", nativeQuery = true)
    long countQueuedBefore(@Param("paidAt") Instant paidAt);

    // ------------------------------------------------------------ student side

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update orders
               set status = 'QUEUED', payment_provider = :provider,
                   gateway_payment_id = :paymentId, paid_at = now()
             where id = :id and status = 'AWAITING_PAYMENT'
            """, nativeQuery = true)
    int markPaid(@Param("id") UUID id, @Param("provider") String provider, @Param("paymentId") String paymentId);

    /** Stores the Razorpay order id once; a double-click cannot create a second one. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update orders set gateway_order_id = :gatewayOrderId, payment_provider = :provider
             where id = :id and gateway_order_id is null and status = 'AWAITING_PAYMENT'
            """, nativeQuery = true)
    int setGatewayOrder(@Param("id") UUID id, @Param("gatewayOrderId") String gatewayOrderId,
                        @Param("provider") String provider);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update orders
               set status = 'CANCELLED', error_code = 'CANCELLED', error_message = 'Cancelled before payment'
             where id = :id and status in ('AWAITING_UPLOAD', 'AWAITING_PAYMENT')
            """, nativeQuery = true)
    int cancelUnpaid(@Param("id") UUID id);

    // ------------------------------------------------------------ Xerox PC side

    /** Atomic claim, see claim_next_order() in db/setup.sql. */
    @Query(value = "select * from claim_next_order(:agentId, :printerId, :leaseSeconds)", nativeQuery = true)
    Optional<PrintOrder> claimNext(@Param("agentId") UUID agentId,
                                   @Param("printerId") UUID printerId,
                                   @Param("leaseSeconds") int leaseSeconds);

    /**
     * The PC reports progress. Only accepted if it still holds the order
     * (same claim token) and the order is in an expected state.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update orders
               set status           = :newStatus,
                   lease_expires_at = case when :newStatus in ('COMPLETED', 'FAILED') then null
                                           else now() + make_interval(secs => :leaseSeconds) end,
                   error_code       = nullif(:errorCode, ''),
                   error_message    = nullif(:errorMessage, ''),
                   submitted_at     = case when :newStatus = 'SUBMITTED' then now() else submitted_at end,
                   completed_at     = case when :newStatus = 'COMPLETED' then now() else completed_at end,
                   failed_at        = case when :newStatus = 'FAILED' then now() else failed_at end
             where id          = :id
               and claim_token = :claimToken
               and status      = any (cast(:allowedFrom as text[]))
               and (status <> 'FAILED' or error_code = 'AGENT_LOST_AFTER_SUBMIT')
            """, nativeQuery = true)
    int applyAgentStatus(@Param("id") UUID id,
                         @Param("claimToken") UUID claimToken,
                         @Param("newStatus") String newStatus,
                         @Param("allowedFrom") String allowedFrom,
                         @Param("leaseSeconds") int leaseSeconds,
                         @Param("errorCode") String errorCode,
                         @Param("errorMessage") String errorMessage);

    /** Gives an order back before anything printed. Returns the new status or null. */
    @Query(value = "select release_order(:id, :claimToken, :code, :message)", nativeQuery = true)
    String release(@Param("id") UUID id, @Param("claimToken") UUID claimToken,
                   @Param("code") String code, @Param("message") String message);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update orders set lease_expires_at = now() + make_interval(secs => :leaseSeconds)
             where id = :id and claim_token = :claimToken
               and status in ('CLAIMED', 'DOWNLOADING', 'SUBMITTED')
            """, nativeQuery = true)
    int renewLease(@Param("id") UUID id, @Param("claimToken") UUID claimToken,
                   @Param("leaseSeconds") int leaseSeconds);

    // ------------------------------------------------------------ counter side

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update orders set collected_at = now() where id = :id and status in ('COMPLETED', 'FAILED') and collected_at is null",
            nativeQuery = true)
    int markCollected(@Param("id") UUID id);

    /** Staff checked the tray and want it printed again. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update orders
               set status = 'QUEUED', attempts = 0, printer_id = null, agent_id = null,
                   claim_token = null, claimed_at = null, lease_expires_at = null,
                   error_code = null, error_message = null, failed_at = null
             where id = :id and status = 'FAILED' and paid_at is not null and not file_deleted
            """, nativeQuery = true)
    int printAgain(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update orders
               set status = 'CANCELLED', error_code = 'CANCELLED_AT_COUNTER',
                   error_message = 'Cancelled at the counter. Refund is due.'
             where id = :id and status = 'QUEUED'
            """, nativeQuery = true)
    int cancelAtCounter(@Param("id") UUID id);

    // ------------------------------------------------------------ housekeeping

    @Query(value = "select * from reap_expired_leases()", nativeQuery = true)
    List<Object[]> reapExpiredLeases();

    /** Unpaid orders with a payment started recently: ask Razorpay if they were paid. */
    @Query(value = """
            select * from orders
             where status = 'AWAITING_PAYMENT' and gateway_order_id is not null
               and created_at > now() - interval '24 hours'
               and (payment_checked_at is null or payment_checked_at < now() - interval '45 seconds')
             order by created_at desc
             limit 50
            """, nativeQuery = true)
    List<PrintOrder> findPaymentsToCheck();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update orders set payment_checked_at = now() where id = :id", nativeQuery = true)
    int touchPaymentCheck(@Param("id") UUID id);

    /** Uploads never finished after 1 hour, payments never made after 24 hours. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update orders
               set status = 'EXPIRED', error_code = 'EXPIRED', error_message = 'Not paid in time'
             where (status = 'AWAITING_UPLOAD'  and created_at < now() - interval '1 hour')
                or (status = 'AWAITING_PAYMENT' and created_at < now() - interval '24 hours')
            """, nativeQuery = true)
    int expireUnpaid();

    /**
     * Files nobody needs any more. Failed PAID orders keep their file for 24
     * hours so staff can press "Print again".
     */
    @Query(value = """
            select * from orders
             where not file_deleted
               and (status in ('COMPLETED', 'CANCELLED', 'EXPIRED')
                    or (status = 'FAILED' and (paid_at is null or failed_at < now() - interval '24 hours')))
             limit 200
            """, nativeQuery = true)
    List<PrintOrder> findFilesToDelete();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update orders set file_deleted = true where id = :id", nativeQuery = true)
    int markFileDeleted(@Param("id") UUID id);
}
