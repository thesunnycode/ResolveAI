package com.resolveai.ticketing.domain;

/**
 * The kinds of technical entity {@code EntityExtractor} pulls out of a ticket's redacted
 * text. Matches {@code ck_entity_type}.
 *
 * <p>Deliberately not the LLM's vocabulary. {@code TriageSignals.extractedEntities()} is an
 * advisory, free-form map the model produces; this enum backs the deterministic extraction
 * that feeds the correlation gate, and the two are not the same field for the same reason
 * the gate has no model in it at all — see {@code EntityExtractor}'s class comment.
 */
public enum TicketEntityType {
    ERROR_CODE,
    SERVICE,
    REGION,
    PAYMENT_METHOD,
    APP_VERSION,
    HTTP_STATUS
}
