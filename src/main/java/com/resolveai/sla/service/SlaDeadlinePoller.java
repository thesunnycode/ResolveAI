package com.resolveai.sla.service;

import com.resolveai.platform.tenant.TenantContext;
import com.resolveai.sla.domain.SlaRecord;
import com.resolveai.sla.domain.SlaState;
import com.resolveai.sla.repository.SlaRecordRepository;
import com.resolveai.platform.time.DatabaseClock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wakes every ten seconds, claims the clocks whose deadline has passed, and fires their
 * rungs.
 *
 * <h2>Why polling an indexed column, and not a scheduler</h2>
 *
 * <p>The obvious design is a {@code ScheduledExecutorService} task per deadline. It is
 * simpler, it has no latency, and it <b>silently loses every pending escalation on
 * restart</b> — including a deploy. Nobody notices until a customer escalates about a
 * breach that was never flagged, weeks later, and the evidence is gone.
 *
 * <p>An absolute deadline in an indexed column survives anything. The application can be
 * down for two hours and the first poll after it comes back finds every missed deadline
 * and fires the correct rung. {@code SlaPollerTest} has that as an explicit test, because
 * it is the property the whole design was chosen for.
 *
 * <h2>The two-transaction structure</h2>
 *
 * <pre>{@code
 * // Transaction 1 — short. Ids only, FOR UPDATE SKIP LOCKED.
 * List<Long> due = records.claimDueIds(batchSize);
 *
 * // Transactions 2..N — one per record, independent.
 * for (Long id : due) { txTemplate.execute(...); }
 * }</pre>
 *
 * <p><b>Why not process the whole batch inside the claiming transaction:</b> those
 * {@code FOR UPDATE} locks would be held for the duration. An agent replying to any of
 * those two hundred tickets would block behind a background job. Claiming ids in
 * milliseconds and re-locking one record at a time keeps every lock short enough not to
 * matter.
 *
 * <p><b>Why it is safe for two runs to pick the same id:</b> because the effect is
 * idempotent. {@code uq_escalation_rung} means firing a rung twice inserts one row and
 * sends one notification. The cheap claim strategy is viable <i>because</i> that
 * constraint exists — a database constraint buying a simpler application design.
 *
 * <h2>{@code TenantContext.runAs} is mandatory here</h2>
 *
 * <p>The poller is a system job. There is no request, so there is no tenant, and
 * {@code @TenantId} with an unset context is exactly the hole Phase 4 tested for — every
 * read inside would run under the no-tenant sentinel and find nothing, while every write
 * would land under it. The tenant comes from the record.
 */
@Component
public class SlaDeadlinePoller {

    private static final Logger log = LoggerFactory.getLogger(SlaDeadlinePoller.class);

    private final SlaRecordRepository records;
    private final EscalationService escalations;
    private final TransactionTemplate txTemplate;
    private final int batchSize;
    private final DatabaseClock clock;

    public SlaDeadlinePoller(SlaRecordRepository records, EscalationService escalations,
                             TransactionTemplate txTemplate,
                             @Value("${resolveai.workers.sla-poller-batch-size:200}")
                             int batchSize, DatabaseClock clock) {
        this.records = records;
        this.escalations = escalations;
        this.txTemplate = txTemplate;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    /**
     * {@code fixedDelay}, not {@code fixedRate}.
     *
     * <p>{@code fixedRate} schedules the next run from the start of the previous one, so a
     * poll that takes longer than the interval queues the next one immediately and, under
     * sustained load, keeps queueing. {@code fixedDelay} measures from the end, so the
     * poller degrades by running less often rather than by piling up.
     *
     * <p>Ten seconds is the resolution of the escalation ladder. It could be a minute — an
     * SLA rung is not a real-time event — and ten keeps the demo legible.
     */
    @Scheduled(fixedDelayString = "${resolveai.workers.sla-poll-interval-ms:10000}")
    public void poll() {
        try {
            int fired = pollOnce();
            if (fired > 0) {
                log.info("SLA poll fired {} rung(s)", fired);
            }
        } catch (RuntimeException e) {
            // A scheduled method that throws is silently never run again by some
            // schedulers and logged at DEBUG by others. Catching here means one bad batch
            // cannot stop the escalation ladder for the life of the process.
            log.error("SLA poll failed; the next run will retry the same records", e);
        }
    }

    /**
     * One pass. Returns the number of rungs fired, so tests can drive the poller directly
     * rather than waiting for the schedule — a test that sleeps for a poll interval is a
     * test that is slow and flaky at the same time.
     */
    public int pollOnce() {
        OffsetDateTime claimAt = clock.now();
        List<Object[]> due = txTemplate.execute(status -> records.claimDue(claimAt, batchSize));
        if (due == null || due.isEmpty()) {
            return 0;
        }

        int fired = 0;
        for (Object[] row : due) {
            Long id = ((Number) row[0]).longValue();
            Long tenantId = ((Number) row[1]).longValue();
            if (processOne(id, tenantId) == EscalationService.Outcome.FIRED) {
                fired++;
            }
        }
        return fired;
    }

    /**
     * One record, in its own transaction, under its own tenant.
     *
     * <p>The re-read takes the row lock again. That lock is what serialises this against
     * an agent replying to the same ticket at the same instant: whichever transaction goes
     * second sees the other's committed work, so a reply and a breach cannot both happen.
     * {@code SlaRaceTest} is the test that says so, and removing the {@code FOR UPDATE}
     * from this read is how you check the test is real.
     */
    private EscalationService.Outcome processOne(Long id, Long tenantId) {
        try {
            // Outside the transaction, because Hibernate reads the tenant when it opens
            // the session. Set inside, it would be too late for every lazy association
            // loaded during the work - they would resolve under the no-tenant sentinel
            // and fail on rows that exist.
            return TenantContext.callAs(tenantId, () -> txTemplate.execute(status -> {
                SlaRecord record = records.findByIdForUpdate(id).orElse(null);
                if (record == null) {
                    return EscalationService.Outcome.NOT_DUE;
                }
                OffsetDateTime now = clock.now();

                // Re-checked under the lock, because the claim was made in a transaction
                // that has since committed. In that window an agent may have replied,
                // paused the clock, or resolved the ticket - all of which make this
                // record no longer due, and none of which the claim could have known.
                if (record.getState() != SlaState.RUNNING
                        || record.getNextDeadlineAt() == null
                        || record.getNextDeadlineAt().isAfter(now)) {
                    return EscalationService.Outcome.NOT_DUE;
                }

                try {
                    return escalations.processRung(record, now);
                } finally {
                    // The memo is keyed by tenant, but the poller walks many tenants on
                    // one pooled thread and a calendar edited mid-run must not be served
                    // from a stale entry for the rest of the process's life.
                    CalendarService.clearCache();
                }
            }));
        } catch (RuntimeException e) {
            // One poisoned record must not abandon the rest of the batch. It stays due,
            // so the next poll tries it again - and if it keeps failing, the log says so
            // every ten seconds, which is the right amount of noise for a stuck clock.
            log.error("Failed to process SLA record {}", id, e);
            return EscalationService.Outcome.NOT_DUE;
        }
    }
}
