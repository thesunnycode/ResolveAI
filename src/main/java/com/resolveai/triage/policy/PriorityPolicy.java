package com.resolveai.triage.policy;

import com.resolveai.platform.ai.model.TriageSignals;
import com.resolveai.ticketing.domain.Priority;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns observations into a priority. <b>The component the whole project's argument rests
 * on.</b>
 *
 * <h2>Why this class has no annotations, no repository and no I/O</h2>
 *
 * <p>Read the imports: four, none of them Spring, JPA or anything that touches a network
 * or a database. That is deliberate, and a test asserts it. The reason is not purity for
 * its own sake — it is that the four properties below all follow from it, and all four
 * are lost the moment the language model is asked for a priority instead of for
 * observations:
 *
 * <ol>
 *   <li><b>Explainable.</b> "P1 because the customer reports data loss" is a sentence
 *       generated from the rule that fired, not a post-hoc guess at what a model was
 *       thinking.
 *   <li><b>Reproducible.</b> The same inputs give the same answer for ever. A model's do
 *       not: temperature, a provider's silent version bump and a re-prompt all move it.
 *   <li><b>Testable.</b> {@code PriorityPolicyTest} runs thirty-odd cases and a thousand
 *       random ones in milliseconds, with no model, no Spring context and no bill. There
 *       is nothing equivalent for "does the model still say P2 for this".
 *   <li><b>Changeable.</b> Tuning a rule is an edit here plus a version bump. Tuning a
 *       model's priority judgement means re-running every ticket through the model, and
 *       leaves every historical decision unexplainable in terms of the new behaviour.
 * </ol>
 *
 * <p>The tempting shortcut — one more field in the prompt's output schema — is easy,
 * appears to work immediately, and silently deletes all four. {@code V10__seed_prompts}
 * carries the same warning at the other end of the pipe.
 *
 * <h2>Levels are integers here, and only here</h2>
 *
 * <p>{@link Priority} is an enum so that nothing outside this class can do arithmetic on
 * a priority. Inside it the bumps need arithmetic, so the rules work on {@code 1..4}
 * (1 being P1, the most urgent) and convert back once at the end. "+1" in the rule table
 * therefore means <i>one level more urgent</i>, which is {@code level - 1} — worth
 * stating, because the opposite reading is equally natural and produces a policy that
 * looks right and is exactly backwards.
 */
public final class PriorityPolicy {

    /**
     * The version recorded on every decision this class makes.
     *
     * <p><b>Bump it whenever a rule changes</b>, including a threshold. It is written to
     * {@code priority_decision.policy_version} and is the only thing that makes a
     * rationale from three months ago readable as the argument that was actually made,
     * rather than as today's rules applied to yesterday's inputs.
     */
    public static final String VERSION = "v1";

    private static final int MOST_URGENT = 1;   // P1
    private static final int LEAST_URGENT = 4;  // P4

    /** Rule names. Constants because they are persisted and must not drift with a rename. */
    public static final String BASE_FROM_IMPACT = "BASE_FROM_IMPACT";
    public static final String SERVICE_DOWN_BUMP = "SERVICE_DOWN_BUMP";
    public static final String DATA_LOSS_BUMP = "DATA_LOSS_BUMP";
    public static final String PAYMENT_BUMP = "PAYMENT_BUMP";
    public static final String PLAN_TIER_BUMP = "PLAN_TIER_BUMP";
    public static final String INCIDENT_INHERIT = "INCIDENT_INHERIT";
    public static final String REOPEN_BUMP = "REOPEN_BUMP";
    public static final String CLAMP = "CLAMP";

    /** How many reopens before the ticket is treated as evidence of a bad resolution. */
    private static final int REOPEN_THRESHOLD = 2;

    public PriorityPolicy() {
        // Instantiable so callers can hold it as a collaborator and a later version can
        // be selected at runtime; stateless, so one instance is safe everywhere.
    }

