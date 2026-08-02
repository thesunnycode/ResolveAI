package com.resolveai.ticketing.web.dto;

import jakarta.validation.constraints.Size;

/**
 * A partial update of <b>subject, category and team only</b>.
 *
 * <p>Status, priority and assignee each have a dedicated endpoint with their own
 * authorization and side effects, and are deliberately not reachable from here — doc 05 §5
 * Deviation 1.
 *
 * <p><b>Never bind the entity.</b> A {@code PATCH} handler taking a {@code Ticket} is the
 * textbook mass-assignment vulnerability: a customer posts {@code {"priority":"P1"}}, or
 * {@code {"tenantId":2}}, and Jackson obligingly sets it. The fields this class does not
 * declare are fields no request can touch.
 *
 * <h2>Why this is a bean with presence flags and not a record of {@code Optional}</h2>
 *
 * <p>PATCH has to distinguish three states and a plain field expresses two: <i>absent</i>
 * means "leave it alone", an explicit {@code null} means "clear it", and a value means "set
 * it".
 *
 * <p>The obvious answer is {@code Optional<String> subject} on a record — {@code null} for
 * absent, {@code Optional.empty()} for an explicit null. <b>It does not work, and it fails
 * silently in the dangerous direction.</b> Jackson treats {@code Optional} as having a
 * defined absent value, so a missing property is supplied to the canonical constructor as
 * {@code Optional.empty()}, indistinguishable from {@code "category": null}. The result:
 * {@code PATCH {"subject":"..."}} cleared the ticket's category <i>and</i> its team,
 * returned {@code 200}, and the next read of that ticket was a {@code 404} because it was
 * no longer in any team the caller could see.
 *
 * <p>That is what {@code EtagConcurrencyTest} found, and it is worth keeping the story: the
 * bug was in the mechanism chosen <i>specifically to prevent</i> unmentioned fields being
 * wiped.
 *
 * <p>A setter is called only when the key is present in the JSON — including when its value
 * is {@code null} — so recording presence in the setter is exact, needs no extra
 * dependency, and cannot be got wrong by a library upgrade.
 */
public class UpdateTicketRequest {

    @Size(min = 1, max = 200, message = "Subject must be 1-200 characters")
    private String subject;
    private boolean subjectPresent;

    @Size(max = 40, message = "Category must be at most 40 characters")
    private String category;
    private boolean categoryPresent;

    private Long teamId;
    private boolean teamIdPresent;

    public void setSubject(String subject) {
        this.subject = subject;
        this.subjectPresent = true;
    }

    public void setCategory(String category) {
        this.category = category;
        this.categoryPresent = true;
    }

    public void setTeamId(Long teamId) {
        this.teamId = teamId;
        this.teamIdPresent = true;
    }

    public String getSubject() { return subject; }
    public String getCategory() { return category; }
    public Long getTeamId() { return teamId; }

    public boolean hasSubject() { return subjectPresent; }
    public boolean hasCategory() { return categoryPresent; }
    public boolean hasTeamId() { return teamIdPresent; }

    /** True when the body mentioned nothing this endpoint can change. */
    public boolean isEmpty() {
        return !subjectPresent && !categoryPresent && !teamIdPresent;
    }
}
