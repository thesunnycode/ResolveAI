package com.resolveai.incidents.domain;

/** Who an incident update is for. Matches {@code ck_iu_visibility}. */
public enum UpdateVisibility {
    /** Fanned out to customers as a public message on every linked ticket. */
    PUBLIC,
    /** Team-facing only; never appended to a ticket's public thread. */
    INTERNAL
}
