package edu.campus.print.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * An order's life:
 *
 *   AWAITING_UPLOAD   the student adds documents and chooses settings (a draft)
 *   AWAITING_PAYMENT  the server checked every document and fixed the price
 *   QUEUED            paid; its documents wait for printers
 *   PRINTING          at least one document is printing or already printed
 *   COMPLETED         every document printed: ready at the counter
 *
 * plus FAILED (a document could not print; staff decide), CANCELLED and
 * EXPIRED (never paid). Once paid, the status follows the documents: the
 * database works it out (sync_order_status in db/setup.sql), and a trigger
 * there refuses any impossible change.
 */
public enum OrderStatus {
    AWAITING_UPLOAD,
    AWAITING_PAYMENT,
    QUEUED,
    PRINTING,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED;

    public static final Set<OrderStatus> IN_PROGRESS = EnumSet.of(QUEUED, PRINTING);

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == EXPIRED;
    }

    public boolean isPaid() {
        return IN_PROGRESS.contains(this) || this == COMPLETED;
    }
}
