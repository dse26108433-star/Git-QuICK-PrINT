package edu.campus.print.schedule;

import edu.campus.print.domain.OrderDocument;
import edu.campus.print.domain.PrintOrder;
import edu.campus.print.orders.OrderService;
import edu.campus.print.repo.OrderDocumentRepository;
import edu.campus.print.repo.OrderRepository;
import edu.campus.print.storage.SupabaseStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Background jobs:
 *   - recovery when the Xerox PC stops answering (every 30 s)
 *   - "was this actually paid?" checks with Razorpay (every minute)
 *   - expiring unpaid orders and deleting files nobody needs (every 5 min)
 */
@Component
public class Housekeeping {

    private static final Logger log = LoggerFactory.getLogger(Housekeeping.class);

    private final OrderRepository orders;
    private final OrderDocumentRepository documents;
    private final OrderService orderService;
    private final SupabaseStorage storage;

    public Housekeeping(OrderRepository orders, OrderDocumentRepository documents, OrderService orderService,
                        SupabaseStorage storage) {
        this.orders = orders;
        this.documents = documents;
        this.orderService = orderService;
        this.storage = storage;
    }

    /**
     * Rules live in reap_expired_leases() (db/setup.sql). Not yet at a printer
     * -> back on the queue. Maybe already printed -> FAILED for staff to check.
     * Never an automatic reprint.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    @Transactional
    public void recoverStuckOrders() {
        try {
            List<Object[]> rows = documents.reapExpiredLeases();
            if (!rows.isEmpty()) {
                int requeued = ((Number) rows.get(0)[0]).intValue();
                int failed = ((Number) rows.get(0)[1]).intValue();
                if (requeued > 0 || failed > 0) {
                    log.warn("Recovery: {} file(s) put back in the queue, {} marked for staff to check",
                            requeued, failed);
                }
            }
        } catch (Exception e) {
            log.error("Recovery check failed; will retry", e);
        }
    }

    /** Catches payments where the student closed the app before we heard back. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void checkPayments() {
        try {
            for (PrintOrder o : orders.findPaymentsToCheck()) {
                orderService.reconcile(o);
            }
        } catch (Exception e) {
            log.error("Payment check for orders failed; will retry", e);
        }
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void cleanUp() {
        try {
            List<UUID> expired = orders.expireUnpaid();
            for (UUID id : expired) {
                documents.cancelDraftDocuments(id);
            }
            if (!expired.isEmpty()) {
                log.info("Expired {} unpaid order(s)", expired.size());
            }
            for (OrderDocument d : documents.findFilesToDelete()) {
                storage.delete(d.getStoragePath());
                documents.markFileDeleted(d.getId());
            }
        } catch (Exception e) {
            log.error("Clean-up failed; will retry", e);
        }
    }
}
