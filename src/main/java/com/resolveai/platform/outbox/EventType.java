package com.resolveai.platform.outbox;

/**
 * Every kind of thing the outbox carries.
 *
 * <p><b>An enum rather than free strings</b>, because the alternative is a typo in a
 * publisher that matches no worker: the event is written, the row sits at {@code PENDING}
 * for ever, and the only symptom is a feature that silently never happens. The compiler
 * cannot check {@code "TICKET_CREATD"}; it can check this.
 *
 * <p>The column is {@code VARCHAR(50)} and is read back by name, so <b>a value may be
 * added but never renamed</b> — a rename orphans every unprocessed row already written
 * under the old name, and those rows are exactly the ones in flight during the deploy
 * that renamed it.
 */
public enum EventType {

    /** A ticket was created and needs triage. Phase 6's headline path. */
    TICKET_CREATED,

    /** An agent asked for a draft reply. Phase 7. */
    DRAFT_REQUESTED,

    /** A knowledge document changed and its chunks need re-embedding. Phase 7. */
    KNOWLEDGE_DOCUMENT_ADDED,

    /** An incident update was published and must fan out to linked tickets. Phase 8. */
    INCIDENT_UPDATE_PUBLISHED,

    /** A ticket was resolved; its resolution may be worth indexing. Phase 7. */
    TICKET_RESOLVED,

    /** A ticket's priority changed by hand and its clocks may need re-basing. */
    TICKET_RETRIAGE_REQUESTED
}
