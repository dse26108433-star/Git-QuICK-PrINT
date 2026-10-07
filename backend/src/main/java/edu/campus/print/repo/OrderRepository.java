package edu.campus.print.repo;

import edu.campus.print.domain.OrderStatus;
import edu.campus.print.domain.PrintOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
 * Once an order is paid, its status follows its documents (the database does
 * that: sync_order_status in db/setup.sql); see OrderDocumentRepository.
 */
public interface OrderRepository extends JpaRepository<PrintOrder, UUID> {

    boolean existsByPickupCode(String pickupCode);

    /** Locks the order row for the rest of the transaction (adding documents, pricing). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PrintOrder o where o.id = :id")
    Optional<PrintOrder> lockById(@Param("id") UUID id);

    List<PrintOrder> findTop200ByOrderByCreatedAtDesc();

    List<PrintOrder> findTop200ByStatusInOrderByCreatedAtDesc(Collection<OrderStatus> statuses);

    List<PrintOrder> findByPickupCodeIgnoreCase(String pickupCode);

    long countByStatusIn(Collection<OrderStatus> statuses);

    @Query("select o from PrintOrder o where o.status = edu.campus.print.domain.OrderStatus.COMPLETED "
            + "and o.collectedAt is null order by o.completedAt desc")
    List<PrintOrder> findReadyToCollect();

    /** Paid, not handed over, and at least one document failed: staff must decide. */
    @Query(value = """
            select o.* from orders o
             where o.paid_at is not null and o.collected_at is null and o.status <> 'CANCELLED'
               and (o.status = 'FAILED'
                    or exists (select 1 from order_documents d where d.order_id = o.id and d.status = 'FAILED'))
             order by coalesce(o.failed_at, o.updated_at) desc
             limit 200
            """, nativeQuery = true)
    List<PrintOrder> findProblems();

    /** How many paid orders are waiting ahead of this one. */
    @Query(value = "select count(*) from orders where status in ('QUEUED', 'PRINTING') and paid_at < :paidAt",
            nativeQuery = true)
    long countQueuedBefore(@Param("paidAt") Instant paidAt);

    // ------------------------------------------------------------ student side

    /** Payment verified: the order and all its documents join the print queue (mark_order_paid). */
    @Transactional
    @Query(value = "select mark_order_paid(:id, :provider, :paymentId)", nativeQuery = true)
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

    /** Back to editing after pricing. Only before a payment was started. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update orders set status = 'AWAITING_UPLOAD', amount_paise = null
             where id = :id and status = 'AWAITING_PAYMENT' and gateway_order_id is null
            """, nativeQuery = true)
    int backToEditing(@Param("id") UUID id);

    /**
     * The student's phone says "I am at the counter" (or, with here = false,
     * "not any more"). Only for a paid order that was not handed over yet.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update orders set arrived_at = case when :here then now() else null end
             where id = :id and paid_at is not null and collected_at is null
               and status in ('QUEUED', 'PRINTING', 'COMPLETED', 'FAILED')
            """, nativeQuery = true)
    int markArrived(@Param("id") UUID id, @Param("here") boolean here);

    // ------------------------------------------------------------ counter side

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update orders set collected_at = now()
             where id = :id and status in ('COMPLETED', 'FAILED', 'PRINTING', 'CANCELLED') and paid_at is not null
               and collected_at is null
            """, nativeQuery = true)
    int markCollected(@Param("id") UUID id);

    /** Students standing at the counter now: they opened their paid order there in the last few minutes. */
    @Query(value = """
            select * from orders
             where arrived_at > now() - make_interval(secs => :withinSeconds)
               and collected_at is null and paid_at is not null and status <> 'CANCELLED'
             order by arrived_at
             limit 30
            """, nativeQuery = true)
    List<PrintOrder> findAtCounter(@Param("withinSeconds") int withinSeconds);

    /**
     * Staff look for an order by its number or by a file's name (for a student
     * whose phone has no internet at the counter). Paid orders of the last two
     * weeks; not yet handed over first.
     */
    @Query(value = """
            select o.* from orders o
             where o.paid_at is not null and o.created_at > now() - interval '14 days'
               and (upper(o.pickup_code) = upper(:text)
                    or exists (select 1 from order_documents d
                                where d.order_id = o.id
                                  and position(lower(:text) in lower(d.file_name)) > 0))
             order by (o.collected_at is not null), o.paid_at desc
             limit 20
            """, nativeQuery = true)
    List<PrintOrder> search(@Param("text") String text);

    // ------------------------------------------------------------ housekeeping

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

    /** The student is still working on a draft: keeps it from expiring. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update orders set updated_at = now() where id = :id", nativeQuery = true)
    int touch(@Param("id") UUID id);

    /**
     * Drafts nobody touched for 2 hours, and priced orders not paid within
     * 24 hours. Their documents are cancelled by the caller, then cleaned up.
     * A XeoGo Pay order whose student said "I have paid" waits 7 days for
     * staff to check it: the money may really be in the bank.
     */
    @Query(value = """
            with expired as (
                update orders
                   set status = 'EXPIRED', error_code = 'EXPIRED', error_message = 'Not paid in time'
                 where (status = 'AWAITING_UPLOAD'  and updated_at < now() - interval '2 hours')
                    or (status = 'AWAITING_PAYMENT' and created_at < now() - interval '24 hours'
                        and payment_claimed_at is null)
                    or (status = 'AWAITING_PAYMENT' and payment_claimed_at < now() - interval '7 days')
                returning id
            )
            select id from expired
            """, nativeQuery = true)
    @Transactional
    List<UUID> expireUnpaid();

    /**
     * Orders that were never paid and ended long ago (expired, cancelled, or a
     * file that could not be used): nobody needs them, and they must not pile
     * up. Their files are gone already; their documents and history go with them.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            delete from orders o
             where o.paid_at is null and o.status in ('EXPIRED', 'CANCELLED', 'FAILED')
               and o.updated_at < now() - interval '14 days'
               and not exists (select 1 from order_documents d where d.order_id = o.id and not d.file_deleted)
            """, nativeQuery = true)
    int deleteDeadUnpaid();

    // ------------------------------------------------------------ XeoGo Pay (direct UPI) at the counter

    /** The student said "I have paid" and no bank message proved it yet: staff check these, oldest first. */
    @Query(value = """
            select * from orders
             where status = 'AWAITING_PAYMENT' and payment_provider = 'upi' and payment_claimed_at is not null
             order by payment_claimed_at
             limit 100
            """, nativeQuery = true)
    List<PrintOrder> findUpiToCheck();

    /** The UPI payment screen is open (or was, recently) and nobody said "paid" yet. */
    @Query(value = """
            select * from orders
             where status = 'AWAITING_PAYMENT' and payment_provider = 'upi' and payment_claimed_at is null
               and payment_started_at > now() - interval '3 hours'
             order by payment_started_at desc
             limit 50
            """, nativeQuery = true)
    List<PrintOrder> findUpiWaiting();

    /** Paid through XeoGo Pay today (bank message or staff), newest first. */
    @Query(value = """
            select * from orders
             where payment_provider = 'upi' and paid_at > now() - interval '24 hours'
             order by paid_at desc
             limit 50
            """, nativeQuery = true)
    List<PrintOrder> findUpiPaidRecently();
}
