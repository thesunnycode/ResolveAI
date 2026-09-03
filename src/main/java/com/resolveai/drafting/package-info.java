/**
 * Citation-enforced reply drafting: claim extraction, numeric pre-filter, entailment verification, coverage suppression.
 *
 * <p><b>Owns:</b> {@code draft}, {@code draft_claim}, {@code draft_claim_citation}, {@code agent_draft_action}, and the draft worker.
 *
 * <p><b>May depend on:</b> {@code knowledge} for retrieval; {@code ticketing} by id; {@code platform}; {@code common}.
 *
 * <p>Groundedness is checked <b>per claim</b>, not per response. Response-level averaging hides unsupported sentences inside otherwise-good answers, which is exactly the failure this module exists to prevent.
 */
package com.resolveai.drafting;
