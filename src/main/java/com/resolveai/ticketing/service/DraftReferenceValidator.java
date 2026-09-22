package com.resolveai.ticketing.service;

/**
 * What ticketing needs from the drafting subsystem to close the gap Phase 5 Task 14 left
 * open: {@code fromDraftId} was accepted and stored, unvalidated. Doc 15 Task 21.
 *
 * <h2>Why a port, the same shape as {@link SlaLifecycle}</h2>
 *
 * <p>{@code drafting} needs {@code ticketing} — a draft is drafted <i>for</i> a ticket,
 * and the drafting package reads tickets, messages and knowledge chunks to build one.
 * Wiring the dependency the other way, with {@code ticketing} calling into
 * {@code drafting} directly, would complete a cycle between the two largest packages in
 * the system. An interface owned by the caller breaks it exactly as it did for the SLA
 * engine in Phase 5: {@code drafting} depends on {@code ticketing}, and
 * {@code ticketing} depends only on this file.
 *
 * <p>Every method runs in the caller's transaction — the message insert and the action
 * auto-capture have to commit together, or a crash between them leaves a message with a
 * draft reference and no recorded outcome, silently corrupting the {@code SENT_AS_IS}
 * rate the whole mechanism exists to measure honestly.
 */
public interface DraftReferenceValidator {

    /**
     * Confirms {@code fromDraftId} belongs to this ticket and is {@code SHOWN}.
     *
     * @throws com.resolveai.common.error.ApiException {@code 422 INVALID_DRAFT_REFERENCE}
     *                                                  otherwise
     */
    void requireValid(Long ticketId, Long draftId);

    /**
     * Records the draft's outcome automatically, from the message that was just sent.
     *
     * <p>Identical text to {@code assembledText} is {@code SENT_AS_IS}; anything else is
     * {@code EDITED}, with the server-computed edit distance. Does nothing if an action
     * was already recorded for this draft — an agent who called {@code POST
     * /drafts/{id}/action} by hand before sending is not silently overwritten.
     */
    void recordAutomaticAction(Long draftId, Long agentId, String sentBody);
}
