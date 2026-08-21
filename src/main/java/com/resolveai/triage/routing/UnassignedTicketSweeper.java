package com.resolveai.triage.routing;

import com.resolveai.iam.repository.AppUserRepository;
import com.resolveai.platform.tenant.TenantContext;
import com.resolveai.platform.time.DatabaseClock;
import com.resolveai.ticketing.domain.Ticket;
import com.resolveai.ticketing.domain.TicketEventType;
import com.resolveai.ticketing.domain.TicketStatus;
import com.resolveai.ticketing.repository.TicketRepository;
import com.resolveai.ticketing.service.TicketEventRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retries auto-routing for tickets that triage could not place.
 *
 * <h2>Why a ticket ends up unassigned</h2>
 *
 * <p>{@link AgentAssigner#claim} runs once, at the end of triage. If every agent in the
 * team is off shift, unavailable or at capacity at that moment, the ticket waits in the
 * team queue - correctly. But nothing ever asked again: a ticket triaged at 02:00 was
 * still unassigned at 10:00 with the whole team on shift, until a lead noticed and
 * assigned it by hand.
 *
 * <p>This sweeper asks again, every minute, in the order a lead would: highest priority
 * first, then oldest. It uses the same claim, so capacity, shifts and availability are
 * respected exactly as they are at triage.
 *
 * <h2>Losing a race to a human is fine</h2>
 *
 * <p>The ticket is written through JPA with its version column. If an agent or lead
 * assigns it between our read and our write, the save fails on the version check and the
 * whole transaction - including the open_count increment the claim made - rolls back.
 * The human's assignment stands, and nothing is double counted.
 */
@Component
public class UnassignedTicketSweeper {

    private static final Logger log = LoggerFactory.getLogger(UnassignedTicketSweeper.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;
    private final TicketRepository tickets;
    private final AppUserRepository users;
    private final AgentAssigner assigner;
    private final TicketEventRecorder eventRecorder;
    private final DatabaseClock clock;
    private final MeterRegistry metrics;
    private final int batchSize;

    public UnassignedTicketSweeper(JdbcTemplate jdbc, TransactionTemplate txTemplate,
                                   TicketRepository tickets, AppUserRepository users,
                                   AgentAssigner assigner, TicketEventRecorder eventRecorder,
                                   DatabaseClock clock, MeterRegistry metrics,
                                   @Value("${resolveai.assignment.retry-batch-size:50}")
                                   int batchSize) {
        this.jdbc = jdbc;
        this.txTemplate = txTemplate;
        this.tickets = tickets;
        this.users = users;
        this.assigner = assigner;
        this.eventRecorder = eventRecorder;
        this.clock = clock;
        this.metrics = metrics;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${resolveai.assignment.retry-interval-ms:60000}")
    public void sweep() {
        try {
            int assigned = sweepOnce();
            if (assigned > 0) {
                log.info("Auto-routing retry assigned {} waiting ticket(s)", assigned);
            }
        } catch (RuntimeException e) {
            log.error("Auto-routing retry sweep failed; the next sweep will retry", e);
        }
    }

    /**
     * One pass. Returns how many tickets were assigned.
     *
     * <p>Public so tests can drive it directly rather than waiting for the schedule.
     */
    public int sweepOnce() {
        // Cross-tenant and native for the same reason as SlaFallbackSweeper: a scheduled
        // job has no tenant set, so the tenant comes off each row. UNTRIAGED is excluded
        // because triage assigns in the same transaction that sets the priority - a
        // ticket still UNTRIAGED is triage's to route, not ours.
        List<Waiting> waiting = jdbc.query("""
                SELECT t.id, t.tenant_id, t.team_id
                  FROM ticket t
                 WHERE t.assignee_id IS NULL
                   AND t.team_id IS NOT NULL
                   AND t.priority <> 'UNTRIAGED'
                   AND t.status IN ('OPEN', 'TRIAGED')
                 ORDER BY t.priority, t.created_at
                 LIMIT ?
                """,
                (rs, rowNum) -> new Waiting(rs.getLong("id"), rs.getLong("tenant_id"),
                        rs.getLong("team_id")),
                batchSize);

        // Once a team has nobody free, the rest of its tickets will not find anyone this
        // pass either. Skipping them saves a locking query per ticket on a busy day.
        Set<TeamKey> exhausted = new HashSet<>();
        int assigned = 0;
        for (Waiting row : waiting) {
            TeamKey team = new TeamKey(row.tenantId(), row.teamId());
            if (exhausted.contains(team)) {
                continue;
            }
            Outcome outcome;
            try {
                outcome = TenantContext.callAs(row.tenantId(),
                        () -> txTemplate.execute(status -> tryAssign(row)));
            } catch (ObjectOptimisticLockingFailureException e) {
                log.debug("Ticket {} changed while retrying its routing; leaving it", row.ticketId());
                continue;
            }
            if (outcome == Outcome.NO_AGENT) {
                exhausted.add(team);
            } else if (outcome == Outcome.ASSIGNED) {
                assigned++;
                metrics.counter("assignment.retry.assigned").increment();
            }
        }
        return assigned;
    }

    private Outcome tryAssign(Waiting row) {
        Ticket ticket = tickets.findById(row.ticketId()).orElse(null);
        // Re-checked under the transaction: the list was read a moment ago, outside it.
        if (ticket == null || ticket.getAssignee() != null || ticket.getTeam() == null
                || (ticket.getStatus() != TicketStatus.OPEN
                    && ticket.getStatus() != TicketStatus.TRIAGED)) {
            return Outcome.SKIPPED;
        }

        Optional<Long> agent = assigner.claim(row.tenantId(), ticket.getTeam().getId(), clock.now());
        if (agent.isEmpty()) {
            return Outcome.NO_AGENT;
        }
        Long userId = agent.get();
        ticket.setAssignee(users.findById(userId).orElseThrow());
        // One ASSIGNED event and the status on the same UPDATE, as triage and the manual
        // assign both do.
        eventRecorder.record(ticket, TicketEventType.ASSIGNED, null, String.valueOf(userId),
                Map.of("source", "AUTO_ROUTING_RETRY"));
        ticket.moveTo(TicketStatus.ASSIGNED, clock.now());
        tickets.saveAndFlush(ticket);
        return Outcome.ASSIGNED;
    }

    private enum Outcome { ASSIGNED, NO_AGENT, SKIPPED }

    private record Waiting(Long ticketId, Long tenantId, Long teamId) {
    }

    private record TeamKey(Long tenantId, Long teamId) {
    }
}
