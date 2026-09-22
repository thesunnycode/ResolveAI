package com.resolveai.ticketing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

/**
 * One deterministically-extracted entity mention on one ticket — {@code ERR_PAY_TIMEOUT} on
 * ticket 88301, say.
 *
 * <p>Written by {@code TriageWorker} in {@code tx2}, from {@code EntityExtractor} run
 * against the same redacted text the model saw. Read by
 * {@code com.resolveai.incidents.service.TicketClusterer} to compute the shared-entity boost
 * in similarity scoring — {@code "which other recent tickets mention payment-service?"} is
 * exactly {@code idx_ticket_entity_lookup}.
 *
 * <p>{@code uq_ticket_entity (ticket_id, entity_type, entity_value)} makes a second
 * extraction of the same ticket — a triage retry — idempotent at the database rather than
 * needing a check in application code first.
 */
@Entity
@Table(name = "ticket_entity")
public class TicketEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 24)
    private TicketEntityType entityType;

    @Column(name = "entity_value", nullable = false, length = 80)
    private String entityValue;

    protected TicketEntity() {
        // JPA
    }

    public TicketEntity(Long ticketId, TicketEntityType entityType, String entityValue) {
        this.ticketId = ticketId;
        this.entityType = entityType;
        this.entityValue = entityValue;
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public TicketEntityType getEntityType() {
        return entityType;
    }

    public String getEntityValue() {
        return entityValue;
    }
}
