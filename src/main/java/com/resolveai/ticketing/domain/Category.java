package com.resolveai.ticketing.domain;

import java.util.Optional;

/**
 * The eight support categories, from {@code seed/domain/taxonomy.yml}.
 *
 * <p>That file is the single source of truth and says so: these values appear in the
 * enum, the triage prompt's output schema, {@code team.skills[]} in the seeder, the eval
 * labels and the generated corpus. Five copies that must agree exactly — so the file is
 * the original and everything else is generated from or checked against it.
 *
 * <h2>Why {@code ticket.category} is still a String</h2>
 *
 * <p>Deliberate, and not laziness. The column is {@code VARCHAR(40)} with no check
 * constraint, because a category the model returns that this enum does not know about
 * must be <i>storable</i> — that is the evidence that the taxonomy needs a new value, and
 * throwing it away at the boundary loses exactly the data that would tell you. The enum
 * is how code reasons about categories; the column is how the system records what
 * actually happened.
 *
 * <h2>PAYMENT and BILLING overlap on purpose</h2>
 *
 * <p>Both concern money, both route to the Payments team, and the distinction — the
 * customer's money versus Ledgerly's own — is genuinely subtle. A taxonomy of eight
 * cleanly separated categories makes classification accuracy look excellent and teaches
 * nothing. "Accuracy is 84%, and the confusion matrix shows PAYMENT/BILLING is most of
 * the error, which is a taxonomy problem rather than a model problem" is both more
 * honest and more useful.
 */
public enum Category {

    /** The customer's money: gateway failures, refunds, settlement delays. */
    PAYMENT,

    /** Ledgerly's own money: subscriptions, plan changes, our invoices to them. */
    BILLING,

    AUTH,

    API,

    PERFORMANCE,

    /** Tally and Zoho connectors, bank feeds, sync failures. */
    INTEGRATION,

    /** Reports, exports, GST returns, missing or duplicated records. */
    DATA,

    /** KYC, business verification, document review, initial setup. */
    ONBOARDING;

    /**
     * Parses a stored or returned value without throwing.
     *
     * <p>Empty rather than an exception, because the caller is usually reading a
     * {@code category} column that may hold a value written by a newer version of the
     * taxonomy — and a read path that throws on unfamiliar historical data is a read
     * path that breaks precisely when somebody is investigating it.
     */
    public static Optional<Category> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Category.valueOf(value.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