    /**
     * Evaluates the rules in order and returns the priority with its full trace.
     *
     * <p>Every rule appends exactly one {@link RuleTrace}, matched or not, so the trace
     * length is constant and the UI renders a stable list rather than one whose shape
     * depends on the answer.
     */
    public PriorityDecision evaluate(PolicyInputs inputs) {
        List<RuleTrace> trace = new ArrayList<>(8);
        TriageSignals signals = inputs.signals();

        // -- 1. Base level, from what the customer says is affected ----------
        //
        // Impact, not urgency. linguisticUrgency is deliberately not consulted by any
        // rule in this policy: it measures capital letters and exclamation marks, and
        // weighting it would systematically deprioritise polite customers and reward
        // shouting. It is captured and shown because it is useful context for a human,
        // and it decides nothing.
        int level = switch (signals.reportedImpact()) {
            case ORG_WIDE -> 1;
            case TEAM -> 2;
            case SINGLE_USER -> 3;
        };
        trace.add(RuleTrace.matched(BASE_FROM_IMPACT, "P" + level,
                impactLabel(signals.reportedImpact()) + " impact starts at P" + level));

        // -- 2. Something is completely unusable -----------------------------
        if (signals.serviceDownClaimed()) {
            level = bump(level);
            trace.add(RuleTrace.matched(SERVICE_DOWN_BUMP, "+1",
                    "the ticket states something is completely unusable"));
        } else {
            trace.add(RuleTrace.skipped(SERVICE_DOWN_BUMP,
                    "no claim that anything is completely unusable"));
        }

        // -- 3. Data loss goes straight to P1 --------------------------------
        //
        // Absolute, not a bump, and the only signal treated that way. Data that is gone
        // gets worse with every hour of backup rotation and log expiry, so the cost of
        // arriving late is not proportional to the delay the way it is for an outage. A
        // bump from P4 would land at P3 and be looked at tomorrow.
        if (signals.dataLossClaimed()) {
            level = MOST_URGENT;
            trace.add(RuleTrace.matched(DATA_LOSS_BUMP, "P1",
                    "the ticket states data is missing, lost or wrong"));
        } else {
            trace.add(RuleTrace.skipped(DATA_LOSS_BUMP, "no data loss claimed"));
        }

        // -- 4. Money --------------------------------------------------------
        if (signals.paymentAffected()) {
            level = bump(level);
            trace.add(RuleTrace.matched(PAYMENT_BUMP, "+1",
                    "money has moved, failed to move, or is at risk"));
        } else {
            trace.add(RuleTrace.skipped(PAYMENT_BUMP, "no payment impact reported"));
        }

        // -- 5. What the tenant pays for -------------------------------------
        //
        // The one rule that is commercial rather than technical, and it is honest about
        // it: an ENTERPRISE contract buys a faster first look and FREE does not. Keeping
        // it as a named rule in the trace means an agent can see that a ticket is P2
        // rather than P3 because of the plan, which is exactly the kind of thing that
        // should be visible rather than buried in a weighting.
        switch (inputs.planTier()) {
            case ENTERPRISE -> {
                level = bump(level);
                trace.add(RuleTrace.matched(PLAN_TIER_BUMP, "+1",
                        "Enterprise plan raises one level"));
            }
            case FREE -> {
                level = lower(level);
                trace.add(RuleTrace.matched(PLAN_TIER_BUMP, "-1",
                        "Free plan lowers one level"));
            }
            case PRO -> trace.add(RuleTrace.skipped(PLAN_TIER_BUMP,
                    "Pro does not change the level; Enterprise would raise it"));
        }

        // -- 6. A live incident overrides everything above and below ---------
        //
        // Fifty tickets about one outage must not arrive at fifty different priorities
        // because of how each was worded or which plan each customer is on. They are one
        // problem, so they inherit one priority, and the incident's own priority is the
        // considered judgement about the outage rather than about any single report.
        //
        // Terminal on purpose: the rule after it is recorded as not evaluated rather than
        // as not matching, because "REOPEN_BUMP: false" would read as "this ticket has
        // not been reopened", which may be untrue.
        boolean inherited = inputs.linkedIncidentPriority() != null;
        if (inherited) {
            level = levelOf(inputs.linkedIncidentPriority());
            trace.add(RuleTrace.matched(INCIDENT_INHERIT,
                    inputs.linkedIncidentPriority().name(),
                    "inherited from the linked live incident, overriding the rules above"));
        } else {
            trace.add(RuleTrace.skipped(INCIDENT_INHERIT, "not linked to a live incident"));
        }

        // -- 7. Repeatedly reopened ------------------------------------------
        //
        // Threshold 2, not 1. One reopen is ordinary — a customer replying "still not
        // working" after a plausible fix — and bumping on it would raise a large fraction
        // of all tickets, which is the same as raising none. Two is a pattern.
        if (inherited) {
            trace.add(RuleTrace.skipped(REOPEN_BUMP,
                    "not evaluated: the priority is inherited from a live incident"));
        } else if (inputs.reopenCount() >= REOPEN_THRESHOLD) {
            level = bump(level);
            trace.add(RuleTrace.matched(REOPEN_BUMP, "+1",
                    "reopened " + inputs.reopenCount() + " times; previous resolutions "
                    + "did not hold"));
        } else {
            trace.add(RuleTrace.skipped(REOPEN_BUMP,
                    "requires reopenCount >= " + REOPEN_THRESHOLD + ", this ticket has "
                    + inputs.reopenCount()));
        }

        // -- 8. Clamp ---------------------------------------------------------
        //
        // bump() and lower() already saturate, so this can only ever record "in range".
        // It stays in the trace regardless: it is the visible proof that the output is
        // bounded, and it is the line that would start matching if somebody later added a
        // rule that does its own arithmetic.
        int clamped = Math.min(LEAST_URGENT, Math.max(MOST_URGENT, level));
        if (clamped != level) {
            trace.add(RuleTrace.matched(CLAMP, "P" + clamped,
                    "computed level " + level + " clamped into P1-P4"));
            level = clamped;
        } else {
            trace.add(RuleTrace.skipped(CLAMP, "already within P1-P4"));
        }

        Priority priority = priorityOf(level);
        return new PriorityDecision(priority, VERSION, trace,
                explain(priority, trace, inputs));
    }

