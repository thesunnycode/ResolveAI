package com.resolveai.triage.routing;

import com.resolveai.iam.repository.AgentProfileRepository;
import com.resolveai.sla.service.CalendarService;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Picks an agent for a routed ticket and books the capacity in the same breath.
 *
 * <h2>Claim and increment are one transaction, or the counter is a lie</h2>
 *
 * <p>The row is selected {@code FOR UPDATE SKIP LOCKED} and {@code open_count} is
 * incremented before the transaction ends, so the lock spans the decision and its
 * consequence. Split them — claim here, increment when the ticket is saved a moment later
 * — and two routers can both see an agent at 14 of 15 and both assign, because the lock
 * was released between the read and the write. The agent ends up over capacity and every
 * request reported success.
 *
 * <p>{@code MANDATORY} enforces it: this cannot be called outside a transaction, and a
 * caller that forgets gets a startup-time-quality error rather than a silent capacity
 * leak under load.
 *
 * <h2>Not assigning is a normal outcome</h2>
 *
 * <p>Everyone in the team busy, off shift, or unavailable means the ticket sits in the
 * team queue with no assignee, which is what a team queue is for. Throwing here would
 * dead-letter the triage event and lose the analysis and the priority along with it —
 * punishing the ticket for the roster.
 */
@Service
public class AgentAssigner {

    private static final Logger log = LoggerFactory.getLogger(AgentAssigner.class);

    /** Retries when free agents are only locked, not busy: 2+4+8+16+32 ms at most. */
    private static final int LOCKED_RETRIES = 5;

    private final AgentProfileRepository agents;
    private final CalendarService calendars;

    public AgentAssigner(AgentProfileRepository agents, CalendarService calendars) {
        this.agents = agents;
        this.calendars = calendars;
    }

    /**
     * Claims the least loaded eligible agent in {@code teamId} and books one slot.
     *
     * @param now the current instant, from {@link com.resolveai.platform.time.DatabaseClock}
     * @return the claimed agent's user id, or empty when nobody is eligible
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> claim(Long tenantId, Long teamId, OffsetDateTime now) {
        if (teamId == null) {
            return Optional.empty();
        }

        // The shift window is wall-clock time in the tenant's own zone. Comparing against
        // the server's UTC clock would shift every window by the tenant's offset, which
        // for an India-based product is five and a half hours - enough that the morning
        // shift looks like it is asleep and the evening one never ends.
        LocalTime localTime = now.atZoneSameInstant(calendars.current().zone()).toLocalTime();

        String time = localTime.toString();
        Optional<Long> claimed = agents.claimLeastLoadedAgent(tenantId, teamId, time);

        // SKIP LOCKED returns nothing both when nobody is free and when everybody free is
        // locked by a sibling router that is about to commit. Under a burst the second is
        // common - five routers, five agents, and the fifth router sees four locked rows
        // and the fifth agent already taken. Treating that as "nobody free" left tickets
        // unassigned with agents sitting at half capacity. The locks are held for a few
        // milliseconds, so a short bounded retry resolves it; a genuine shortage fails the
        // unlocked check straight away and costs one extra query.
        for (int attempt = 0; claimed.isEmpty() && attempt < LOCKED_RETRIES
                && agents.anyEligibleAgent(tenantId, teamId, time); attempt++) {
            pause(2L << attempt);
            claimed = agents.claimLeastLoadedAgent(tenantId, teamId, time);
        }
        if (claimed.isEmpty()) {
            log.debug("No eligible agent in team {} at {}; leaving the ticket in the "
                      + "team queue", teamId, localTime);
            return Optional.empty();
        }

        Long userId = claimed.get();
        // SET open_count = open_count + 1 as one statement, under the row lock the claim
        // already holds. A read-modify-write here would be a lost update on the most
        // contended column in the schema.
        agents.incrementOpenCount(userId, tenantId);
        return Optional.of(userId);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
