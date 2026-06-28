package com.resolveai.platform;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled}.
 *
 * <h2>Not on in the {@code test} profile</h2>
 *
 * <p>A background poller running during an integration test is a source of races that
 * belong to the test harness rather than to the system: a test that seeds a due SLA record
 * and then asserts nothing has fired yet is competing with a thread that wakes every ten
 * seconds. Worse, it makes the failure intermittent and ordering-dependent.
 *
 * <p>The tests drive {@code SlaDeadlinePoller.pollOnce()} directly instead, which is both
 * deterministic and a stronger test — it asserts what one pass does, rather than what
 * happened to have run by the time the assertion executed.
 *
 * <p>The cost is that the {@code @Scheduled} annotation itself is not exercised by the
 * suite. That is a real gap and it is the right trade: the annotation is one line of
 * configuration, and the thing worth testing is the claim-and-process logic behind it.
 *
 * <h2>Pool size</h2>
 *
 * <p>{@code spring.task.scheduling.pool.size} is set above 1 in {@code application.yml}.
 * With the default of one thread, the SLA poller and every scheduler Phase 6 adds - the
 * outbox dispatcher, the IN_FLIGHT reaper, the correlation sweep - would run on the same
 * thread and block each other. A slow poll would stall the outbox, and the symptom would
 * be "async work is late" with nothing pointing at the scheduler.
 */
@Configuration
@EnableScheduling
@Profile("!test")
public class SchedulingConfig {
}
