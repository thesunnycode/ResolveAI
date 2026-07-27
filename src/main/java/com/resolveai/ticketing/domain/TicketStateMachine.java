package com.resolveai.ticketing.domain;

import static com.resolveai.ticketing.domain.TicketStatus.ASSIGNED;
import static com.resolveai.ticketing.domain.TicketStatus.CLOSED;
import static com.resolveai.ticketing.domain.TicketStatus.IN_PROGRESS;
import static com.resolveai.ticketing.domain.TicketStatus.OPEN;
import static com.resolveai.ticketing.domain.TicketStatus.PENDING_THIRD_PARTY;
import static com.resolveai.ticketing.domain.TicketStatus.RESOLVED;
import static com.resolveai.ticketing.domain.TicketStatus.TRIAGED;
import static com.resolveai.ticketing.domain.TicketStatus.WAITING_ON_CUSTOMER;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The legal moves between ticket statuses, and what each one does to the SLA clock.
 *
 * <p><b>No Spring annotations, no repository, no clock, no I/O.</b> It is a table and three
 * lookups. That makes {@code TicketStateMachineTest} a plain JUnit test that runs in
 * milliseconds and can enumerate the entire 8 × 8 cross product, which is the only way to
 * be sure the illegal transitions are illegal rather than merely untested.
 *
 * <p>The table is the one from
 * <a href="../../../../../../../docs/planning/05-API-CONTRACT.md">doc 05 §3.2</a>. It is the
 * validation rule for {@code POST /tickets/{id}/status}; there is no second copy of it in
 * the service layer.
 *
 * <h2>Why illegal transitions are rejected by the server</h2>
 *
 * <p>The UI also greys out the buttons. That is a convenience, not a control: the API is
 * public, the mobile client is a different codebase, and an agent with curl is a normal
 * thing to have. A state machine enforced only in the browser is not enforced.
 */
public final class TicketStateMachine {

    /**
     * Built once, in a static initialiser, and never mutated. {@link EnumMap} and
     * {@link EnumSet} rather than {@code HashMap}/{@code HashSet}: both are backed by an
     * array indexed by ordinal, so a lookup is an array access and the whole table fits in a
     * cache line.
     */
    private static final Map<TicketStatus, Set<TicketStatus>> TRANSITIONS;

    static {
        Map<TicketStatus, Set<TicketStatus>> t = new EnumMap<>(TicketStatus.class);
        t.put(OPEN, EnumSet.of(TRIAGED, ASSIGNED, CLOSED));
        t.put(TRIAGED, EnumSet.of(ASSIGNED, CLOSED));
        t.put(ASSIGNED, EnumSet.of(IN_PROGRESS, WAITING_ON_CUSTOMER, PENDING_THIRD_PARTY,
                RESOLVED, OPEN));
        t.put(IN_PROGRESS, EnumSet.of(WAITING_ON_CUSTOMER, PENDING_THIRD_PARTY, RESOLVED,
                ASSIGNED));
        t.put(WAITING_ON_CUSTOMER, EnumSet.of(IN_PROGRESS, RESOLVED, CLOSED));
        t.put(PENDING_THIRD_PARTY, EnumSet.of(IN_PROGRESS, RESOLVED));
        // Reopening a RESOLVED ticket back to OPEN is legal here, but POST /reopen is the
        // endpoint that should be used: it also increments reopen_count and starts a fresh
        // resolution clock, neither of which a bare status change does.
        t.put(RESOLVED, EnumSet.of(CLOSED, OPEN));
        // Terminal. Getting out of CLOSED is POST /reopen and nothing else.
        t.put(CLOSED, EnumSet.noneOf(TicketStatus.class));
        TRANSITIONS = Collections.unmodifiableMap(t);
    }

    private TicketStateMachine() {
        // A table, not an object.
    }

    /** Every status reachable from {@code current}. Empty for {@code CLOSED}. */
    public static Set<TicketStatus> allowedFrom(TicketStatus current) {
        return TRANSITIONS.getOrDefault(current, EnumSet.noneOf(TicketStatus.class));
    }

    /**
     * Whether the move is legal.
     *
     * <p><b>A transition to the status a ticket is already in is false, not a silent
     * success.</b> Returning true would make {@code OPEN → OPEN} a no-op that still writes a
     * {@code STATUS_CHANGED} audit event recording a change that did not happen, and the
     * timeline is the one artefact that has to be trustworthy.
     */
    public static boolean canTransition(TicketStatus from, TicketStatus to) {
        return allowedFrom(from).contains(to);
    }

    /**
     * What this transition does to the resolution clock.
     *
     * <p><b>Precedence matters and is deliberate: STOP beats PAUSE beats RESUME.</b>
     * {@code WAITING_ON_CUSTOMER → RESOLVED} both leaves a waiting state and enters a
     * terminal one; resuming a clock in order to immediately stop it would append two
     * pointless segments and could tip a record over a rung on the way past. Terminal wins.
     *
     * <p>{@code CLOSED} stops the clock too, which goes one step beyond doc 05's table.
     * Reason: {@code WAITING_ON_CUSTOMER → CLOSED} is how an abandoned ticket ends, and
     * leaving its resolution clock {@code PAUSED} for ever means a closed ticket sits in the
     * SLA reporting as though it were still in flight.
     *
     * <p>Returns {@link SlaEffect#NONE} for an illegal transition rather than throwing —
     * callers check {@link #canTransition} first, and a method that answers a question about
     * a hypothetical should not have opinions about whether it will happen.
     */
    public static SlaEffect sideEffectOf(TicketStatus from, TicketStatus to) {
        if (to == RESOLVED || to == CLOSED) {
            return SlaEffect.STOP;
        }
        if (to.isWaiting()) {
            return SlaEffect.PAUSE;
        }
        if (from.isWaiting()) {
            return SlaEffect.RESUME;
        }
        return SlaEffect.NONE;
    }

    /** The allowed set as strings, for the {@code allowedTransitions} field on a 409. */
    public static List<String> allowedNamesFrom(TicketStatus current) {
        return allowedFrom(current).stream().map(Enum::name).sorted().toList();
    }
}
