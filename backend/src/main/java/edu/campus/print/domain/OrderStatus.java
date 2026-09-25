package edu.campus.print.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * An order's life:
 *
 *   AWAITING_UPLOAD -> AWAITING_PAYMENT -> QUEUED -> CLAIMED -> DOWNLOADING
 *                   -> SUBMITTED -> COMPLETED
 *
 * plus FAILED, CANCELLED (only before payment, or by staff) and EXPIRED
 * (never paid). The same rules are enforced by a trigger in the database.
 */
public enum OrderStatus {
    AWAITING_UPLOAD,
    AWAITING_PAYMENT,
    QUEUED,
    CLAIMED,
    DOWNLOADING,
    SUBMITTED,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED;

    public static final Set<OrderStatus> IN_PROGRESS = EnumSet.of(QUEUED, CLAIMED, DOWNLOADING, SUBMITTED);

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == EXPIRED;
    }

    public boolean isPaid() {
        return IN_PROGRESS.contains(this) || this == COMPLETED;
    }
}
