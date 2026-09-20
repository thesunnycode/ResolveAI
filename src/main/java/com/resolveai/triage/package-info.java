/**
 * Asynchronous ticket triage: the model produces signals, a versioned rule set turns them into a priority.
 *
 * <p><b>Owns:</b> {@code ai_analysis}, {@code priority_decision}, {@code priority_override}, and the triage worker.
 *
 * <p><b>May depend on:</b> {@code ticketing} by id and event; {@code platform} for the AI gateway; {@code common}.
 *
 * <p><b>The split between the two halves is the point.</b> {@code AiAnalysis} holds what the model observed; {@code PriorityDecision} holds what the rules concluded and which rules fired. Keeping them in separate tables and separate types is what lets a wrong priority be attributed to a model misread or to a policy bug.
 */
package com.resolveai.triage;
