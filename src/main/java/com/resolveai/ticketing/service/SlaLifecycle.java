package com.resolveai.ticketing.service;

import com.resolveai.ticketing.domain.Ticket;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * What ticketing needs from the SLA engine, and nothing more.
 *
 * <h2>Why a port rather than a direct dependency</h2>
 *
 * <p>The SLA engine reads tickets; ticketing drives the clocks. Wiring the two concrete
 * services to each other makes a cycle, and a cycle between two large services is how a
 * codebase stops being separable. An interface owned by the <i>caller</i> breaks it: the
 * {@code sla} package depends on {@code ticketing}, and {@code ticketing} depends only on
 * this file.
 *
 * <p>It also makes the Phase 5A ordering possible. 5A ships the ticket lifecycle with
 * {@link NoOpSlaLifecycle} behind this interface, which is a real, demoable checkpoint; 5B
 * replaces the implementation without touching a single call site. The alternative — a
 * {@code // TODO: call slaService here} in six methods — is a hunt through the service layer
 * when the time comes, and at least one of the six gets missed.
 *
 * <h2>Every method runs in the caller's transaction</h2>
 *
 * <p>Not near it: in it. <b>A status change that commits while its clock pause rolls back
 * leaves a permanently wrong SLA, and nothing anywhere would report it.</b> No
 * implementation of this interface may open its own transaction.
 */
public interface SlaLifecycle {

    /**
     * Start both clocks for a ticket whose priority is known.
     *
     * <p>Idempotent: {@code uq_sla_ticket_kind} means a second call finds the existing
     * records rather than creating a second pair. In Phase 6 this call moves out of ticket
     * creation and into the triage transaction, because a clock should start when the
     * priority it is measured against is decided.
     */
    void start(Ticket ticket);

    /**
     * The first public agent reply landed. Stops the first-response clock.
     *
     * @return {@code MET}, or {@code null} if there was no running first-response clock
     */
    SlaOutcome markFirstResponseMet(Ticket ticket, OffsetDateTime at);

    /** Pause the resolution clock. Idempotent — pausing a paused clock changes nothing. */
    Map<String, Object> pauseResolution(Ticket ticket, String reason);

    /** Resume the resolution clock, recomputing its deadline from the elapsed total. */
    Map<String, Object> resumeResolution(Ticket ticket);

    /** Terminal. Judges the resolution clock {@code MET} or {@code BREACHED} and stops it. */
    Map<String, Object> stopResolution(Ticket ticket);

    /**
     * A human changed the priority; re-point the live clocks at the new target.
     *
     * <p>Separate from {@link #start} because they answer different questions. Starting
     * is idempotent and must never move an existing clock's goalposts - a second call
     * after triage must not rewrite what was promised. Retargeting is the one case where
     * moving them is the intent, and it happens only behind a human decision that
     * required a written reason.
     *
     * <p>Elapsed time carries over, so an override does not hand a ticket its spent
     * budget back.
     */
    void retarget(Ticket ticket);

    /** A reopened ticket gets a fresh resolution clock; the old one stays terminal. */
    void restartResolution(Ticket ticket);

    /** @param state the clock's new state; @param at when it got there */
    record SlaOutcome(String state, OffsetDateTime at) {
    }
}
