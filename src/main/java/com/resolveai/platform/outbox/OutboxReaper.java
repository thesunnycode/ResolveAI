package com.resolveai.platform.outbox;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Returns events whose worker died to the queue.
 *
 * <h2>What actually goes wrong without this</h2>
 *
 * <p>A claim sets {@code status = 'IN_FLIGHT'} and a visibility timeout. If the process
 * finishes, the row moves to {@code DONE} or back to {@code PENDING} with a backoff. If
 * the process <b>stops existing</b> — an OOM kill, a {@code SIGKILL} from an impatient
 * deploy, a node drained by the scheduler — nothing moves it, and the row stays
 * {@code IN_FLIGHT} for ever. It is invisible to the claim query, so no worker ever takes
 * it again, and it never appears in the DLQ, so nobody is told. The ticket is simply
 * never triaged.
 *
 * <p>This is the same failure the SLA poller's absolute deadlines avoid, in a different
 * costume: <b>state that lives only in a running process disappears with it.</b> A
 * visibility timeout in a column plus a sweep makes the recovery automatic and puts a
 * bound on how long it takes.
 *
 * <h2>Why each reap is logged at WARN</h2>
 *
 * <p>An occasional reap is a crash, which is expected and handled. A <i>steady trickle</i>
 * of reaps is something else entirely: a worker whose processing genuinely takes longer
 * than its visibility timeout. Nothing is crashing, nothing errors, and every event is
 * being processed twice — once by the worker still working on it and once by whoever
 * claims the redelivered copy. At INFO it disappears into the noise; at WARN, with the
 * type named, the pattern is visible and the fix (raise that worker's timeout) is
 * obvious.
 */
@Component
public class OutboxReaper {

    private static final Logger log = LoggerFactory.getLogger(OutboxReaper.class);

    private final OutboxRepository outbox;

    public OutboxReaper(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    /**
     * Every minute. Faster would add load for no benefit — the timeout itself already
     * sets how long recovery takes, and a sweep interval well below it only means
     * repeatedly scanning an index to find nothing.
     */
    @Scheduled(fixedDelayString = "${resolveai.workers.reaper-interval-ms:60000}")
    public void reap() {
        try {
            reapOnce();
        } catch (RuntimeException e) {
            log.error("Outbox reap failed; the next run will retry", e);
        }
    }

    /** One sweep. Returns the ids recovered, so tests need not wait for the schedule. */
    public List<Long> reapOnce() {
        List<Long> reaped = outbox.reapExpired();
        for (Long id : reaped) {
            log.warn("Reaped outbox event {} — its worker never finished. A steady trickle "
                     + "of these means a visibility timeout is too short, not that workers "
                     + "are crashing.", id);
        }
        return reaped;
    }
}
