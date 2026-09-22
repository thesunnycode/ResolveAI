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
 * One message a team lead published about an incident, fanned out to every live linked
 * ticket at the moment it was published.
 *
 * <p>No {@code tenantId}: tenancy is inherited through {@code incident_id}, the same
 * reasoning as {@link IncidentTicket}.
 */
@Entity
@Table(name = "incident_update")
public class IncidentUpdate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private UpdateVisibility visibility = UpdateVisibility.INTERNAL;

    @CreationTimestamp
    @Column(name = "published_at", nullable = false)
    private OffsetDateTime publishedAt;

    protected IncidentUpdate() {
        // JPA
    }

    public IncidentUpdate(Long incidentId, Long authorId, String body,
                          UpdateVisibility visibility) {
        this.incidentId = incidentId;
        this.authorId = authorId;
        this.body = body;
        this.visibility = visibility;
    }

    public Long getId() {
        return id;
    }

    public Long getIncidentId() {
        return incidentId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public String getBody() {
        return body;
    }

    public UpdateVisibility getVisibility() {
        return visibility;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }
}
