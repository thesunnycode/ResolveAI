package com.resolveai.platform.ai.prompt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One version of one prompt. <b>Immutable: no setters, and a trigger behind them.</b>
 *
 * <h2>Why every analysis references this by foreign key</h2>
 *
 * <p>"Why did this ticket get classified as BILLING in March?" is a question with exactly
 * one useful answer: the prompt that was sent, the model it was sent to, and the
 * parameters it ran under. A prompt held in a Java constant answers that with "whatever
 * was deployed at the time", which is not an answer. A row that {@code ai_analysis}
 * points at answers it exactly, months later, after the prompt has been superseded twice.
 *
 * <p>{@code trg_prompt_immutable} rejects UPDATE of the template, model or schema, and
 * the absence of setters here matches it — so the compiler refuses before the database
 * has to. A new prompt is a new row; {@code uq_prompt_active} allows exactly one active
 * version per name, which makes "which one is live?" a fact rather than a convention.
 */
@Entity
@Table(name = "prompt_version")
public class PromptVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 60)
    private String name;

    @Column(nullable = false, updatable = false)
    private int version;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String template;

    @Column(name = "model_id", nullable = false, updatable = false, length = 80)
    private String modelId;

    /** Temperature, seed, and anything else the call must be reproducible under. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private String params = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_schema", updatable = false, columnDefinition = "jsonb")
    private String outputSchema;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected PromptVersion() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getVersion() {
        return version;
    }

    public String getTemplate() {
        return template;
    }

    public String getModelId() {
        return modelId;
    }

    public String getParams() {
        return params;
    }

    public String getOutputSchema() {
        return outputSchema;
    }

    public boolean isActive() {
        return active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /** The label that appears in API responses and logs: {@code triage@1}. */
    public String label() {
        return name + "@" + version;
    }
}
