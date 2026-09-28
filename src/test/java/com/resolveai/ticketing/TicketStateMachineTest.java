package com.resolveai.ticketing;

import static com.resolveai.ticketing.domain.TicketStatus.ASSIGNED;
import static com.resolveai.ticketing.domain.TicketStatus.CLOSED;
import static com.resolveai.ticketing.domain.TicketStatus.IN_PROGRESS;
import static com.resolveai.ticketing.domain.TicketStatus.OPEN;
import static com.resolveai.ticketing.domain.TicketStatus.PENDING_THIRD_PARTY;
import static com.resolveai.ticketing.domain.TicketStatus.RESOLVED;
import static com.resolveai.ticketing.domain.TicketStatus.TRIAGED;
import static com.resolveai.ticketing.domain.TicketStatus.WAITING_ON_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.ticketing.domain.SlaEffect;
import com.resolveai.ticketing.domain.TicketStateMachine;
import com.resolveai.ticketing.domain.TicketStatus;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The whole 8 × 8 cross product of ticket statuses, every pair asserted explicitly.
 *
 * <p><b>Enumerating all 64 rather than only the 21 legal ones is the point of this file.</b>
 * A test that checks the legal transitions work proves nothing about the illegal ones; the
 * bug worth catching is a table entry that lets {@code CLOSED → IN_PROGRESS} through. And
 * because the expectation is a hand-written table rather than a second copy of the
 * implementation, this test fails if the production table is edited — including by adding a
 * ninth status, which is exactly the moment somebody should be made to think about what it
 * may transition to and what it does to the clock.
 *
 * <p>No Spring context. The class under test is a static table, so the whole file runs in
 * single-digit milliseconds and can be run on every save.
 */
class TicketStateMachineTest {

    /**
     * The legal transitions, written out independently of the implementation.
     *
     * <p>Copied from doc 05 §3.2 by hand, deliberately. Deriving it from
     * {@code TicketStateMachine} would make the test tautological: it would pass whatever the
     * table said, including whatever somebody broke.
     */
    private static final Map<TicketStatus, Set<TicketStatus>> EXPECTED_LEGAL = Map.of(
            OPEN, Set.of(TRIAGED, ASSIGNED, CLOSED),
            TRIAGED, Set.of(ASSIGNED, CLOSED),
            ASSIGNED, Set.of(IN_PROGRESS, WAITING_ON_CUSTOMER, PENDING_THIRD_PARTY, RESOLVED, OPEN),
            IN_PROGRESS, Set.of(WAITING_ON_CUSTOMER, PENDING_THIRD_PARTY, RESOLVED, ASSIGNED),
            WAITING_ON_CUSTOMER, Set.of(IN_PROGRESS, RESOLVED, CLOSED),
            PENDING_THIRD_PARTY, Set.of(IN_PROGRESS, RESOLVED),
            RESOLVED, Set.of(CLOSED, OPEN),
            CLOSED, Set.of());

    static Stream<Arguments> everyPair() {
        return Stream.of(TicketStatus.values())
                .flatMap(from -> Stream.of(TicketStatus.values())
                        .map(to -> Arguments.of(from, to,
                                EXPECTED_LEGAL.get(from).contains(to))));
    }

