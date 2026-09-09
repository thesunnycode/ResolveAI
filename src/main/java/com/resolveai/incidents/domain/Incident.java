package com.resolveai.incidents.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

/**
 * A proposed or confirmed burst of related tickets.
 *
 * <p>{@code generatedByModel} is nullable and null means the title was templated — see
 * {@code IncidentTitleGenerator}. That is honest provenance rather than an implementation
 * detail: the API exposes the same field, so a reviewer can tell, for any incident, whether
 * a model wrote its title or a fallback did.
 *
 * <p>{@code clusterSizeAtDetection}, {@code arrivalRateMultiple}, {@code firstTicketAt} and
 * {@code detectedAt} are the gate's evidence, stored rather than recomputed — the board and
 * detail views render exactly what the gate saw, not a live re-evaluation that could differ
 * by the time someone reads it.
 *
 * <p>{@code updated_at} is deliberately not mapped: nothing here has a Phase 5-style trigger
 * maintaining it (see {@code V5__incidents.sql}), and nothing in Phase 8 needs it — every
 * state transition already has its own timestamp column.
 */
@Entity
@Table(name = "incident")
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** {@code INC-1000}. Minted from {@link com.resolveai.platform.sequence.ReferenceGenerator}. */
    @Column(nullable = false, length = 24, updatable = false)
    private String reference;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String summary;

    /** Null means the title (and summary) came from the template fallback, not a model. */
    @Column(name = "generated_by_model", length = 80)
    private String generatedByModel;

    @Column(name = "prompt_version_id")
    private Long promptVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IncidentStatus status = IncidentStatus.PROPOSED;

    @Enumerated(EnumType.STRING)
    @Column(name = "detection_method", nullable = false, length = 20)
    private DetectionMethod detectionMethod = DetectionMethod.CLUSTER;

    @Column(name = "cluster_size_at_detection", nullable = false)
    private int clusterSizeAtDetection;

    @Column(name = "arrival_rate_multiple", precision = 6, scale = 2)
    private BigDecimal arrivalRateMultiple;

    @Column(name = "first_ticket_at", nullable = false)
    private OffsetDateTime firstTicketAt;

    @CreationTimestamp
    @Column(name = "detected_at", nullable = false)
    private OffsetDateTime detectedAt;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "rejected_reason", length = 500)
    private String rejectedReason;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Version
    @Column(nullable = false)
    private int version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Incident() {
        // JPA
    }

    public Incident(String reference, String title, String summary, String generatedByModel,
                    Long promptVersionId, DetectionMethod detectionMethod,
                    int clusterSizeAtDetection, BigDecimal arrivalRateMultiple,
                    OffsetDateTime firstTicketAt) {
        this.reference = reference;
        this.title = title;
        this.summary = summary;
        this.generatedByModel = generatedByModel;
        this.promptVersionId = promptVersionId;
        this.detectionMethod = detectionMethod;
        this.clusterSizeAtDetection = clusterSizeAtDetection;
        this.arrivalRateMultiple = arrivalRateMultiple;
        this.firstTicketAt = firstTicketAt;
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getReference() {
        return reference;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public String getGeneratedByModel() {
        return generatedByModel;
    }

    public Long getPromptVersionId() {
        return promptVersionId;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public void setStatus(IncidentStatus status) {
        this.status = status;
    }

    public DetectionMethod getDetectionMethod() {
        return detectionMethod;
    }

    public int getClusterSizeAtDetection() {
        return clusterSizeAtDetection;
    }

    public BigDecimal getArrivalRateMultiple() {
        return arrivalRateMultiple;
    }

    public OffsetDateTime getFirstTicketAt() {
        return firstTicketAt;
    }

    public OffsetDateTime getDetectedAt() {
        return detectedAt;
    }

    public Long getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(Long confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public OffsetDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(OffsetDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public String getRejectedReason() {
        return rejectedReason;
    }

    public void setRejectedReason(String rejectedReason) {
        this.rejectedReason = rejectedReason;
    }

    public OffsetDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(OffsetDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public int getVersion() {
        return version;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
