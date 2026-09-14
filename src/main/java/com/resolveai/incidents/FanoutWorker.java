package com.resolveai.incidents;

import com.resolveai.incidents.domain.IncidentUpdate;
import com.resolveai.incidents.domain.UpdateVisibility;
import com.resolveai.incidents.repository.IncidentUpdateDeliveryRepository;
import com.resolveai.incidents.repository.IncidentUpdateRepository;
import com.resolveai.platform.outbox.EventType;
import com.resolveai.platform.outbox.NonRetryableException;
import com.resolveai.platform.outbox.OutboxEvent;
import com.resolveai.platform.outbox.Worker;
import com.resolveai.platform.time.DatabaseClock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Delivers one {@link IncidentUpdate} to one ticket. Doc 12 Task 19.
 *
 * <h2>The conditional update {@code IS} the idempotency mechanism</h2>
 *
 * <p>{@code deliveries.markSent} is {@code UPDATE ... WHERE status = 'PENDING'}, backed by
 * {@code uq_delivery (incident_update_id, ticket_id)}. A redelivered outbox event finds the
 * row already {@code SENT}, updates zero rows, and this method returns having done
 * nothing. <b>That is the entire idempotency guarantee</b> — no additional check, no
 * dedup table, because the delivery row already carries the state a check would have to
 * ask about.
 *
 * <p>Notifications are broadcast, not a personal reply: appending the public body to the
 * ticket thread here does not run through {@code TicketService.addMessage} and does not
 * mark first response — a team-wide status update is not the agent's own reply, and
 * treating it as one would let an incident satisfy a first-response SLA nobody actually
 * answered.
 */
@Component
public class FanoutWorker implements Worker {

    private static final Logger log = LoggerFactory.getLogger(FanoutWorker.class);

    private final TransactionTemplate txTemplate;
    private final IncidentUpdateRepository updates;
    private final IncidentUpdateDeliveryRepository deliveries;
    private final JdbcTemplate jdbc;
    private final DatabaseClock clock;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;

    public FanoutWorker(TransactionTemplate txTemplate, IncidentUpdateRepository updates,
                        IncidentUpdateDeliveryRepository deliveries, JdbcTemplate jdbc,
                        DatabaseClock clock, ObjectMapper objectMapper,
                        @Value("${resolveai.workers.max-attempts:5}") int maxAttempts) {
        this.txTemplate = txTemplate;
        this.updates = updates;
        this.deliveries = deliveries;
        this.jdbc = jdbc;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public Set<EventType> handles() {
        return Set.of(EventType.INCIDENT_UPDATE_PUBLISHED);
    }

    /** Fan-out is the one workload where batching helps: the work per event is small and local. */
    @Override
    public int batchSize() {
        return 50;
    }

    @Override
    public void process(OutboxEvent event) {
        Payload payload = readPayload(event);
        try {
            txTemplate.executeWithoutResult(status -> deliverOne(event.tenantId(), payload));
        } catch (RuntimeException e) {
            txTemplate.executeWithoutResult(status -> {
                deliveries.recordFailure(payload.deliveryId(), messageOf(e));
                if (event.attempts() >= maxAttempts) {
                    deliveries.markFailed(payload.deliveryId());
                }
            });
            throw e;
        }
    }

    private void deliverOne(Long tenantId, Payload payload) {
        int claimed = deliveries.markSent(payload.deliveryId(), clock.now());
        if (claimed == 0) {
            log.debug("Delivery {} already sent; redelivery is a no-op", payload.deliveryId());
            return;
        }

        IncidentUpdate update = updates.findById(payload.incidentUpdateId())
                .orElseThrow(() -> new NonRetryableException(
                        "Incident update " + payload.incidentUpdateId() + " no longer exists"));

        // queryForObject throws rather than returning null for zero rows, so the
        // missing-ticket case is read as a list first - the same "no longer exists is
        // not worth retrying" outcome TriageWorker reaches for a vanished ticket.
        List<Long> found = jdbc.queryForList(
                "SELECT requester_id FROM ticket WHERE id = ?", Long.class, payload.ticketId());
        if (found.isEmpty()) {
            throw new NonRetryableException("Ticket " + payload.ticketId() + " no longer exists");
        }
        Long recipientId = found.get(0);

        jdbc.update("""
                INSERT INTO notification (tenant_id, recipient_id, kind, title, body, link_url)
                VALUES (?, ?, 'INCIDENT_UPDATE', ?, ?, ?)
                """, tenantId, recipientId, "Incident update",
                truncate(update.getBody(), 1000), "/tickets/" + payload.ticketId());

        if (update.getVisibility() == UpdateVisibility.PUBLIC) {
            jdbc.update("""
                    INSERT INTO ticket_message (ticket_id, tenant_id, author_id, body,
                                                visibility, is_first_response)
                    VALUES (?, ?, ?, ?, 'PUBLIC', FALSE)
                    """, payload.ticketId(), tenantId, update.getAuthorId(), update.getBody());
        }
    }

    private Payload readPayload(OutboxEvent event) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(event.payload(), Map.class);
            Long updateId = numberOf(body.get("incidentUpdateId"));
            Long ticketId = numberOf(body.get("ticketId"));
            Long deliveryId = numberOf(body.get("deliveryId"));
            if (updateId == null || ticketId == null || deliveryId == null) {
                throw new NonRetryableException(
                        "Incomplete fan-out payload on outbox event " + event.id());
            }
            return new Payload(updateId, ticketId, deliveryId);
        } catch (RuntimeException e) {
            if (e instanceof NonRetryableException) {
                throw e;
            }
            throw new NonRetryableException(
                    "Unreadable payload on outbox event " + event.id(), e);
        }
    }

    private static Long numberOf(Object value) {
        return value == null ? null : Long.valueOf(String.valueOf(value));
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String messageOf(RuntimeException e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName()
                : message.length() > 500 ? message.substring(0, 500) : message;
    }

    /** {@code visibilityTimeout} is short: each delivery is one insert or two, well under it. */
    @Override
    public Duration visibilityTimeout() {
        return Duration.ofMinutes(1);
    }

    private record Payload(Long incidentUpdateId, Long ticketId, Long deliveryId) {
    }
}
