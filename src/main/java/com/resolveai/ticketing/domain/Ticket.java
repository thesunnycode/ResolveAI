package com.resolveai.ticketing.domain;

import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.Team;
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
 * A support ticket.
 *
 * <h2>Three columns in {@code V2__ticketing.sql} are deliberately not mapped</h2>
 *
 * <ul>
 *   <li><b>{@code search_tsv}</b> — maintained by {@code trg_ticket_tsv}, a BEFORE INSERT OR
 *       UPDATE trigger that derives it from {@code subject} and {@code body}. Mapping it
 *       would have Hibernate write a value the trigger immediately recomputes, and the two
 *       would fight on every update. It is read only from native search queries.
 *   <li><b>{@code embedding}</b> — {@code vector(768)}, written in Phase 6. pgvector needs a
 *       custom Hibernate type, and there is no reason to wire one before anything writes to
 *       the column.
 *   <li><b>{@code updated_at}</b> — owned by {@code trg_ticket_updated}, for the same reason
 *       {@code AgentProfile.updatedAt} is: native UPDATEs (the conditional assign in
 *       {@code /assign}) bypass {@code @UpdateTimestamp} entirely, and only the trigger sees
 *       all of them. Mapped read-only so it can still be serialised.
 * </ul>
 *
 * <p>{@code ddl-auto: validate} checks that every <i>mapped</i> attribute has a column, not
 * that every column has an attribute, so leaving these out is safe.
 *
 * <h2>{@code @Version} is what makes the ETag contract real</h2>
 *
 * <p>Two agents with the same ticket open both submit an edit; the second is rejected with
 * {@code 409 VERSION_CONFLICT} rather than silently overwriting the first. The
 * {@code If-Match} interceptor is a cheap early check — this column is the guarantee.
 */
@Entity
@Table(name = "ticket")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /**
     * {@code TKT-10428}. The only identifier a customer ever sees.
     *
     * <p>Per-tenant, from {@code tenant_sequence}, rather than the primary key with a prefix:
     * a global {@code TKT-88213} tells every customer of every tenant roughly how many
     * tickets exist system-wide.
     */
    @Column(nullable = false, length = 24, updatable = false)
    private String reference;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private TicketStatus status = TicketStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Priority priority = Priority.UNTRIAGED;

    @Column(length = 40)
    private String category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_id", nullable = false, updatable = false)
    private AppUser requester;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private AppUser assignee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    /** An input to the Phase 6 priority policy: a ticket reopened twice is not routine. */
    @Column(name = "reopen_count", nullable = false)
    private int reopenCount;

    /**
     * When an agent first replied publicly.
     *
     * <p><b>This is the column the reply-versus-breach race turns on</b> (Phase 5 Task 33).
     * It is written in the same transaction as the message that sets it and as the
     * first-response clock's transition to {@code MET}, so a reply landing at the instant a
     * breach would fire cannot produce both. Anything that moves this write out of that
     * transaction reopens the race.
     */
    @Column(name = "first_responded_at")
    private OffsetDateTime firstRespondedAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Version
    @Column(nullable = false)
    private int version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Database-owned. See the class comment. */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected Ticket() {
        // JPA
    }

    public Ticket(String reference, String subject, String body, AppUser requester) {
        this.reference = reference;
        this.subject = subject;
        this.body = body;
        this.requester = requester;
    }

    public Long getId() { return id; }
    public Long getTenantId() { return tenantId; }
    public String getReference() { return reference; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public TicketStatus getStatus() { return status; }
    public Priority getPriority() { return priority; }
    public String getCategory() { return category; }
    public AppUser getRequester() { return requester; }
    public AppUser getAssignee() { return assignee; }
    public Team getTeam() { return team; }
    public int getReopenCount() { return reopenCount; }
    public OffsetDateTime getFirstRespondedAt() { return firstRespondedAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public OffsetDateTime getClosedAt() { return closedAt; }
    public int getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setSubject(String subject) { this.subject = subject; }
    public void setCategory(String category) { this.category = category; }
    public void setTeam(Team team) { this.team = team; }
    public void setAssignee(AppUser assignee) { this.assignee = assignee; }
    public void setPriority(Priority priority) { this.priority = priority; }
    public void setFirstRespondedAt(OffsetDateTime at) { this.firstRespondedAt = at; }

    /**
     * Moves the ticket and maintains the timestamps that go with the destination.
     *
     * <p>Kept here rather than in the service so that {@code resolvedAt} and {@code closedAt}
     * cannot drift out of step with {@code status} — the pair is one fact, and nothing
     * outside this method sets either half.
     *
     * <p>It does <b>not</b> validate the move: that is {@link TicketStateMachine}'s job, and
     * duplicating the table here would give it two owners.
     */
    public void moveTo(TicketStatus target, OffsetDateTime now) {
        this.status = target;
        this.resolvedAt = target == TicketStatus.RESOLVED ? now : null;
        this.closedAt = target == TicketStatus.CLOSED ? now : null;
    }

    /** Reopen: back to {@code OPEN}, counter up, resolution timestamps cleared. */
    public void reopen() {
        this.status = TicketStatus.OPEN;
        this.reopenCount++;
        this.resolvedAt = null;
        this.closedAt = null;
    }

    public boolean isClosed() {
        return status == TicketStatus.CLOSED;
    }
}
