package com.resolveai.platform.outbox;

import com.resolveai.platform.tenant.TenantContext;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes an event in the same transaction as the change that caused it.
 *
 * <h2>The dual-write problem, which this exists to not have</h2>
 *
 * <p>The naive version of "create a ticket and trigger triage" is: save the ticket, then
 * publish to a broker. Two systems, no shared transaction, and therefore two failure
 * modes that are both silent. If the publish fails after the commit, the ticket exists
 * and is never triaged. If the publish succeeds and the transaction then rolls back, a
 * consumer is handed the id of a ticket that does not exist. Neither is rare under load,
 * and neither produces an error anybody sees.
 *
 * <p>An outbox row is an ordinary INSERT into the same database in the same transaction.
 * The ticket and the intent to triage it commit together or not at all. A poller then
 * moves the intent outward, at-least-once, which is a problem with a known answer
 * (idempotent consumers) rather than a problem with no answer.
 *
 * <h2>{@code MANDATORY} is the enforcement, and it is the whole design in one word</h2>
 *
 * <p>{@code REQUIRED} would quietly open a transaction of its own when a caller forgot
 * one — reintroducing the exact dual write this class exists to prevent, at the one call
 * site that got it wrong, with no symptom until a rollback happens in production.
 * {@code MANDATORY} throws {@code IllegalTransactionStateException} the first time the
 * code is run. The failure is loud, immediate, and lands on the developer who caused it.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Publishes an event. <b>Must be called inside an existing transaction.</b>
     *
     * @param payload serialised to JSONB. Keep it small and keep it <i>identifying</i>
     *                rather than <i>descriptive</i>: ids and a version, not a copy of the
     *                ticket body. A payload that duplicates the row is a payload that is
     *                stale by the time it is processed, and a worker that trusts it is a
     *                worker that acts on the old subject line.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long publish(String aggregateType, Long aggregateId, EventType eventType,
                        Object payload) {
        String json = serialise(payload);
        // The tenant is nullable in the schema and optional here: a system event with no
        // tenant is legitimate, and the worker sets the context from this column before
        // touching anything tenant-scoped. Reading it from the context at publish time
        // rather than taking it as an argument means no call site can pass the wrong one.
        Long tenantId = TenantContext.isSet() ? TenantContext.getRequired() : null;

        Long id = jdbc.queryForObject("""
                INSERT INTO outbox_event (tenant_id, aggregate_type, aggregate_id, event_type,
                                          payload, status, next_attempt_at)
                VALUES (?, ?, ?, ?, ?::jsonb, 'PENDING', NOW())
                RETURNING id
                """, Long.class, tenantId, aggregateType, aggregateId, eventType.name(), json);

        log.debug("Outbox {} queued: {} {} #{}", id, eventType, aggregateType, aggregateId);
        return id;
    }

    private String serialise(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? java.util.Map.of() : payload);
        } catch (JacksonException e) {
            // Not retryable and not recoverable: the payload cannot be written, so the
            // transaction that produced it must not commit either. Failing here keeps the
            // aggregate and its event consistent - which is the one guarantee this class
            // sells.
            throw new IllegalArgumentException(
                    "Outbox payload is not serialisable: " + payload.getClass().getName(), e);
        }
    }
}
