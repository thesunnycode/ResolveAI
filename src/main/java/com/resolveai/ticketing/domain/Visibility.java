package com.resolveai.ticketing.domain;

/**
 * Whether a message is visible to the requester.
 *
 * <p>Two values, and the whole of the project's field-visibility risk is concentrated in
 * them. {@code ck_message_visibility} constrains the column as well, because a bug here is
 * not a bad response shape — it is an internal note reaching the customer it was written
 * about.
 */
public enum Visibility {

    /** The customer sees it. Counts toward the first-response clock when an agent writes it. */
    PUBLIC,
    /** Agents only. Never serialised into {@code TicketCustomerResponse}. */
    INTERNAL
}
