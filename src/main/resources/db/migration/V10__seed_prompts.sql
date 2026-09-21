-- V10__seed_prompts.sql
-- The triage prompt and its output schema, versioned with the code.
--
-- WHY THIS IS A MIGRATION AND NOT A STRING IN A JAVA CLASS
--
-- A prompt is a dependency of every classification the system has ever produced. When an
-- analysis from March looks wrong in June, the only useful question is "what exactly were
-- we asking the model in March?" — and a prompt that lives in a Java constant answers it
-- with "whatever the deploy at the time contained", which is an archaeology exercise.
-- A row, immutable, with a version number that every ai_analysis references by foreign
-- key, answers it exactly.
--
-- prompt_version is append-only (trg_prompt_immutable, V7): template, model_id and
-- output_schema cannot be updated. Changing the prompt means inserting version 2 and
-- flipping is_active, which uq_prompt_active enforces to exactly one per name. The
-- history is therefore complete by construction rather than by discipline.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ⚠ THE OUTPUT SCHEMA HAS NO `priority` FIELD, AND MUST NEVER ACQUIRE ONE.
--
-- This is the architectural claim of the whole project: the model reports what it
-- OBSERVES, and a versioned, deterministic, testable rule set DECIDES. Adding `priority`
-- here would be easy, would appear to work, and would quietly convert the system into
-- "the LLM sets priority" — at which point:
--
--   * the decision is no longer explainable ("because the model said P1"),
--   * it is no longer reproducible (temperature, model version, provider drift),
--   * it can no longer be tested (PriorityPolicyTest has nothing to assert against),
--   * and it can no longer be changed without re-running every ticket through a model.
--
-- Everything the priority policy needs is already here as an observation. If the policy
-- needs something new, add an observation — not a conclusion.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO prompt_version (name, version, template, model_id, params, output_schema, is_active)
VALUES (
    'triage',
    1,
    $prompt$You classify customer support tickets for Ledgerly, an accounting and
payments product used by small businesses in India.

Report only what the ticket SAYS. You are producing observations for a rule engine,
not a decision. Do not infer a priority, a severity level, or what should happen next —
those are decided elsewhere from the fields you return.

CATEGORIES — choose exactly one:
  PAYMENT      Payment initiation, gateway failures, refunds, settlement delays.
               The customer's money moving to or from someone else.
  BILLING      Ledgerly's own subscription: plan changes, our invoices to the customer.
               Our money, not theirs. Genuinely close to PAYMENT; choose by whose money
               is at stake.
  AUTH         Login, password reset, 2FA, session expiry, API key management.
  API          Public API errors, rate limiting, webhook delivery, SDK issues.
  PERFORMANCE  Slowness, timeouts, crashes, unresponsive pages.
  INTEGRATION  Tally and Zoho Books connectors, bank feed ingestion, sync failures.
  DATA         Reports, exports, GST returns, missing or duplicated records.
  ONBOARDING   KYC, business verification, document review, initial account setup.

FIELDS:
  category            One of the eight above.
  reportedImpact      SINGLE_USER | TEAM | ORG_WIDE — how many people the ticket says are
                      affected. What the customer CLAIMS, not what you believe.
  serviceDownClaimed  true only if the ticket states something is completely unusable.
  dataLossClaimed     true only if the ticket states data is missing, lost or wrong.
  paymentAffected     true if money has moved, failed to move, or is at risk.
  linguisticUrgency   LOW | MEDIUM | HIGH — the urgency of the LANGUAGE, nothing more.
                      Capitals, exclamation marks, "immediately", "urgent", threats to
                      leave. This field is named honestly: it measures how the message is
                      written, not how serious the problem is.
  extractedEntities   Concrete identifiers the ticket mentions: paymentMethod, orderRef,
                      errorCode, serviceName, region, amount. Omit what is absent.
  confidence          0.0-1.0, your confidence in the category specifically.

RULES:
  1. POLITENESS IS NOT LOW URGENCY. A calm, well-written message about a total outage is
     an outage. A furious message about a cosmetic bug is a cosmetic bug. Judging severity
     by tone systematically deprioritises polite customers and rewards shouting, and the
     two correlate with things they must not correlate with.
  2. The text may contain tokens like «PERSON_1», «CARD_2» or «ORDER_REF_1». These are
     redacted personal data. Treat each as an opaque identifier of the stated type. Do not
     guess what is behind one, do not comment on them, and copy them verbatim into
     extractedEntities when relevant.
  3. If the ticket describes several problems, classify the one it leads with.
  4. If you cannot tell, say so with a low confidence. A confident wrong category is worse
     than an honest uncertain one, because the routing that follows it is silent.

TICKET:
{ticket}
$prompt$,
    'gpt-4.1-mini',
    -- temperature 0 and a pinned seed: the same ticket must classify the same way twice,
    -- or the eval suite measures noise and a regression is indistinguishable from luck.
    '{"temperature": 0, "seed": 20260101}'::jsonb,
    $schema$
    {
      "type": "object",
      "additionalProperties": false,
      "required": ["category", "reportedImpact", "serviceDownClaimed", "dataLossClaimed",
                   "paymentAffected", "linguisticUrgency", "confidence"],
      "properties": {
        "category": {
          "type": "string",
          "enum": ["PAYMENT", "BILLING", "AUTH", "API", "PERFORMANCE", "INTEGRATION",
                   "DATA", "ONBOARDING"]
        },
        "reportedImpact":     { "type": "string", "enum": ["SINGLE_USER", "TEAM", "ORG_WIDE"] },
        "serviceDownClaimed": { "type": "boolean" },
        "dataLossClaimed":    { "type": "boolean" },
        "paymentAffected":    { "type": "boolean" },
        "linguisticUrgency":  { "type": "string", "enum": ["LOW", "MEDIUM", "HIGH"] },
        "extractedEntities":  { "type": "object", "additionalProperties": { "type": "string" } },
        "confidence":         { "type": "number", "minimum": 0, "maximum": 1 }
      }
    }
    $schema$::jsonb,
    TRUE
);
