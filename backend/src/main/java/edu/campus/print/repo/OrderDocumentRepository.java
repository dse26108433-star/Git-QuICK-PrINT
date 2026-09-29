package edu.campus.print.repo;

import edu.campus.print.domain.OrderDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The documents of orders. Each paid document is one print job: the Xerox
 * PC claims it, reports progress with its claim token, and gives it back if
 * nothing reached a printer. As in OrderRepository, every change that could
 * race is one SQL statement with the expected current status in its WHERE.
 *
 * Note: never pass null into these native queries (PostgreSQL cannot tell
 * the type of a bare null). Pass "" and let nullif() turn it into NULL.
 */
public interface OrderDocumentRepository extends JpaRepository<OrderDocument, UUID> {

    List<OrderDocument> findByOrderIdOrderByPosition(UUID orderId);

    List<OrderDocument> findByOrderIdInOrderByPosition(Collection<UUID> orderIds);

    Optional<OrderDocument> findByIdAndOrderId(UUID id, UUID orderId);

    long countByOrderId(UUID orderId);

    @Query(value = "select coalesce(max(position), 0) from order_documents where order_id = :orderId",
            nativeQuery = true)
    int maxPosition(@Param("orderId") UUID orderId);

    /** Paid documents waiting for a printer (to find the ones no printer can do). */
    @Query(value = "select * from order_documents where status = 'QUEUED' order by created_at limit 500",
            nativeQuery = true)
    List<OrderDocument> findQueued();

    // ------------------------------------------------------------ Xerox PC side

    /** Atomic claim, see claim_next_job() in db/setup.sql. */
    @Query(value = "select * from claim_next_job(:agentId, :printerId, :leaseSeconds, :legacyOnly)",
            nativeQuery = true)
    Optional<OrderDocument> claimNext(@Param("agentId") UUID agentId,
                                      @Param("printerId") UUID printerId,
                                      @Param("leaseSeconds") int leaseSeconds,
                                      @Param("legacyOnly") boolean legacyOnly);

    /**
     * The PC reports progress. Only accepted if it still holds the document
     * (same claim token) and the document is in an expected state.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update order_documents
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

    /** Gives a document back before anything printed. Returns the new status or null. */
    @Query(value = "select release_job(:id, :claimToken, :code, :message)", nativeQuery = true)
    String release(@Param("id") UUID id, @Param("claimToken") UUID claimToken,
                   @Param("code") String code, @Param("message") String message);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update order_documents set lease_expires_at = now() + make_interval(secs => :leaseSeconds)
             where id = :id and claim_token = :claimToken
               and status in ('CLAIMED', 'DOWNLOADING', 'SUBMITTED')
            """, nativeQuery = true)
    int renewLease(@Param("id") UUID id, @Param("claimToken") UUID claimToken,
                   @Param("leaseSeconds") int leaseSeconds);

    // ------------------------------------------------------------ student side

    /** The student removed a document from a draft, or cancelled the order. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update order_documents set status = 'CANCELLED'
             where order_id = :orderId and status in ('UPLOADING', 'READY', 'REJECTED')
            """, nativeQuery = true)
    int cancelDraftDocuments(@Param("orderId") UUID orderId);

    // ------------------------------------------------------------ counter side

    /** Staff checked the tray and want this document printed again. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update order_documents d
               set status = 'QUEUED', attempts = 0, printer_id = null, agent_id = null,
                   claim_token = null, claimed_at = null, lease_expires_at = null,
                   error_code = null, error_message = null, failed_at = null
             where d.id = :id and d.status = 'FAILED' and not d.file_deleted
               and exists (select 1 from orders o where o.id = d.order_id and o.paid_at is not null
                                                     and o.collected_at is null)
            """, nativeQuery = true)
    int printAgain(@Param("id") UUID id);

    /** Every failed document of an order, printed again. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update order_documents d
               set status = 'QUEUED', attempts = 0, printer_id = null, agent_id = null,
                   claim_token = null, claimed_at = null, lease_expires_at = null,
                   error_code = null, error_message = null, failed_at = null
             where d.order_id = :orderId and d.status = 'FAILED' and not d.file_deleted
               and exists (select 1 from orders o where o.id = d.order_id and o.paid_at is not null
                                                     and o.collected_at is null)
            """, nativeQuery = true)
    int printAgainForOrder(@Param("orderId") UUID orderId);

    /** A paid document that has not started printing (or failed), cancelled by staff. Refund is due. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update order_documents
               set status = 'CANCELLED', error_code = 'CANCELLED_AT_COUNTER',
                   error_message = 'Cancelled at the counter. Refund is due.'
             where id = :id and status in ('QUEUED', 'FAILED')
            """, nativeQuery = true)
    int cancelAtCounter(@Param("id") UUID id);

    /** A whole paid order that has not started printing. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update order_documents d
               set status = 'CANCELLED', error_code = 'CANCELLED_AT_COUNTER',
                   error_message = 'Cancelled at the counter. Refund is due.'
             where d.order_id = :orderId and d.status = 'QUEUED'
               and exists (select 1 from orders o where o.id = d.order_id and o.status = 'QUEUED')
            """, nativeQuery = true)
    int cancelOrderAtCounter(@Param("orderId") UUID orderId);

    // ------------------------------------------------------------ housekeeping

    @Query(value = "select * from reap_expired_leases()", nativeQuery = true)
    List<Object[]> reapExpiredLeases();

    /**
     * Files nobody needs any more: printed, cancelled or rejected ones; failed
     * paid ones after 24 hours (until then staff can press "Print again").
     */
    @Query(value = """
            select * from order_documents
             where not file_deleted
               and (status in ('COMPLETED', 'CANCELLED', 'REJECTED')
                    or (status = 'FAILED' and failed_at < now() - interval '24 hours'))
             limit 200
            """, nativeQuery = true)
    List<OrderDocument> findFilesToDelete();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update order_documents set file_deleted = true where id = :id", nativeQuery = true)
    int markFileDeleted(@Param("id") UUID id);
}
