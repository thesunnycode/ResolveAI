-- V15__draft_prompt_customer_voice.sql
-- draft@2: draft@1 plus two rules about who the claims are written FOR.
--
-- Found in the flow audit: draft@1 never said its claims would be sent to the customer,
-- so the model restated the runbook as a procedure for staff ("If a customer reports the
-- portal spinning, they should try ...") and included steps only an engineer can take
-- ("... a redirect_uri mismatch in the billing service's auth-callback logs"). Both
-- claims were correctly grounded and correctly cited - verification passed - and "Use
-- reply" would still have sent an internal diagnostic to a customer in the third person.
--
-- Built from v1 by appending to the rule list, so every load-bearing rule (one assertion
-- per claim, cite by exact chunk id, no ungrounded numbers) is carried over unchanged,
-- and the model, params and output schema are identical: this changes voice and scope,
-- not the verification contract. entailment@1 is untouched - it judges a rephrased
-- claim against its span exactly as before.

UPDATE prompt_version SET is_active = FALSE WHERE name = 'draft' AND version = 1;

INSERT INTO prompt_version (name, version, template, model_id, params, output_schema, is_active)
SELECT 'draft',
       2,
       replace(template, E'\n{ticket}', $rules$
  7. WRITE TO THE CUSTOMER. Each claim is a sentence the agent will send to this
     customer, so address them directly as "you", in plain and friendly language:
     "Please try signing in from a private or incognito window", not "If a customer
     reports this, they should try an incognito window". Change the voice, never the
     facts - the claim must still say only what its passage says.
  8. CUSTOMER-SAFE CONTENT ONLY. Leave out anything only staff can do or see: log names,
     internal service or configuration names, restarts, internal diagnostics, and any
     other customer. If the passages' only answer is an internal step, do not write it
     as a claim - put what the agent should check in unresolvedAspects instead.

{ticket}$rules$),
       model_id,
       params,
       output_schema,
       TRUE
  FROM prompt_version
 WHERE name = 'draft' AND version = 1;
