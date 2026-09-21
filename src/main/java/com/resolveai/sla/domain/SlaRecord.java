package com.resolveai.sla.domain;

import com.resolveai.ticketing.domain.Ticket;
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
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

/**
 * One clock on one ticket.
 *
 * <h2>The targets are snapshotted, and that is the point</h2>
 *
 * <p>{@code targetMinutes} and {@code policyVersion} are copied onto this row when the
 * clock starts, rather than joined from {@code sla_policy} at read time. <b>Tightening a
 * policy in October must not retroactively breach a ticket from September.</b> Joining
 * live would do exactly that, silently, and the first anybody would know is a customer
 * disputing a breach that was not a breach when it happened.
 *
 * <p>The foreign key to the policy is kept anyway, for "which policy was this?", but
 * nothing computes from it.
 *
 * <h2>There is no {@code elapsedMinutes} field</h2>
 *
 * <p>Not an omission. Elapsed time is a sum over {@code sla_clock_segment} — see
 * {@code SlaCalculator}. A stored counter would be a lost update waiting to happen the
 * moment two pauses or a pause and a poller run overlap, and the failure mode is a number
 * that is quietly wrong rather than an error.
 *
 * <h2>{@code nextDeadlineAt} is absolute, and null while paused</h2>
 *
 * <p>An absolute instant in an indexed column is what makes the poller cheap
 * ({@code idx_sla_poller} is partial on {@code state = 'RUNNING'}) and what makes a missed
 * deadline recoverable after a restart: the row still says when it was due. Null while
 * paused is what takes a paused clock out of that index entirely.
 */
@Entity
@Table(name = "sla_record")
public class SlaRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
    private Ticket ticket;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "sla_policy_id", nullable = false, updatable = false)
    private Long slaPolicyId;

    /** Snapshot of {@code SlaPolicy.versionLabel}. See the class comment. */
    @Column(name = "policy_version", nullable = false, length = 30, updatable = false)
    private String policyVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private SlaKind kind;

    /** Snapshot. Never re-read from the policy. */
    @Column(name = "target_minutes", nullable = false, updatable = false)
    private int targetMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private SlaState state = SlaState.RUNNING;

    @Column(name = "next_deadline_at")
    private OffsetDateTime nextDeadlineAt;

    @Column(name = "next_rung")
    private Short nextRung = 50;

    @Column(name = "met_at")
    private OffsetDateTime metAt;

    @Column(name = "breached_at")
    private OffsetDateTime breachedAt;

    @Version
    @Column(nullable = false)
    private int version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * Database-owned: {@code trg_sla_record_updated} maintains it, and the poller writes
     * this table with native SQL that {@code @UpdateTimestamp} would never see.
     */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected SlaRecord() {
        // JPA
    }

    public SlaRecord(Ticket ticket, SlaPolicy policy, SlaKind kind, OffsetDateTime deadline) {
        this.ticket = ticket;
        this.slaPolicyId = policy.getId();
        this.policyVersion = policy.getVersionLabel();
        this.kind = kind;
        this.targetMinutes = policy.targetMinutesFor(kind);
        this.nextDeadlineAt = deadline;
        this.nextRung = policy.rungs().isEmpty() ? null : policy.rungs().get(0);
    }

    public Long getId() { return id; }
    public Ticket getTicket() { return ticket; }
    public Long getTenantId() { return tenantId; }
    public Long getSlaPolicyId() { return slaPolicyId; }
    public String getPolicyVersion() { return policyVersion; }
    public SlaKind getKind() { return kind; }
    public int getTargetMinutes() { return targetMinutes; }
    public SlaState getState() { return state; }
    public OffsetDateTime getNextDeadlineAt() { return nextDeadlineAt; }
    public Short getNextRung() { return nextRung; }
    public OffsetDateTime getMetAt() { return metAt; }
    public OffsetDateTime getBreachedAt() { return breachedAt; }
    public int getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setNextDeadlineAt(OffsetDateTime at) { this.nextDeadlineAt = at; }
    public void setNextRung(Short rung) { this.nextRung = rung; }

    /**
     * Pause: state and deadline move together.
     *
     * <p>Kept as one method because they are one fact. Setting {@code state = PAUSED} while
     * leaving a deadline behind would keep the row in {@code idx_sla_poller}'s predicate on
     * the next state change, and clearing the deadline without the state would make a
     * running clock invisible to the poller for ever.
     */
    public void pause() {
        this.state = SlaState.PAUSED;
        this.nextDeadlineAt = null;
    }

    public void resume(OffsetDateTime nextDeadline) {
        this.state = SlaState.RUNNING;
        this.nextDeadlineAt = nextDeadline;
    }

    public void meet(OffsetDateTime at) {
        this.state = SlaState.MET;
        this.metAt = at;
        this.nextDeadlineAt = null;
    }

    public void breach(OffsetDateTime at) {
        this.state = SlaState.BREACHED;
        this.breachedAt = at;
        this.nextDeadlineAt = null;
    }

    /**
     * Superseded rather than deleted.
     *
     * <p>Used when a ticket is reopened: the old resolution clock is cancelled so
     * {@code uq_sla_ticket_kind} - which excludes {@code CANCELLED} - lets a fresh one be
     * created, and the old one stays on the record as history.
     */
    public void cancel() {
        this.state = SlaState.CANCELLED;
        this.nextDeadlineAt = null;
    }

    public boolean isTerminal() {
        return state.isTerminal();
    }
}
