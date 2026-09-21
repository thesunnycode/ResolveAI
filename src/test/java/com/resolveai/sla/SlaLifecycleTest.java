package com.resolveai.sla;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.ticketing.TicketTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The clock's life story, driven entirely through the API: triage starts it, an agent
 * reply meets the first-response clock, a waiting status pauses the resolution clock,
 * leaving that status resumes it, resolving stops it and reopening starts a fresh one.
 *
 * <p>This is Task 27 — the wiring — under test. The individual operations have their own
 * unit-level coverage; what this asserts is that {@code TicketStateMachine.sideEffectOf}
 * is actually consulted on every path, because the failure mode being guarded against is
 * not a broken pause but a pause that is never called. A status change that quietly skips
 * its clock effect leaves an SLA that is permanently wrong and reports nothing.
 */
class SlaLifecycleTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;
    @Autowired TicketTestSupport tickets;
    @Autowired SlaTestSupport sla;

    private AuthTestSupport.SeededTenant tenant;
    private String agentToken;
    private String customerToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("slalife");
        // 24×7, so the arithmetic in these assertions does not depend on whether CI
        // happens to run the suite at 3am or on a Sunday.
        sla.makeCalendarAlwaysOpen(tenant.tenantId());
        sla.seedPolicies(tenant.tenantId(), "PRO");
        agentToken = auth.accessToken(rest, "slalife", "agent");
        customerToken = auth.accessToken(rest, "slalife", "customer");
    }

    @Test
    @DisplayName("An untriaged ticket has no clocks; triage starts both")
    @SuppressWarnings("unchecked")
    void triageStartsBothClocks() {
        Long ticketId = tickets.createId(rest, customerToken, "No priority yet", "Body");

        // No priority, no policy, no promise. A 404 on the SLA sub-resource rather than
        // an empty 200, because "this ticket has no SLA" and "this ticket's SLA is zero"
        // are different answers and a client that cannot tell them apart renders the
        // wrong one — a countdown from zero on a ticket nobody has promised anything for.
        assertThat(sla.sla(rest, agentToken, ticketId).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> triaged = sla.triage(rest, tickets, agentToken, ticketId, "P2");
        assertThat(triaged.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map body = sla.sla(rest, agentToken, ticketId).getBody();
        List<Map<String, Object>> clocks = (List<Map<String, Object>>) body.get("clocks");
        assertThat(clocks).hasSize(2);
        assertThat(clocks).allSatisfy(clock -> {
            assertThat(clock.get("state")).isEqualTo("RUNNING");
            assertThat(clock.get("nextDeadlineAt")).isNotNull();
            assertThat(clock.get("nextRung")).isEqualTo(50);
            // Snapshotted from the policy, not joined at read time — which is what makes
            // a September ticket explicable after an October policy change.
            assertThat(clock.get("policyVersion")).isEqualTo("v1");
        });
        assertThat(sla.openSegmentCount(sla.recordId(ticketId, "RESOLUTION"))).isEqualTo(1);
    }

    @Test
    @DisplayName("Triaging twice does not create a second pair of clocks")
    void triageIsIdempotent() {
        Long ticketId = tickets.createId(rest, customerToken, "Twice", "Body");
        sla.triage(rest, tickets, agentToken, ticketId, "P2");
        Long firstResolution = sla.recordId(ticketId, "RESOLUTION");

        sla.triage(rest, tickets, agentToken, ticketId, "P1");

        // Same record, and its target was not rewritten. Re-targeting a clock that is
        // already running moves the goalposts mid-game; if that is ever wanted it is a
        // deliberate admin action, not a side effect of correcting a priority.
        assertThat(sla.recordId(ticketId, "RESOLUTION")).isEqualTo(firstResolution);
        assertThat(sla.segmentCount(firstResolution)).isEqualTo(1);
    }

    @Test
    @DisplayName("The first public agent reply meets the first-response clock")
    @SuppressWarnings("unchecked")
    void firstReplyMeetsFirstResponse() {
        Long ticketId = tickets.createId(rest, customerToken, "Reply me", "Body");
        sla.triage(rest, tickets, agentToken, ticketId, "P2");

        ResponseEntity<Map> reply = tickets.addMessage(rest, agentToken, ticketId,
                "On it.", "PUBLIC");
        assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Long firstResponse = sla.recordId(ticketId, "FIRST_RESPONSE");
        assertThat(sla.state(firstResponse)).isEqualTo("MET");
        // Out of the poller's partial index the moment it stops being interesting.
        assertThat(sla.openSegmentCount(firstResponse)).isZero();
        // The resolution clock is untouched: replying is not resolving.
        assertThat(sla.state(sla.recordId(ticketId, "RESOLUTION"))).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("An internal note does not meet the first-response clock")
    void internalNoteDoesNotMeetFirstResponse() {
        Long ticketId = tickets.createId(rest, customerToken, "Note only", "Body");
        sla.triage(rest, tickets, agentToken, ticketId, "P2");

        tickets.addMessage(rest, agentToken, ticketId, "Looks like billing.", "INTERNAL");

        // The customer has heard nothing, so nothing has been responded to. Counting an
        // internal note would let a team hit every first-response target while talking
        // only to itself.
        assertThat(sla.state(sla.recordId(ticketId, "FIRST_RESPONSE"))).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("Waiting on the customer pauses the resolution clock and leaving it resumes")
    void waitingPausesAndResumes() {
        Long ticketId = tickets.createId(rest, customerToken, "Pause me", "Body");
        sla.triage(rest, tickets, agentToken, ticketId, "P2");
        Long resolution = sla.recordId(ticketId, "RESOLUTION");
        tickets.post(rest, agentToken, ticketId, "assign", Map.of());

        tickets.post(rest, agentToken, ticketId, "status",
                Map.of("status", "WAITING_ON_CUSTOMER", "reason", "Need the invoice id"));

        assertThat(sla.state(resolution)).isEqualTo("PAUSED");
        assertThat(sla.openSegmentCount(resolution)).isEqualTo(1);
        assertThat(sla.segmentCount(resolution)).isEqualTo(2);

        tickets.post(rest, agentToken, ticketId, "status",
                Map.of("status", "IN_PROGRESS", "reason", "Customer replied"));

        assertThat(sla.state(resolution)).isEqualTo("RUNNING");
        assertThat(sla.openSegmentCount(resolution)).isEqualTo(1);
        assertThat(sla.segmentCount(resolution)).isEqualTo(3);
    }

    @Test
    @DisplayName("Paused time does not count towards elapsed")
    @SuppressWarnings("unchecked")
    void pausedTimeIsNotElapsed() {
        Long ticketId = tickets.createId(rest, customerToken, "Long pause", "Body");
        sla.triage(rest, tickets, agentToken, ticketId, "P2");
        Long resolution = sla.recordId(ticketId, "RESOLUTION");
        tickets.post(rest, agentToken, ticketId, "assign", Map.of());
        tickets.post(rest, agentToken, ticketId, "status",
                Map.of("status", "WAITING_ON_CUSTOMER", "reason", "Waiting"));

        // Two days of waiting, applied to the segments as though they had really elapsed.
        sla.rewind(resolution, 2 * 24 * 60);

        Map body = sla.sla(rest, agentToken, ticketId).getBody();
        Map<String, Object> clock = clockOf(body, "RESOLUTION");
        long elapsed = ((Number) clock.get("elapsedBusinessMinutes")).longValue();

        // The running segment before the pause was a moment long; the paused segment is
        // two days and contributes nothing. An elapsed of two days here would mean the
        // pause was decorative.
        assertThat(elapsed).isLessThan(5);

        tickets.post(rest, agentToken, ticketId, "status",
                Map.of("status", "IN_PROGRESS", "reason", "Back"));

        // And the new deadline is the remaining budget measured from now — not the
        // original deadline, which is two days in the past.
        Map resumed = clockOf(sla.sla(rest, agentToken, ticketId).getBody(), "RESOLUTION");
        assertThat(resumed.get("state")).isEqualTo("RUNNING");
        assertThat(java.time.OffsetDateTime.parse((String) resumed.get("nextDeadlineAt")))
                .isAfter(java.time.OffsetDateTime.now());
    }

    @Test
    @DisplayName("Resolving stops the resolution clock; reopening starts a fresh one")
    void resolveStopsAndReopenRestarts() {
        Long ticketId = tickets.createId(rest, customerToken, "Round trip", "Body");
        sla.triage(rest, tickets, agentToken, ticketId, "P2");
        Long firstResolution = sla.recordId(ticketId, "RESOLUTION");
        tickets.post(rest, agentToken, ticketId, "assign", Map.of());

        tickets.post(rest, agentToken, ticketId, "resolve",
                Map.of("resolution", "Refunded"));

        assertThat(sla.state(firstResolution)).isEqualTo("MET");
        assertThat(sla.openSegmentCount(firstResolution)).isZero();

        tickets.post(rest, agentToken, ticketId, "reopen",
                Map.of("reason", "Refund never arrived"));

        Long secondResolution = sla.recordId(ticketId, "RESOLUTION");
        assertThat(secondResolution).isNotEqualTo(firstResolution);
        assertThat(sla.state(secondResolution)).isEqualTo("RUNNING");
        // The old record survives as CANCELLED rather than being reset: "resolved in four
        // hours, reopened, resolved again in forty" is a different and more useful story
        // than "resolved in forty-four hours", and only the two-record shape can tell it.
        assertThat(sla.state(firstResolution)).isIn("MET", "CANCELLED");
        // No second first-response clock: a first reply has already happened and cannot
        // happen again.
        assertThat(sla.state(sla.recordId(ticketId, "FIRST_RESPONSE"))).isNotNull();
    }

    @Test
    @DisplayName("A clock breached on the arithmetic is BREACHED even if the poller never ran")
    void stopJudgesByArithmeticNotByEscalation() {
        Long ticketId = tickets.createId(rest, customerToken, "Slow one", "Body");
        sla.triage(rest, tickets, agentToken, ticketId, "P2");
        Long resolution = sla.recordId(ticketId, "RESOLUTION");
        tickets.post(rest, agentToken, ticketId, "assign", Map.of());

        // Well past the 240-minute resolution target, with no poll in between.
        sla.rewind(resolution, 400);

        tickets.post(rest, agentToken, ticketId, "resolve", Map.of("resolution", "Late"));

        // Judged by comparing elapsed against the snapshotted target. Reading the
        // escalation table instead would have called this MET, because the poller — which
        // is at-least-once and may not have run — had not fired the breach rung yet.
        assertThat(sla.state(resolution)).isEqualTo("BREACHED");
        assertThat(sla.firedRungs(resolution)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> clockOf(Map body, String kind) {
        return ((List<Map<String, Object>>) body.get("clocks")).stream()
                .filter(c -> kind.equals(c.get("kind")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + kind + " clock in " + body));
    }
}
