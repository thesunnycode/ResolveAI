package com.resolveai.sla.domain;

/**
 * Whether a stretch of wall-clock time counted against the budget.
 *
 * <p>Only {@code RUNNING} segments contribute to elapsed time. {@code PAUSED} segments are
 * stored anyway, and that is the whole point of the append-only design: "this clock was
 * paused for two days waiting on the customer" is the answer to the question somebody asks
 * when they dispute a breach, and a design that only stored a running total could not
 * produce it.
 */
public enum SegmentState {
    RUNNING,
    PAUSED
}
