# Corpus generator

Phase 1 Task 9. Generates the synthetic ticket corpus. **Runs offline; its output is
committed.** Not part of the Spring application.

```bash
npm install
node generate.js --count 10 --seed 1 --dry-run         # structure only, no LLM, free
node generate.js --count 200 --seed 42 \
     --out ../generated/tickets-starter.json           # the starter corpus
```

| Flag | Default | Meaning |
|---|---|---|
| `--count` | 200 | tickets to generate |
| `--seed` | 42 | PRNG seed — same seed gives byte-identical output |
| `--days` | 14 | spread arrivals over N days |
| `--batch` | 20 | scenarios per LLM call |
| `--dry-run` | off | structure + distribution report only, no LLM calls |
| `--no-cache` | off | ignore the on-disk response cache |
| `--out` | `../generated/tickets-starter.json` | output path |

## The design decision

**Label-first.** `lib/structure.js` picks category, priority, team, persona, entities,
arrival time and resolution duration *deterministically, before the model is called*.
The LLM then writes a ticket that expresses that scenario.

The labels are therefore **ground truth by construction** — they were not inferred from
the text, the text was generated from them. That is what makes 2,000 labelled examples
cost one generation run instead of 2,000 hand-labelling decisions.

The consequence, and it matters: these labels measure the *generator's* consistency, not
real-world accuracy. The 100 evaluation cases in `seed/eval/` must be labelled
**independently, by reading the ticket** — see doc 14 Task 15.

## Two instructions that are load-bearing

1. **"Never use the category name."** A `PAYMENT` ticket containing the word *payment*
   turns classification into keyword matching and makes both the eval suite and the storm
   demo meaningless. Enforced in the prompt and **checked after generation** — any leak
   is counted and recorded in `_violations` on the ticket.

2. **~50% of tickets carry no extractable entity.** A corpus where every ticket names a
   service makes entity extraction look trivially reliable and inflates the clustering
   boost in doc 12 T6 against reality.

## Files

```
generate.js         CLI, prompts, assembly, distribution report
lib/domain.js       loads seed/domain/*.yml — fails loudly on taxonomy mismatch
lib/rng.js          seeded PRNG (mulberry32 + Box-Muller) for reproducibility
lib/structure.js    ALL structural decisions; arrival shape; resolution durations
lib/llm.js          provider-agnostic call, retry with backoff, on-disk cache
```

`seed/.cache/` holds one file per batch keyed by content hash, so a crashed run resumes
free and a re-run with the same seed costs nothing.
