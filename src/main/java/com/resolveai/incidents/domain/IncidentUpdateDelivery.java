package com.resolveai.incidents.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One ticket's delivery record for one {@link IncidentUpdate}.
 *
 * <p><b>{@code uq_delivery (incident_update_id, ticket_id)} is the fan-out's idempotency
 * mechanism</b>, not application code. {@code FanoutWorker} claims a row with a conditional
 * {@code UPDATE ... WHERE status = 'PENDING'}; a redelivered outbox event finds the row
 * already {@code SENT} and does nothing. See {@code FanoutWorker}'s class comment.
 */
@Entity
@Table(name = "incident_update_delivery")
public class IncidentUpdateDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_update_id", nullable = false)
    private Long incidentUpdateId;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(nullable = false)
    private short attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected IncidentUpdateDelivery() {
        // JPA
    }

    public IncidentUpdateDelivery(Long incidentUpdateId, Long ticketId) {
        this.incidentUpdateId = incidentUpdateId;
        this.ticketId = ticketId;
    }

    public Long getId() {
        return id;
    }

    public Long getIncidentUpdateId() {
        return incidentUpdateId;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public short getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public OffsetDateTime getDeliveredAt() {
        return deliveredAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
