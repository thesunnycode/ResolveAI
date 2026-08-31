-- V12__seed_draft_prompts.sql
-- draft@1 and entailment@1 — the two prompts behind citation-enforced drafting.
--
-- Same reasoning as V10 for triage@1: a prompt is a dependency of every draft ever
-- produced, and a prompt held in a Java constant answers "what were we asking in March?"
-- with "whatever was deployed", which is not an answer. Both are referenced by foreign
-- key from every draft.prompt_version_id and (indirectly, via the calling code) every
-- entailment cache entry.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ⚠ draft@1 PRODUCES CLAIMS, NOT PROSE, AND ENTAILMENT@1 SEES ONLY ONE CLAIM AND ONE SPAN.
--
-- Both constraints are load-bearing and both are easy to relax by accident:
--
--   * If draft@1 is ever changed to return a single "response" string instead of a list
--     of individually-citable claims, the whole verification pipeline has nothing to
--     verify claim by claim, and coverage collapses to "did the model cite something
--     somewhere" - which is not a check on the actual sentences a customer will read.
--
--   * If entailment@1 is ever given the ticket, the other retrieved chunks, or the rest
--     of the draft, it stops being an independent check. A verifier with the full
--     context will rationalise support from material the claim did not cite - which
--     defeats the entire mechanism, because now a plausible-sounding claim can pass by
--     leaning on context the citation never named.
-- ─────────────────────────────────────────────────────────────────────────────
--
-- DO NOT EDIT once applied: Flyway checksums this file.

INSERT INTO prompt_version (name, version, template, model_id, params, output_schema, is_active)
VALUES (
    'draft',
    1,
    $prompt$You are drafting a reply for a support agent at Ledgerly, an accounting and
payments product used by small businesses in India. The agent will read your draft,
verify it, and decide what to send - you are not sending anything yourself.

You will be shown the customer's ticket and a set of retrieved knowledge-base passages,
each labelled with a stable id like chunk:33401. Write a small number of short,
individually verifiable claims that answer the ticket, using ONLY the retrieved passages.

RULES, and they are checked mechanically after you answer:

  1. ONE VERIFIABLE ASSERTION PER CLAIM. Not a paragraph - a single sentence a person
     could independently confirm true or false against one passage.
  2. EVERY CLAIM CITES AT LEAST ONE RETRIEVED CHUNK by its exact id, e.g. "chunk:33401".
     Never invent a chunk id. Never cite a chunk that does not support the claim.
  3. NEVER STATE A NUMBER, DATE, AMOUNT, DURATION OR IDENTIFIER THAT DOES NOT APPEAR IN A
     CITED PASSAGE. Not a plausible estimate, not a typical figure - if the passages do
     not contain it, leave it out of the claim entirely. This is checked automatically
     against the cited text and a violation drops the claim before a human ever sees it.
  4. If the ticket asks something the retrieved passages do not cover, do not guess.
     List it in unresolvedAspects instead of writing a claim for it.
  5. The text may contain tokens like «PERSON_1» or «ORDER_REF_1» - redacted personal
     data. Copy them verbatim where relevant; never guess what is behind one.
  6. suggestedTone is APOLOGETIC when the ticket describes a failure that cost the
     customer money or time, REASSURING when it describes a delay that is normal and
     resolving, and NEUTRAL for an informational question.

{ticket}
$prompt$,
    'gpt-4.1',
    '{"temperature": 0, "seed": 20260201}'::jsonb,
    $schema$
    {
      "type": "object",
      "additionalProperties": false,
      "required": ["claims", "suggestedTone", "unresolvedAspects"],
      "properties": {
        "claims": {
          "type": "array",
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["text", "citationIds"],
            "properties": {
              "text": { "type": "string" },
              "citationIds": { "type": "array", "items": { "type": "string" }, "minItems": 1 }
            }
          }
        },
        "suggestedTone": { "type": "string", "enum": ["APOLOGETIC", "NEUTRAL", "REASSURING"] },
        "unresolvedAspects": { "type": "array", "items": { "type": "string" } }
      }
    }
    $schema$::jsonb,
    TRUE
);

INSERT INTO prompt_version (name, version, template, model_id, params, output_schema, is_active)
VALUES (
    'entailment',
    1,
    $prompt$Does the SPAN below support the CLAIM? Judge only what the span actually
says - not whether the claim sounds plausible, not general knowledge, not anything about
the situation the claim might be describing.

Answer SUPPORTED if every part of the claim is stated or directly implied by the span.
Answer PARTIAL if the span supports the general substance but not the precise wording -
for example, the span gives a range and the claim states one end of it, or the span is
about a closely related but not identical situation.
Answer NOT_SUPPORTED if the span does not address the claim, addresses a different claim,
or the claim adds specifics the span does not contain.

You will be given CLAIM and SPAN below, and nothing else - no ticket, no other passages,
no other claims. Judge only what is in front of you.

{ticket}
$prompt$,
    'gpt-4o-mini',
    '{"temperature": 0, "seed": 20260201}'::jsonb,
    $schema$
    {
      "type": "object",
      "additionalProperties": false,
      "required": ["verdict"],
      "properties": {
        "verdict": { "type": "string", "enum": ["SUPPORTED", "PARTIAL", "NOT_SUPPORTED"] }
      }
    }
    $schema$::jsonb,
    TRUE
);
