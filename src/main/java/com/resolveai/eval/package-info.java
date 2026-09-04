/**
 * The offline evaluation harness and the CI quality gate.
 *
 * <p><b>Owns:</b> {@code eval_case}, {@code eval_run}, {@code eval_result}, the suites (TRIAGE, GROUNDING, REFUSAL, CORRELATION) and the runner.
 *
 * <p><b>May depend on:</b> Reads the outputs of {@code triage}, {@code drafting} and {@code incidents}. <b>Nothing depends on eval</b>, which is what keeps it safe to change.
 *
 * <p>Prompts ship with the code and are seeded by Flyway precisely so that a prompt change is a reviewable diff behind this gate, rather than a runtime edit that bypasses it.
 */
package com.resolveai.eval;
