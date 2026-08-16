package com.resolveai.platform.ai.pii;

/**
 * What kind of thing a placeholder stands for.
 *
 * <p>The name appears in the placeholder the model sees — {@code «CARD_1»} — and that is
 * deliberate: the model needs to know a card number was here in order to reason about a
 * payment problem, and it does not need the digits. Replacing everything with an
 * anonymous {@code «REDACTED»} would destroy the meaning along with the data.
 *
 * <p>Ordered by <b>specificity, most specific first</b>. Detection runs in this order and
 * an earlier match wins the overlap, which is what stops a twelve-digit Aadhaar number
 * being half-eaten by the phone-number detector.
 */
public enum PiiType {

    /** {@code [A-Z]{5}[0-9]{4}[A-Z]} — India's tax id, and unmistakable. */
    GOV_ID,

    /** 13–19 digits that pass Luhn. Checked, not guessed — see {@code Luhn}. */
    CARD,

    /** A tenant-configured order prefix, e.g. {@code ORD-88213}. */
    ORDER_REF,

    EMAIL,

    /** {@code +91} forms, bare Indian ten-digit numbers, and generic international. */
    PHONE,

    IP,

    /** An exact match against a known user's name in this tenant. */
    PERSON
}
