package com.resolveai.incidents.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One ticket's link to one incident, with its own identity.
 *
 * <p><b>Mapped as a full entity, never as a {@code @ManyToMany}.</b> The link carries data
 * of its own — {@code linkConfidence}, who linked it, whether and when it was detached —
 * and a {@code @ManyToMany} join table has nowhere to put any of that.
 *
 * <p>Detach is soft: {@code detachedAt} is set rather than the row deleted. A wrong link is
 * useful history, and it is the evidence behind the precision number in the README's tuning
 * table. {@code uq_incident_ticket_live} — a partial unique index on {@code ticket_id WHERE
 * detached_at IS NULL} — is what makes "a ticket belongs to at most one live incident" a
 * database guarantee rather than an application check that a race could slip past.
 *
 * <p>No {@code tenantId} column: this table has none in {@code V5__incidents.sql} (its
 * tenancy is inherited through {@code incident_id}/{@code ticket_id}, both of which are
 * themselves tenant-scoped), so there is nothing for {@code @TenantId} to discriminate on
 * here.
 */
@Entity
@Table(name = "incident_ticket")
public class IncidentTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    /** Null for a manual link — a human did not compute a cosine similarity. */
    @Column(name = "link_confidence", precision = 4, scale = 3)
    private BigDecimal linkConfidence;

    /** Null means automatic, i.e. the correlation sweep linked it. */
    @Column(name = "linked_by")
    private Long linkedBy;

    @CreationTimestamp
    @Column(name = "linked_at", nullable = false)
    private OffsetDateTime linkedAt;

    @Column(name = "detached_at")
    private OffsetDateTime detachedAt;

    @Column(name = "detached_by")
    private Long detachedBy;

    protected IncidentTicket() {
        // JPA
    }

    public IncidentTicket(Long incidentId, Long ticketId, BigDecimal linkConfidence,
                          Long linkedBy) {
        this.incidentId = incidentId;
        this.ticketId = ticketId;
        this.linkConfidence = linkConfidence;
        this.linkedBy = linkedBy;
    }

    public Long getId() {
        return id;
    }

    public Long getIncidentId() {
        return incidentId;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public BigDecimal getLinkConfidence() {
        return linkConfidence;
    }

    public Long getLinkedBy() {
        return linkedBy;
    }

    public OffsetDateTime getLinkedAt() {
        return linkedAt;
    }

    public OffsetDateTime getDetachedAt() {
        return detachedAt;
    }

    public Long getDetachedBy() {
        return detachedBy;
    }

    public boolean isLive() {
        return detachedAt == null;
    }

    public void detach(Long detachedBy, OffsetDateTime at) {
        this.detachedAt = at;
        this.detachedBy = detachedBy;
    }
}
