package edu.campus.print.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * One document's life. Each document of an order is printed as its own job,
 * on a printer that can do everything its settings need.
 *
 *   UPLOADING -> READY (checked, pages counted)      or REJECTED (cannot be printed)
 *   READY     -> QUEUED (the order was paid)
 *   QUEUED    -> CLAIMED -> DOWNLOADING -> SUBMITTED -> COMPLETED
 *
 * plus FAILED (staff decide: print again or refund) and CANCELLED. The same
 * rules are enforced by a trigger in the database (guard_document_transition).
 */
public enum DocumentStatus {
    UPLOADING,
    READY,
    REJECTED,
    QUEUED,
    CLAIMED,
    DOWNLOADING,
    SUBMITTED,
    COMPLETED,
    FAILED,
    CANCELLED;

    /** A printer holds it right now. */
    public static final Set<DocumentStatus> AT_PRINTER = EnumSet.of(CLAIMED, DOWNLOADING, SUBMITTED);

    /** Still part of a draft order (before payment). */
    public static final Set<DocumentStatus> DRAFT = EnumSet.of(UPLOADING, READY, REJECTED);
}
