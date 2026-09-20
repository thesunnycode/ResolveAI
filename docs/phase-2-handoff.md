# Phase 2 handoff

Task 16. The eight things [doc 08](planning/08-TASK-BREAKDOWN-PHASE-3.md) now assumes are
true, each with the command that proves it rather than an assertion that it is.

| # | Item | Verified by | Result |
|---|---|---|---|
| 1 | `ops/verify-migrations.sh` green from an empty database | `bash ops/verify-migrations.sh` | ✅ 7/7 applied after `DROP DATABASE … WITH (FORCE)` |
| 2 | 39 tables, all indexes, all triggers present | same script's assertions | ✅ 39 tables · 23 partial indexes · 10 triggers · 2 HNSW · 3 GIN · 73 FKs |
| 3 | Structural guarantees hand-tested, positive paths confirmed | `ops/structural-guarantees.sql` | ✅ **ALL 10 GUARANTEES HOLD** — 6 negatives, 4 positives |
| 4 | `docs/design-trace.md` shows no unclosed gaps | [design-trace.md](design-trace.md) | ✅ 5 gaps closed by 11 endpoints + 1 payload change; 3 documented as deliberate |
| 5 | `docs/migrations.md` reserves every version | [migrations.md](migrations.md) | ✅ `V1`–`V14`, with `V8` and `V11` left as documented holes |
| 6 | Docs 04 and 05 carry the Task 2 resolutions | grep | ✅ `tenant_sequence` + `ticket_entity` in doc 04; doc 05 §3.8 and §3.9 added |
| 7 | `docs/openapi.yaml` validates; 12 endpoints fully specified | `bash ops/verify-openapi.sh` | ✅ Redocly clean · 64 operations · 348 `$ref`s resolve · 12/12 with examples |
| 8 | Postman collection committed and self-authenticating | `node ops/verify-postman.mjs` | ✅ 77 requests covering 64/64 operations · login stores both tokens · no secrets |

Everything above runs from one place:

```bash
bash ops/verify-stack.sh        # 7 containers healthy
bash ops/verify-migrations.sh   # schema from empty + 10 structural guarantees
bash ops/verify-openapi.sh      # Redocly + project assertions
node ops/verify-postman.mjs     # collection ↔ contract, both directions
```

---

## What Phase 2 changed about Phase 3

Two edits applied to [doc 08](planning/08-TASK-BREAKDOWN-PHASE-3.md):

**Task 5 shrank from 1.5 hours to 30 minutes.** It used to say *"copy the SQL from doc 04 into
seven files and fix the typos — expect two or three per file."* Phase 2 wrote those files and
applied them, and there were no typos to fix, because the SQL was **extracted programmatically
from doc 04 rather than retyped**. Transcription was the whole risk in that task, and removing
transcription removed it. What remains is configuration: point Flyway at
`classpath:db/migration`, set `ddl-auto: validate`, start the app.

**Task 6 was deleted and its successors renumbered.** It asked for the structural guarantees to
be hand-tested in psql. Phase 2 Task 12 automated that as `ops/structural-guarantees.sql`, and
in doing so widened it:

- three assertions became **ten**
- and, the part the hand-test was missing entirely, **four of them are positive cases** — close
  a segment then open another, detach then re-link, supersede then insert a successor, fire a
  different rung. **A constraint that blocks the legitimate path is as broken as one that
  permits the illegitimate one**, and a negative-only test cannot tell the difference between a
  correct partial index and a total one.

Phase 3 is now **14 tasks, 13.25–17.5 hours**, and its Day 2 has 45 minutes of slack.

Doc 08's old "most skipped task" was the hand-verification. The fix for a task people skip
turned out not to be a warning about skipping it — it was to stop asking for it by hand.

---

## Where the design changed in Phase 2, and why

Only the changes a Phase 3 reader needs to know about:

**The API grew from 49 to 64 operations.** Not scope creep — the [design trace](design-trace.md)
checked coverage *backwards* (does every table have a writer **and** a reader?) and found
`notification` had a writer and no reader, `attachment` a reader and no writer, and
`agent_profile.is_available` was read on every routing decision and writable by nobody. The
notification gap is the one worth remembering: the escalation ladder, one of the three signature
mechanisms, dutifully wrote a notification at every rung, and **no user could ever see one.**

**Two tables were folded into `V1`/`V2` that doc 04 did not have.** `tenant_sequence` and
`ticket_entity`. Their absence made the schema literally incomplete — `ticket.reference` is
`NOT NULL` with no way to generate a value.

**`POST /tickets` returns `201` in Phase 5 and `202` from Phase 6.** Documented in the spec
rather than treated as a bug to be discovered. Before the outbox exists there is no async triage,
so the representation returned *is* final and `202` would be a lie.

---

## Two things worth carrying forward

**My verification code was wrong more often than the thing it was verifying.** In Phase 1 the
forbidden-word checker produced 11 false positives out of 19 because `payment-service` contains
the substring "payment"; the entity-fidelity check flagged `₹54,181` as missing because the text
said `54181`. In Phase 2, `ops/verify-openapi.mjs` reported the spec clean and **Redocly then
found four genuine errors** — unquoted commas inside YAML flow mappings, where
`description: a, b` silently became two extra keys that a permissive parser accepted.
**Verification code needs the same scepticism as the thing it verifies**, which is the argument
for running a second, independent tool over the same artefact rather than trusting one you wrote
yourself.

**Newman caught a defect no static check could.** The health request carried a literal `..` path
segment; clients pass that through rather than normalising it away. Loading the collection in
the runtime that will actually execute it found in one run what reading it would not have.