    private static String impactLabel(TriageSignals.Severity s) {
        return switch (s) {
            case ORG_WIDE -> "Organisation-wide";
            case TEAM -> "Team-level";
            case SINGLE_USER -> "Single-user";
        };
    }

    /** One level more urgent, saturating at P1. */
    private static int bump(int level) {
        return Math.max(MOST_URGENT, level - 1);
    }

    /** One level less urgent, saturating at P4. */
    private static int lower(int level) {
        return Math.min(LEAST_URGENT, level + 1);
    }

    private static int levelOf(Priority priority) {
        if (!priority.isTriaged()) {
            // An incident whose own priority is UNTRIAGED cannot lend one. Reaching here
            // means something linked a ticket to an incident that had not been triaged
            // itself, which is a bug there, not a case to paper over with a default.
            throw new IllegalArgumentException(
                    "A linked incident cannot have priority UNTRIAGED");
        }
        return Integer.parseInt(priority.name().substring(1));
    }

    private static Priority priorityOf(int level) {
        return Priority.valueOf("P" + level);
    }

    /**
     * The sentence in the info popover.
     *
     * <p>Generated from the matched rules rather than written per outcome, so it cannot
     * drift away from what the policy actually did — a hand-written string saying
     * "because it is a payment issue" would survive unchanged after {@code PAYMENT_BUMP}
     * stopped applying, and would then be a confident lie.
     */
    private static String explain(Priority priority, List<RuleTrace> trace,
                                  PolicyInputs inputs) {
        // Replays the matched rules as steps ("starts at P3 ...; raised to P2 ...") rather
        // than listing reasons. A list reads "P1 because it affects one person, and ..." -
        // which makes the one rule that pulled the priority *down* sound like a reason for
        // urgency. Showing how the level moved is what an agent deciding on an override
        // actually needs.
        List<String> steps = new ArrayList<>(6);
        List<String> noEffect = new ArrayList<>(4);
        int level = LEAST_URGENT;
        for (RuleTrace rule : trace) {
            if (!rule.matched() || CLAMP.equals(rule.rule())) {
                continue;
            }
            String why = reasonFor(rule.rule(), inputs);
            if (rule.effect().startsWith("P")) {
                level = Integer.parseInt(rule.effect().substring(1));
                steps.add(BASE_FROM_IMPACT.equals(rule.rule())
                        ? "starts at P" + level + " because " + why
                        : "set to P" + level + " because " + why);
            } else {
                int next = rule.effect().startsWith("+") ? bump(level) : lower(level);
                if (next == level) {
                    // Saturated. Grouped into one clause at the end rather than a repeated
                    // "stays at P1" per rule, which read as three separate decisions.
                    noEffect.add(why);
                } else {
                    steps.add((next < level ? "raised" : "lowered") + " to P" + next + " because " + why);
                    level = next;
                }
            }
        }
        String sentence = priority.name() + ": " + String.join("; ", steps) + ".";
        if (!noEffect.isEmpty()) {
            sentence += " Already at " + priority.name() + ", so this made no difference: "
                    + joinAnd(noEffect) + ".";
            if (noEffect.size() > 1) {
                sentence = sentence.replace("so this made", "so these made");
            }
        }
        return sentence;
    }

    private static String joinAnd(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    private static String reasonFor(String rule, PolicyInputs inputs) {
        return switch (rule) {
            case BASE_FROM_IMPACT -> switch (inputs.signals().reportedImpact()) {
                case ORG_WIDE -> "the customer says their whole organisation is affected";
                case TEAM -> "the customer says a team is affected";
                case SINGLE_USER -> "it affects one person";
            };
            case SERVICE_DOWN_BUMP -> "they say something is completely unusable";
            case DATA_LOSS_BUMP -> "they report data missing or wrong";
            case PAYMENT_BUMP -> "money is involved";
            case PLAN_TIER_BUMP -> "the account is on the " + planName(inputs) + " plan";
            case INCIDENT_INHERIT -> "it is linked to a live incident";
            case REOPEN_BUMP -> "it has been reopened " + inputs.reopenCount() + " times";
            default -> rule;
        };
    }

    private static String planName(PolicyInputs inputs) {
        String p = inputs.planTier().name();
        return p.charAt(0) + p.substring(1).toLowerCase(java.util.Locale.ROOT);
    }
}
