package com.resolveai.ticketing.domain;

import com.resolveai.iam.domain.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * One entry on a ticket's audit timeline. <b>Append-only.</b>
 *
 * <p>There are no setters at all, and that mirrors {@code trg_ticket_event_immutable}, which
 * raises an exception on any UPDATE or DELETE. Two enforcement points for one rule is not
 * redundancy: the trigger is the guarantee (it also covers {@code psql} and any future
 * service), and the missing setters move the failure from a runtime exception in production
 * to a compile error on the developer's machine.
 *
 * <p><b>{@code actorId} is nullable, and the null is meaningful.</b> {@code NULL} means the
 * system did it — the SLA poller firing an escalation, or a Phase 6 worker writing a triage
 * result. Recording those under some service account would make "who changed this?"
 * unanswerable for exactly the changes nobody remembers making.
 */
@Entity
@Table(name = "ticket_event")
public class TicketEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
    private Ticket ticket;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** Null when the actor was the system rather than a person. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", updatable = false)
    private AppUser actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40, updatable = false)
    private TicketEventType eventType;

    @Column(name = "from_value", length = 80, updatable = false)
    private String fromValue;

    @Column(name = "to_value", length = 80, updatable = false)
    private String toValue;

    /**
     * Free-form detail as JSONB.
     *
     * <p>Held as a {@code String} rather than a {@code Map}: the recorder serialises once at
     * the call site, and a typed map here would make every read of the timeline pay for a
     * deserialisation nothing in this phase looks at. {@code @JdbcTypeCode(JSON)} is what
     * makes the {@code varchar}-to-{@code jsonb} binding work — without it Postgres rejects
     * the parameter type outright.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", updatable = false)
    private String payload;

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    protected TicketEvent() {
        // JPA
    }

    public TicketEvent(Ticket ticket, AppUser actor, TicketEventType eventType,
                       String fromValue, String toValue, String payload) {
        this.ticket = ticket;
        this.actor = actor;
        this.eventType = eventType;
        this.fromValue = fromValue;
        this.toValue = toValue;
        this.payload = payload;
    }

    public Long getId() { return id; }
    public Ticket getTicket() { return ticket; }
    public Long getTenantId() { return tenantId; }
    public AppUser getActor() { return actor; }
    public TicketEventType getEventType() { return eventType; }
    public String getFromValue() { return fromValue; }
    public String getToValue() { return toValue; }
    public String getPayload() { return payload; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
