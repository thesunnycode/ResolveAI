package com.resolveai.platform.ai.model;

/**
 * The three ways a model call fails, kept apart because the caller does something
 * different with each.
 *
 * <p>A single {@code AiException} would collapse "try again in a minute", "give up and
 * let a human do it" and "stop spending this tenant's money" into one branch — and the
 * worker's retry logic would then either burn five attempts on a budget that will still
 * be exhausted, or dead-letter a transient timeout that would have worked on the next
 * poll.
 */
public final class LlmExceptions {

    private LlmExceptions() {
    }

    /**
     * The model answered, and the answer could not be turned into the requested type —
     * after a repair attempt.
     *
     * <p>Not retryable by the worker: the same prompt against a deterministic model
     * produces the same unusable output, so five attempts cost four extra calls and
     * arrive at the same place. The right response is to record the failure and let a
     * human triage the ticket.
     */
    public static class LlmParseException extends RuntimeException {
        private final String rawOutput;

        public LlmParseException(String message, String rawOutput, Throwable cause) {
            super(message, cause);
            this.rawOutput = rawOutput;
        }

        /** What the model actually said. Stored on the analysis row for diagnosis. */
        public String rawOutput() {
            return rawOutput;
        }
    }

    /**
     * No model could be reached, or policy forbids reaching one.
     *
     * <p>Retryable when it is a provider problem, which is why the worker retries it and
     * the dead-letter only happens after the backoff has been exhausted. A tenant who has
     * turned external models off produces this too, and the retries are wasted in that
     * case — an acceptable cost for not having two exception types that differ only in
     * who is to blame.
     */
    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message) {
            super(message);
        }

        public LlmUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The tenant has spent their monthly budget.
     *
     * <p><b>Not an error condition of the system.</b> It is the budget working. The
     * worker marks the event held rather than failed, notifies an admin, and — this is
     * the part that matters — <b>ticketing carries on completely unaffected</b>. Tickets
     * are created, assigned, replied to and resolved; only the AI assistance stops.
     */
    public static class BudgetExhaustedException extends RuntimeException {
        private final long budgetMicros;
        private final long spentMicros;

        public BudgetExhaustedException(Long tenantId, long budgetMicros, long spentMicros) {
            super("Tenant " + tenantId + " has spent " + spentMicros + " of " + budgetMicros
                  + " micros this month; AI calls are held until the budget resets or is raised");
            this.budgetMicros = budgetMicros;
            this.spentMicros = spentMicros;
        }

        public long budgetMicros() {
            return budgetMicros;
        }

        public long spentMicros() {
            return spentMicros;
        }
    }
}