    @ParameterizedTest(name = "{0} -> {1} is {2}")
    @MethodSource("everyPair")
    @DisplayName("every one of the 64 status pairs is explicitly legal or explicitly illegal")
    void everyPairMatchesTheContract(TicketStatus from, TicketStatus to, boolean expected) {
        assertThat(TicketStateMachine.canTransition(from, to)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the cross product really is 64 cases, so nothing was quietly dropped")
    void theCrossProductIsComplete() {
        assertThat(everyPair()).hasSize(64);
        assertThat(EXPECTED_LEGAL).hasSize(TicketStatus.values().length);
    }

    @Test
    @DisplayName("CLOSED is terminal and self-transitions are never legal")
    void closedIsTerminalAndNothingLoops() {
        assertThat(TicketStateMachine.allowedFrom(CLOSED)).isEmpty();
        assertThat(TicketStateMachine.canTransition(CLOSED, IN_PROGRESS)).isFalse();

        // A no-op transition would still write a STATUS_CHANGED event recording a change
        // that did not happen, which quietly corrupts the one artefact that has to be
        // trustworthy.
        for (TicketStatus status : TicketStatus.values()) {
            assertThat(TicketStateMachine.canTransition(status, status))
                    .as("%s -> %s", status, status)
                    .isFalse();
        }
    }

    // ── Clock effects ───────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"ASSIGNED", "IN_PROGRESS"})
    @DisplayName("entering a waiting state pauses the clock")
    void enteringAWaitingStatePauses(TicketStatus from) {
        assertThat(TicketStateMachine.sideEffectOf(from, WAITING_ON_CUSTOMER))
                .isEqualTo(SlaEffect.PAUSE);
        assertThat(TicketStateMachine.sideEffectOf(from, PENDING_THIRD_PARTY))
                .isEqualTo(SlaEffect.PAUSE);
    }

    @Test
    @DisplayName("leaving a waiting state for IN_PROGRESS resumes the clock")
    void leavingAWaitingStateResumes() {
        assertThat(TicketStateMachine.sideEffectOf(WAITING_ON_CUSTOMER, IN_PROGRESS))
                .isEqualTo(SlaEffect.RESUME);
        assertThat(TicketStateMachine.sideEffectOf(PENDING_THIRD_PARTY, IN_PROGRESS))
                .isEqualTo(SlaEffect.RESUME);
    }

    @Test
    @DisplayName("PAUSE and RESUME apply to exactly the transitions that cross a waiting state")
    void pauseAndResumeAreExhaustive() {
        List<String> pausing = everyPair()
                .filter(a -> TicketStateMachine.canTransition(from(a), to(a)))
                .filter(a -> TicketStateMachine.sideEffectOf(from(a), to(a)) == SlaEffect.PAUSE)
                .map(a -> from(a) + "->" + to(a))
                .sorted()
                .toList();
        List<String> resuming = everyPair()
                .filter(a -> TicketStateMachine.canTransition(from(a), to(a)))
                .filter(a -> TicketStateMachine.sideEffectOf(from(a), to(a)) == SlaEffect.RESUME)
                .map(a -> from(a) + "->" + to(a))
                .sorted()
                .toList();

        assertThat(pausing).containsExactly(
                "ASSIGNED->PENDING_THIRD_PARTY", "ASSIGNED->WAITING_ON_CUSTOMER",
                "IN_PROGRESS->PENDING_THIRD_PARTY", "IN_PROGRESS->WAITING_ON_CUSTOMER");
        assertThat(resuming).containsExactly(
                "PENDING_THIRD_PARTY->IN_PROGRESS", "WAITING_ON_CUSTOMER->IN_PROGRESS");
    }

    @Test
    @DisplayName("a terminal destination stops the clock even when leaving a waiting state")
    void terminalBeatsResume() {
        // The precedence case. WAITING_ON_CUSTOMER -> RESOLVED both leaves a waiting state
        // and enters a terminal one; resuming a clock in order to stop it a microsecond
        // later would append two pointless segments and could tip the record past a rung on
        // the way through.
        assertThat(TicketStateMachine.sideEffectOf(WAITING_ON_CUSTOMER, RESOLVED))
                .isEqualTo(SlaEffect.STOP);
        assertThat(TicketStateMachine.sideEffectOf(WAITING_ON_CUSTOMER, CLOSED))
                .isEqualTo(SlaEffect.STOP);
        assertThat(TicketStateMachine.sideEffectOf(PENDING_THIRD_PARTY, RESOLVED))
                .isEqualTo(SlaEffect.STOP);
    }

    @Test
    @DisplayName("an ordinary move between working states touches no clock")
    void ordinaryMovesAreNone() {
        assertThat(TicketStateMachine.sideEffectOf(OPEN, ASSIGNED)).isEqualTo(SlaEffect.NONE);
        assertThat(TicketStateMachine.sideEffectOf(ASSIGNED, IN_PROGRESS)).isEqualTo(SlaEffect.NONE);
        assertThat(TicketStateMachine.sideEffectOf(RESOLVED, OPEN)).isEqualTo(SlaEffect.NONE);
    }

    @Test
    @DisplayName("allowedNamesFrom is sorted, so a 409 body is stable across runs")
    void allowedNamesAreSorted() {
        assertThat(TicketStateMachine.allowedNamesFrom(OPEN))
                .containsExactly("ASSIGNED", "CLOSED", "TRIAGED");
        assertThat(TicketStateMachine.allowedNamesFrom(CLOSED)).isEmpty();
    }

    private static TicketStatus from(Arguments a) {
        return (TicketStatus) a.get()[0];
    }

    private static TicketStatus to(Arguments a) {
        return (TicketStatus) a.get()[1];
    }
}
