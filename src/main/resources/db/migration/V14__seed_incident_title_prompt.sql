-- V14__seed_incident_title_prompt.sql
-- incident_title@1 — doc 12 Task 10.
--
-- The model's entire contribution to an incident is this prompt's output: a title and a
-- two-sentence summary. Nothing about whether an incident EXISTS runs through a model -
-- CorrelationGate already decided that, deterministically, before this prompt is ever
-- called. IncidentTitleGenerator's template fallback means this prompt being unreachable
-- (policy, budget, provider outage) degrades the product by exactly one cosmetic field.
--
-- DO NOT EDIT once applied: Flyway checksums this file.

INSERT INTO prompt_version (name, version, template, model_id, params, output_schema, is_active)
VALUES (
    'incident_title',
    1,
    $prompt$A correlation system just grouped several support tickets that appear to
describe the same underlying problem. Write a short, specific title and a two-sentence
summary a team lead can read in the two seconds before deciding whether to confirm this
as one incident.

Base the title and summary ONLY on the ticket subjects and shared entities given below -
do not invent a root cause, a service name, or a scope the input does not support. If the
tickets look varied, say so in general terms ("payment failures at checkout") rather than
picking one ticket's specific wording as if it were the whole story.

Title: under 80 characters, plain language, no markdown, no trailing punctuation.
Summary: exactly two sentences, plain language, no markdown.

{ticket}
$prompt$,
    'gpt-4o-mini',
    '{"temperature": 0, "seed": 20260201}'::jsonb,
    $schema$
    {
      "type": "object",
      "additionalProperties": false,
      "required": ["title", "summary"],
      "properties": {
        "title": { "type": "string" },
        "summary": { "type": "string" }
      }
    }
    $schema$::jsonb,
    TRUE
);
