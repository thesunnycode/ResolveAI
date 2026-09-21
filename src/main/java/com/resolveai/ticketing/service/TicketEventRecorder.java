package com.resolveai.ticketing.service;

import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.ticketing.domain.Ticket;
import com.resolveai.ticketing.domain.TicketEvent;
import com.resolveai.ticketing.domain.TicketEventType;
import com.resolveai.ticketing.repository.TicketEventRepository;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the ticket timeline.
 *
 * <h2>{@code MANDATORY}, and that is the whole design</h2>
 *
 * <p>An audit entry has to share the fate of the change it describes. If the business write
 * rolls back and the audit row does not, the timeline claims something happened that did
 * not — and the timeline is the artefact people reach for precisely when they no longer
 * trust their memory of what happened.
 *
 * <p>{@code Propagation.MANDATORY} is stronger than {@code REQUIRED} here on purpose.
 * {@code REQUIRED} would silently open its own transaction when called from outside one, and
 * the orphaned-audit-row bug would then exist in production while every test passed.
 * {@code MANDATORY} throws {@code IllegalTransactionStateException} at the first such call,
 * which is a development-time failure rather than a data-integrity one.
 *
 * <h2>A null actor is information, not a gap</h2>
 *
 * <p>{@code actor_id IS NULL} means the system did it: the SLA poller firing an escalation,
 * or a Phase 6 worker writing a triage result. Attributing those to a service account would
 * make "who changed this?" unanswerable for exactly the changes nobody remembers making.
 */
@Component
public class TicketEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(TicketEventRecorder.class);

    private final TicketEventRepository events;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public TicketEventRecorder(TicketEventRepository events, EntityManager entityManager,
                               ObjectMapper objectMapper) {
        this.events = events;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public TicketEvent record(Ticket ticket, TicketEventType type, String from, String to) {
        return record(ticket, type, from, to, null);
    }

    /**
     * @param payload free-form detail, serialised to JSONB. {@code null} or empty writes SQL
     *                {@code NULL} rather than {@code "{}"} — an empty object in the column
     *                reads as "there was detail and it was empty", which is a different
     *                claim from "there was no detail".
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TicketEvent record(Ticket ticket, TicketEventType type, String from, String to,
                              Map<String, Object> payload) {
        return events.save(new TicketEvent(ticket, currentActor(), type,
                truncate(from), truncate(to), serialise(payload)));
    }

    /** Convenience for the common case of a status move. */
    @Transactional(propagation = Propagation.MANDATORY)
    public TicketEvent recordStatusChange(Ticket ticket, String from, String to, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (reason != null && !reason.isBlank()) {
            payload.put("reason", reason);
        }
        return record(ticket, TicketEventType.STATUS_CHANGED, from, to, payload);
    }

    /**
     * The authenticated user, or {@code null}.
     *
     * <p>{@code getReference} rather than a {@code findById}: this produces a lazy proxy from
     * the id the token already carries, so recording an event costs no extra SELECT. The row
     * is guaranteed to exist — the token was minted from it — and the foreign key would catch
     * it if it somehow did not.
     */
    private AppUser currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof ResolvePrincipal principal)) {
            return null;
        }
        return entityManager.getReference(AppUser.class, principal.userId());
    }

    private String serialise(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            // An unserialisable payload must not take the business transaction down with it.
            // Losing a detail field is survivable; losing the status change it describes is
            // not, and neither is a 500 on a ticket update because a map held an odd value.
            log.warn("Could not serialise ticket event payload; recording without it", e);
            return null;
        }
    }

    /** {@code from_value} and {@code to_value} are {@code VARCHAR(80)}. */
    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 80 ? value : value.substring(0, 80);
    }
}
