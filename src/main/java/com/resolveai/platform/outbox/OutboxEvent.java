package com.resolveai.platform.outbox;

import java.time.OffsetDateTime;

/**
 * One row of the outbox, as the workers see it.
 *
 * <p><b>A record, not a JPA entity, and that is the whole point of Task 1.</b> The claim
 * path is a queue operation: one statement that finds rows, locks them, mutates them and
 * returns them. Hibernate fights every part of that. Its first-level cache would hand a
 * worker a stale copy of a row another worker has since claimed; its dirty checking would
 * issue UPDATEs nobody asked for at flush time; and {@code @Version} — the obvious
 * instinct — would turn a lock-free {@code SKIP LOCKED} queue into a contended one.
 *
 * <p>So the claim path is {@code JdbcTemplate} and a {@code RowMapper}, and what comes
 * back is an immutable snapshot. There is nothing to accidentally mutate and nothing to
 * flush. The writing side ({@code OutboxPublisher}) is a plain INSERT for the same
 * reason: the payload is JSONB, the row is never updated by its producer, and an entity
 * would buy nothing.
 *
 * @param payload the event body as raw JSON text. Deliberately not deserialised here —
 *                the runtime does not know or care what shape a given event type has, and
 *                a generic queue that parses payloads is a generic queue that fails on a
 *                payload written by a newer version of the code.
 */
public record OutboxEvent(
        Long id,
        Long tenantId,
        String aggregateType,
        Long aggregateId,
        EventType eventType,
        String payload,
        OutboxStatus status,
        short attempts,
        OffsetDateTime nextAttemptAt,
        OffsetDateTime lockedUntil,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime processedAt) {

    /** How many times this event has been tried, for a worker that wants to log it. */
    public boolean isFirstAttempt() {
        return attempts <= 1;
    }
}
