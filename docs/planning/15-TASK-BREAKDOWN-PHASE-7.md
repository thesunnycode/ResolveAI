# 15 — Task Breakdown: Phase 7

> Skill: `task-breakdown` · The phase Path C cuts. Broken down so the decision is made against a real number.

**Phase:** Phase 7 — Knowledge, Retrieval & Citation-Enforced Drafting
**Goal:** A knowledge base with hybrid retrieval, and resolution drafts decomposed into individually-verified claims where unsupported claims are dropped and weak drafts are suppressed entirely.
**Tech stack:** Java 21 · Spring Boot 3.3.x · Spring AI · PostgreSQL 16 + pgvector + tsvector · Redis · WireMock

---

# The number, and a third option

**Phase 7 is 61 hours ≈ 17–18 days.** ([07](07-DEV-PHASES.md) said 6–8; my own Path C estimate assumed ~14. Both low.)

**Including it in full: ~126 working days ≈ 26 weeks.**

## But breaking it down reveals a split I could not see before

The phase is two halves with very different value-per-day, and they are cleanly separable:

| | Content | Effort | What it buys |
|---|---|---|---|
| **7A + 7B** | Knowledge ingestion, chunking, **hybrid retrieval with RRF**, retrieval eval suite, `ef_search` tuning, the `explain=true` score breakdown | **~10 days** | The entire hybrid-search story: pgvector + tsvector + Reciprocal Rank Fusion, Recall@5/MRR measured, thresholds tuned against data. **The best interview material in the phase.** |
| **7C + 7D + 7E** | Claim-structured generation, numeric pre-filter, entailment verifier, coverage suppression, grounding + refusal suites, resolved-ticket indexing | **~8 days** | Citation-enforced drafting — signature mechanism #3. Also the part that most resembles every other AI portfolio project. |

**So there is a third path I should have offered earlier:**

### Path C+ — add 7A/7B only

**+10 days → ~118 days ≈ 24 weeks.**

You get: `GET /knowledge/search?explain=true` returning per-result `lexicalRank`, `vectorRank` and `rrfScore`, so when an interviewer asks *"why not just use semantic search?"* the answer is a response body showing a chunk that ranked **1st lexically and 3rd by vector** while another was the reverse — neither method alone surfaces both. Plus a measured retrieval eval and a tuning story.

You skip: AI writing customer-facing text. Which also means **you never have to defend against the chatbot pattern-match at all** — the AI in your project reads, classifies, correlates and retrieves, and never writes to a customer.

That is arguably a *cleaner* architectural story than the full phase, not merely a cheaper one.

### The three options, with what each costs

| Path | Weeks | AI story |
|---|---|---|
| **C** — skip Phase 7 entirely | 22–23 | Triage signals + deterministic policy + incident correlation |
| **C+** — add 7A/7B *(recommended)* | ~24 | + hybrid retrieval with RRF, measured and tuned |
| **Full** — all of Phase 7 | ~26 | + citation-enforced drafting |

**My recommendation is now C+, not C.** Two extra weeks for the hybrid-retrieval story is good value; the further two weeks for drafting is the weakest ratio in the project.

**It is your call, and the milestone map in [12](12-TASK-BREAKDOWN-PHASE-8.md) still applies either way** — you are competitive from week 9 regardless, and Phase 7 slots in after Phase 6 without blocking Phase 8.

## One dependency I removed and now have to put back

When I cut Phase 7, I also cut the knowledge-base corpus from [14 Task 9](14-TASK-BREAKDOWN-PHASE-1.md) — the starter corpus generates tickets only. **If you build Phase 7, Task 2 below regenerates that corpus**, and it is 3 hours you would otherwise have spent in Phase 1.

---

## Sub-phase structure

| | Sub-phase | Tasks | Effort |
|---|---|---|---|
| **7A** | Knowledge ingestion | 1–6 | ~14.5 h ≈ 4 days |
| **7B** | Hybrid retrieval | 7–11 | ~13 h ≈ 4 days |
| *— Path C+ stops here —* | | | |
| **7C** | Citation-enforced drafting | 12–21 | ~20.5 h ≈ 6 days |
| **7D** | Grounding & refusal evaluation | 22–24 | ~7 h ≈ 2 days |
| **7E** | Resolved-ticket indexing & close | 25–27 | ~6 h ≈ 2 days |

**No new migrations.** `knowledge_document`, `knowledge_chunk`, `draft`, `draft_claim`, `draft_claim_citation` and `agent_draft_action` were all created in [04](04-DATABASE-SCHEMA.md)'s `V2` and `V4`.

---

# PHASE 7A — Knowledge Ingestion

## TASK 1 — Create the knowledge entities and repositories

**📝 Description**
`KnowledgeDocument` and `KnowledgeChunk` in `com.resolveai.knowledge.domain`, per [04 §Group 5](04-DATABASE-SCHEMA.md).

Mapping notes:
- `@TenantId` on both. **`knowledge_chunk.tenant_id` is denormalised deliberately** so the retrieval pre-filter is single-table — see Task 8.
- `text_tsv` is trigger-maintained (`trg_chunk_tsv`) — **do not map it.** Hibernate would fight the trigger.
- `embedding` is `vector(768)`. Unlike `ticket.embedding` this one *is* read through JPA for the chunk detail view, so wire the pgvector Hibernate type here.
- Soft delete on `KnowledgeDocument` via `@SQLDelete` + `@SQLRestriction`.
- `kb_version` is a `BIGINT` bumped on any change — it is part of every retrieval cache key, which is how a KB edit invalidates caches with no explicit eviction.

Repositories: `findPendingIndexing(tenantId)` using `idx_kb_pending`, `findByContentSha256`, `deleteChunksByDocumentId`.

**⛓️ Dependencies** None.
**✅ Expected Output** Entities compile; `ddl-auto: validate` passes. A chunk round-trips its embedding through JPA.
**⏱️** 2 hours.

---

## TASK 2 — Generate the knowledge-base corpus

**📝 Description**
**The dependency removed from [14](14-TASK-BREAKDOWN-PHASE-1.md) when Phase 7 was cut.** Extend the Phase 1 generator to produce, for the Ledgerly domain defined in [14 Task 7](14-TASK-BREAKDOWN-PHASE-1.md):

- **60 knowledge articles** — customer-facing, 300–800 words, with markdown headings. Two to five per category.
- **15 runbooks** — internal, procedural, 400–1,200 words, referencing the service names and error codes from `seed/domain/`.

**The articles must use the same vocabulary as the tickets.** A ticket saying `ERR_UPI_COLLECT_FAILED` and an article saying "UPI collection failure" retrieves poorly, and you would spend a day tuning thresholds against a mismatch that is actually a corpus bug. Generate both from the same `error-codes.yml`.

**Deliberately leave gaps.** Pick five ticket categories and write *no* article covering a specific common scenario in each. Those gaps are what Task 23's refusal suite tests, and a KB with complete coverage makes the suppression path untestable.

Commit to `seed/generated/knowledge.json` with the coverage gaps documented in `seed/CORPUS.md`.

**⛓️ Dependencies** [14 Tasks 7–9](14-TASK-BREAKDOWN-PHASE-1.md).
**✅ Expected Output** 75 documents committed. Vocabulary overlap with the ticket corpus verified. Five deliberate coverage gaps documented.
**⏱️** 3 hours.

---

## TASK 3 — Implement the chunker

**📝 Description**
`DocumentChunker.chunk(document) → List<ChunkSpec>`, target ~400 tokens with ~50 overlap.

Split hierarchically, **never mid-sentence**: markdown headings → paragraphs → sentences. A chunk that ends halfway through a clause retrieves badly and cites worse.

Three requirements that are easy to miss and expensive later:

1. **Record `char_start` and `char_end` into the parent document.** [04](04-DATABASE-SCHEMA.md) has the columns and Task 19's citation popover needs them to highlight the exact supporting span. Retrofitting offsets means reindexing everything.
2. **Prefix each chunk's *embedded* text with its heading path** — `"UPI payment failures > Reversal timeline: <chunk text>"`. Store the raw text, embed the prefixed form. A chunk reading "This usually takes 5–7 business days" is nearly meaningless in isolation; with its heading path it is retrievable. This is the single highest-leverage retrieval improvement available and it costs ten lines.
3. Token counting via the provider's tokenizer, not `text.length() / 4`. A 400-"token" chunk that is actually 600 blows your context budget in Task 13.

**⛓️ Dependencies** Task 1.
**✅ Expected Output** A 2,000-word article with four headings produces 6–8 chunks, none splitting a sentence, each carrying correct offsets and a heading-path prefix in its embedded form.
**⏱️** 3 hours.

---

## TASK 4 — Implement document upload and the `IndexWorker`

**📝 Description**
`POST /api/v1/knowledge/documents` (ADMIN) → validate → compute `content_sha256` → persist with `indexed_at = null` → publish a `KNOWLEDGE_DOCUMENT_ADDED` outbox event **in the same transaction** → `202`.

`IndexWorker` (an `outbox` `Worker` from [11 Task 3](11-TASK-BREAKDOWN-PHASE-6.md)):
```
tx1: load document, mark indexing
no tx: chunk → embed each chunk via EmbeddingService (content-hash cached)
tx2: delete existing chunks, insert new ones, set indexed_at, bump kb_version
```

**Same three-phase structure as `TriageWorker`, for the same reason** — embedding a 40-chunk runbook is 40 network calls, and holding a connection across them would exhaust the pool. Set `visibilityTimeout` to 10 minutes; a large document legitimately takes minutes.

Delete-then-insert rather than diffing chunks: chunk boundaries shift when text changes, so a diff is meaningless. Do both inside `tx2` so a reader never sees a half-indexed document.

**⛓️ Dependencies** Tasks 1, 3, and [11 Tasks 3, 19](11-TASK-BREAKDOWN-PHASE-6.md).
**✅ Expected Output** Uploading a runbook returns `202`; chunks appear within ~30 s with `indexed_at` set and `kb_version` bumped. Re-uploading replaces chunks atomically.
**⏱️** 2.5 hours.

---

## TASK 5 — Implement content-hash skip and reindex

**📝 Description**
`POST /api/v1/knowledge/reindex` (ADMIN) → `202`, with `{force: false}` by default.

Non-forced: skip every document whose `content_sha256` is unchanged **and** whose chunks were embedded with the current model id. Report `documentsQueued`, `documentsSkipped`, `skipReason` and `estimatedCostMicros` in the response, per [05 §3.6](05-API-CONTRACT.md).

**Reporting the estimated cost up front is the point.** A forced full reindex on 75 documents is the single easiest way to burn your monthly AI budget by accident, and the number appears before you commit to it.

Store the embedding model id on the chunk so a model change is detected as a reason to re-embed — otherwise switching models silently leaves you with a mixed-dimension index, which produces nonsense similarity scores and no error.

**⛓️ Dependencies** Task 4.
**✅ Expected Output** Reindexing after changing one document queues 1 and skips 74. `force: true` queues all 75 and reports a cost estimate first.
**⏱️** 2 hours.

---

## TASK 6 — Implement the knowledge CRUD endpoints

**📝 Description**
- `GET /knowledge/documents` — offset-paginated (small, slow-changing, admin-facing; [05 §4](05-API-CONTRACT.md) has the offset-vs-cursor reasoning), filterable by `source`
- `GET /knowledge/documents/{id}` — detail with chunks, token counts and offsets
- `DELETE /knowledge/documents/{id}` — **soft delete**, then remove chunks from the index and bump `kb_version`

Soft-delete the document but **hard-delete its chunks**. The document row must survive because `draft_claim_citation.chunk_id` is `ON DELETE RESTRICT` — but that constraint is exactly why you must check for citing drafts first and return `409 DOCUMENT_CITED` rather than letting the FK throw a raw integrity error at the user.

Add both endpoints to the Phase 4 cross-tenant `@MethodSource`.

**⛓️ Dependencies** Task 4.
**✅ Expected Output** List, detail and delete work. Deleting a cited document returns `409` with the citing draft ids. Deletion removes chunks from retrieval within one `kb_version` bump.
**⏱️** 2 hours.

---

# PHASE 7B — Hybrid Retrieval

## TASK 7 — Implement the RRF hybrid query

**📝 Description**
**The centrepiece of the phase.** One SQL statement, two CTEs, fused by Reciprocal Rank Fusion:

```sql
WITH lex AS (
  SELECT id, ROW_NUMBER() OVER (ORDER BY ts_rank_cd(text_tsv, q) DESC) AS rnk
    FROM knowledge_chunk, plainto_tsquery('english', :query) q
   WHERE tenant_id = :tenantId AND text_tsv @@ q
   ORDER BY ts_rank_cd(text_tsv, q) DESC
   LIMIT 50
),
vec AS (
  SELECT id, ROW_NUMBER() OVER (ORDER BY embedding <=> :queryVec) AS rnk
    FROM knowledge_chunk
   WHERE tenant_id = :tenantId
   ORDER BY embedding <=> :queryVec
   LIMIT 50
)
SELECT c.*,
       COALESCE(1.0/(60 + lex.rnk), 0) AS lex_contrib,
       COALESCE(1.0/(60 + vec.rnk), 0) AS vec_contrib,
       COALESCE(1.0/(60 + lex.rnk), 0) + COALESCE(1.0/(60 + vec.rnk), 0) AS rrf
  FROM knowledge_chunk c
  LEFT JOIN lex ON lex.id = c.id
  LEFT JOIN vec ON vec.id = c.id
 WHERE lex.id IS NOT NULL OR vec.id IS NOT NULL
 ORDER BY rrf DESC
 LIMIT :k;
```

**Why RRF rather than normalising and adding the scores — and this is the answer to the best question an interviewer can ask here:** `ts_rank_cd` returns roughly 0–1 with a distribution that depends on document length; cosine distance returns 0–2 with a completely different shape. **They are not comparable, and any normalisation you invent is a hyperparameter you cannot justify.** RRF discards the scores entirely and fuses on *rank*, which is unit-free. The constant 60 is the standard damping term from the original paper and it is not worth tuning.

Return the per-source contributions, not just the total — Task 9 exposes them.

**⛓️ Dependencies** Tasks 1, 4.
**✅ Expected Output** A query returns results ranked by RRF with both contributions populated. A term appearing lexically but not semantically still surfaces, and vice versa.
**⏱️** 3.5 hours.

---

## TASK 8 — Implement source-tier weighting and pre-filtering

**📝 Description**
Apply a tier multiplier to the RRF score: `RUNBOOK` and `ARTICLE` are `AUTHORITATIVE` (×1.15); `RESOLVED_TICKET` is `PRECEDENT` (×1.0). Expose the tier in results so the UI can show *"the runbook says"* differently from *"someone did this once"*.

**Then the part that matters more: verify the tenant filter runs *before* both scans, not after.**

`EXPLAIN ANALYZE` the query and confirm `tenant_id = :t` is applied inside each CTE's `WHERE`, before the `LIMIT 50`. **Post-filtering silently shrinks your effective k** — if 30 of the top 50 vector hits belong to another tenant, you filter down to 20 and recall drops, with no error and no way to notice without an eval suite. This is the single most likely silent bug in retrieval.

Add optional `productArea` and `source` filters on the same principle.

**⛓️ Dependencies** Task 7.
**✅ Expected Output** `EXPLAIN` shows the tenant predicate inside both CTEs. Two tenants with identical KB content return disjoint results. Tier boost measurably reorders results where relevance is close.
**⏱️** 2 hours.

---

## TASK 9 — Implement `GET /knowledge/search` with `explain`

**📝 Description**
Per [05 §3.6](05-API-CONTRACT.md). With `explain=true`, each result carries `lexicalRank`, `lexicalScore`, `vectorRank`, `vectorScore`, `rrfScore`, `tierBoost` and `finalScore`, plus top-level `timings` and `kbVersion`.

Cache results in Redis under `ret:{sha256(query)}:{kbVersion}` with a 1-hour TTL — **the `kbVersion` in the key means any KB edit makes old entries unreachable with no explicit eviction.**

**This endpoint is a debugging tool wearing a search box, and it is the best artifact in the phase.** When asked *"why not just semantic search?"*, you show a response where chunk A ranked **1st lexically and 3rd by vector** while chunk B was **7th lexically and 1st by vector** — neither method alone puts both in the top 3. That is the argument for hybrid search as *data* rather than as an assertion, and it is worth more than any amount of explanation.

**⛓️ Dependencies** Task 8.
**✅ Expected Output** `explain=true` returns the full breakdown. A repeat query hits cache. Editing a document makes the cached entry unreachable.
**⏱️** 2 hours.

---

## TASK 10 — Build the retrieval evaluation suite

**📝 Description**
60–80 labelled `(query, relevantChunkIds)` cases, built with the **label-first** trick from [14 Task 9](14-TASK-BREAKDOWN-PHASE-1.md):

1. For each knowledge article, ask the model to write 1–2 questions **that article answers.** The source chunk is the label, by construction.
2. Add 20 hand-written queries using *ticket* phrasing rather than article phrasing — *"money gone but no invoice"* rather than *"UPI settlement delay"*. These are the realistic ones.
3. Add 10 queries with an exact identifier (`ERR_UPI_COLLECT_FAILED`) — **these are the ones pure vector search fails**, and they are why hybrid exists.

Compute **Recall@5, Precision@5, MRR and nDCG@10**, and report them **separately for the three groups.** The aggregate hides the finding; the per-group split is where you see that dense retrieval wins on paraphrase and loses on identifiers.

Persist as `eval_case` rows with suite `RETRIEVAL`.

**⛓️ Dependencies** Tasks 2, 9.
**✅ Expected Output** 60–80 cases committed. Metrics reported per group. The identifier group demonstrably scores worse on vector-only than on hybrid.
**⏱️** 3 hours.

---

## TASK 11 — Tune `ef_search` and the fusion against the eval set

**📝 Description**
**A measurement task with no code deliverable**, like [12 Task 11](12-TASK-BREAKDOWN-PHASE-8.md). Budget it.

Sweep and record a table for each:
- `hnsw.ef_search` ∈ {40, 64, 100, 200, 400} — Recall@5 and p95 latency
- Candidate depth per CTE ∈ {20, 50, 100}
- Tier boost ∈ {1.0, 1.1, 1.15, 1.3}
- **Lexical-only vs vector-only vs hybrid** — the headline comparison

Commit the tables to the README.

**The three-way comparison is the deliverable.** *"Hybrid improved Recall@5 by N points over vector-only, and the entire gain came from the identifier query group"* is a specific, measured, defensible claim. *"I used hybrid search because it's better"* is not, and it is what most candidates say.

Also decide about reranking **by measuring**: rerank the top 20 with a cheap LLM, measure the Recall@5 change and the added latency. **If it adds 300 ms and moves recall by under a point, remove it and say so.** *"I implemented reranking, measured it, and removed it because it did not pay for its latency"* is a stronger answer than having it.

**⛓️ Dependencies** Task 10.
**✅ Expected Output** Four tables in the README. Chosen values in config. A documented reranking decision either way.
**⏱️** 2.5 hours.

---

### ✅ 7B checkpoint — **Path C+ stops here**

- [ ] 75 documents ingested and chunked with offsets and heading prefixes
- [ ] Hybrid RRF query returns fused results with per-source contributions
- [ ] Tenant pre-filter verified inside both CTEs by `EXPLAIN`
- [ ] `explain=true` shows the full score breakdown
- [ ] Retrieval eval reports Recall@5/MRR per query group
- [ ] Tuning tables committed; hybrid-vs-single-method gain quantified

**Two resume-grade claims from these 10 days:** hybrid retrieval with RRF in PostgreSQL, measured against a labelled set; and `ef_search` tuned against a recall curve rather than guessed.

---

# PHASE 7C — Citation-Enforced Drafting

## TASK 12 — Seed the `draft@1` and `entailment@1` prompts

**📝 Description**
Two `PromptVersion` rows via `V14__seed_draft_prompts.sql`.

**`draft@1`** must produce **claims, not prose**:
```json
{ "claims": [ { "text": "...", "citationIds": ["chunk:33401"] } ],
  "suggestedTone": "APOLOGETIC|NEUTRAL|REASSURING",
  "unresolvedAspects": ["..."] }
```
Instructions: one verifiable assertion per claim; every claim cites at least one retrieved chunk; **never state a number, date or amount not present in a cited chunk**; list anything the ticket asks that the context does not cover.

**`entailment@1`** is deliberately minimal — given **only** a claim and one cited span, return `SUPPORTED | PARTIAL | NOT_SUPPORTED`. **It must not see the ticket, the other chunks, or the rest of the draft.** A verifier with access to the full context will rationalise support from material the claim did not cite, which defeats the entire mechanism.

**⛓️ Dependencies** [11 Task 14](11-TASK-BREAKDOWN-PHASE-6.md).
**✅ Expected Output** Both seeded and active. Schema-constrained parsing works against a WireMock fixture.
**⏱️** 2 hours.

---

## TASK 13 — Implement claim-structured generation

**📝 Description**
`ClaimGenerator.generate(ticket, retrievedChunks, instruction) → List<RawClaim>` via `ModelRouter` with `BeanOutputConverter`, temperature 0.

Build the context: top-k chunks (k from Task 11), each labelled with a stable `chunk:{id}` the model cites by. Include the ticket subject and body **redacted** — placeholders from [11 Task 12](11-TASK-BREAKDOWN-PHASE-6.md) — and rehydrate only when rendering for the agent.

Budget the context against the model's window using real token counts from Task 3, and drop the lowest-ranked chunks if over. **Log when you drop** — silent truncation makes a retrieval bug look like a generation bug.

**Reject the whole generation if any `citationIds` references a chunk not in the provided context.** A model citing `chunk:99999` has fabricated a citation, and a partial result there is worse than none.

**⛓️ Dependencies** Tasks 9, 12.
**✅ Expected Output** A payment ticket with three retrieved chunks produces 2–4 claims, each citing a real chunk id. A fabricated citation id fails the generation.
**⏱️** 3 hours.

---

## TASK 14 — Implement the deterministic numeric pre-filter

**📝 Description**
`NumericVerifier.verify(claim, citedSpans) → PASS | FAIL(reason)`.

Extract from the claim text: integers, decimals, currency amounts (₹, Rs, INR, with separators), percentages, dates in every format the corpus uses, durations ("5–7 business days"), order/invoice references, and error codes. **Every one must appear in at least one cited span**, normalised for formatting — `₹2,499` matches `2499`, `5-7` matches `5–7`.

Fail → `verdict = FAILED_NUMERIC_CHECK`, `kept = false`, `verifier_model = null`.

**Run this first, before the entailment check.** It is free, it is deterministic, and it catches the most damaging failure mode — an invented refund amount or a promised date. A model that hallucinates *"your refund of ₹2,499 will be credited by 24 September"* produces a claim a customer will hold you to, and this filter kills it at zero cost.

Handle the normalisation carefully: an over-strict matcher rejecting `₹2,499` against `2499` would drop valid claims and make the feature useless. Test both directions.

**⛓️ Dependencies** Task 13.
**✅ Expected Output** A claim with a fabricated amount fails; the same claim with an amount present in its cited span passes. Formatting variants match. Zero LLM calls.
**⏱️** 2.5 hours.

---

## TASK 15 — Implement the entailment verifier with caching

**📝 Description**
For each claim surviving Task 14: call `entailment@1` with **only** the claim and its cited span. Cache by `sha256(claim + span + promptVersion)` with a 7-day TTL — the same claim–span pair recurs constantly across drafts and each verdict is an LLM call.

Run the calls concurrently on virtual threads, **outside any transaction** — a five-claim draft is five sequential 800 ms calls otherwise.

`NOT_SUPPORTED` → dropped. `PARTIAL` → kept, but flagged in the response so the agent knows to check it.

**Treat `PARTIAL` as a first-class outcome rather than rounding it to supported or unsupported.** Most claims in practice are partially supported — the span backs the substance but not the precise phrasing — and collapsing that to a binary either drops useful drafts or ships unverified ones.

**⛓️ Dependencies** Task 14.
**✅ Expected Output** A supported claim passes; an unrelated claim is dropped. Cache hit avoids the call. Five claims verify in roughly the time of one.
**⏱️** 2.5 hours.

---

## TASK 16 — Implement coverage computation and suppression

**📝 Description**
`coverage = (SUPPORTED + 0.5 × PARTIAL) / totalClaims`.

```
coverage >= 0.8                   → SHOWN, assembledText built from kept claims
coverage <  0.8                   → SUPPRESSED_LOW_COVERAGE, assembledText = null
no chunks retrieved at all        → SUPPRESSED_NO_EVIDENCE
```

Suppressed drafts return `recommendation: "ESCALATE_TO_HUMAN"` and a `suppressionReason` naming the numbers.

**`assembledText` is `null`, never a hedged partial draft.** The design position is that **a confidently wrong answer is worse than no answer** — an agent shown a weak draft will edit it rather than write from scratch, which is exactly the wrong anchor. Task 23's refusal suite asserts this fires 100% of the time on the no-coverage fixtures.

Make the 0.8 threshold configurable and tune it in Task 22 against the grounding set.

**⛓️ Dependencies** Task 15.
**✅ Expected Output** A three-claim draft with all supported → `SHOWN` at coverage 1.0. One of three supported → `SUPPRESSED_LOW_COVERAGE` at 0.33 with `assembledText: null`.
**⏱️** 2 hours.

---

## TASK 17 — Implement `unresolvedAspects`

**📝 Description**
Persist the model's `unresolvedAspects` to `draft_unresolved_aspect`, and **add deterministic ones**: any question-shaped sentence in the ticket whose key terms appear in no retrieved chunk.

Return them on the draft even when it is suppressed — **especially** then, since a suppressed draft with *"no KB coverage for refunds on cancelled subscriptions"* tells the agent exactly what to go and find out.

**This is the most useful field in the whole response and the easiest to leave out.** A support agent's real question is not *"what does the KB say"* — it is *"what am I going to have to figure out myself?"* It also feeds knowledge-gap mining, which is the clearest v2 feature in this project.

**⛓️ Dependencies** Task 16.
**✅ Expected Output** A ticket asking two things where the KB covers one returns one unresolved aspect. Suppressed drafts still populate the field.
**⏱️** 1.5 hours.

---

## TASK 18 — Implement `POST /tickets/{id}/drafts` and the `DraftWorker`

**📝 Description**
`POST` (AGENT+) → `202` with `draftId` and `estimatedSeconds`. `409 DRAFT_IN_PROGRESS` if one is pending; `422 NO_KNOWLEDGE_BASE` if the tenant has no indexed documents; `402 AI_BUDGET_EXHAUSTED`; `503 AI_DISABLED_BY_POLICY`.

`DraftWorker` assembles the pipeline in the standard three phases:
```
tx1:    load ticket, policy, budget
no tx:  embed query → hybrid retrieve → generate claims → numeric filter
        → entailment (concurrent) → coverage
tx2:    persist draft, claims, citations, unresolved aspects
```

Rate-limit 10/min per agent. `402` for budget exhaustion is deliberate and distinct from `429` — a client should retry after a `429` and must not after a `402`.

**⛓️ Dependencies** Tasks 13–17, [11 Task 3](11-TASK-BREAKDOWN-PHASE-6.md).
**✅ Expected Output** Requesting a draft returns `202`; it completes in 10–25 s. A tenant with no KB gets `422`. Connection usage stays ≤ 2 during generation.
**⏱️** 2.5 hours.

---

## TASK 19 — Implement `GET /drafts/{id}`

**📝 Description**
Per [05 §3.3](05-API-CONTRACT.md). Returns **every claim, including dropped ones**, with verdict, rejection reason, citations with spans and snippets, coverage, `assembledText` (or null), `unresolvedAspects`, `sources` with tiers, and cost/latency.

**Returning the dropped claims is the single most important decision in this endpoint.** An agent who sees the system caught the model inventing *"₹2,499 by 24 September"* trusts the two claims it kept. Hide the rejection and the verification mechanism is invisible — you would have built the most interesting thing in the project and then concealed it.

Rehydrate PII placeholders when rendering for the agent. Include the exact cited span text so the UI popover in [06 §4.2](06-UI-UX-DESIGN.md) needs no second call.

**⛓️ Dependencies** Task 18.
**✅ Expected Output** A `SHOWN` draft returns kept and dropped claims with reasons. A suppressed draft returns `assembledText: null` with `recommendation`. Spans are present and correct.
**⏱️** 2 hours.

---

## TASK 20 — Implement `POST /drafts/{id}/action`

**📝 Description**
Record `SENT_AS_IS | EDITED | DISCARDED`, with **server-computed** Levenshtein distance between `assembledText` and `finalText` — never trusted from the client. `409 ACTION_ALREADY_RECORDED` on a second call, backed by `uq_draft_action`.

**This is the best quality metric in the system and it costs nothing.** `SENT_AS_IS` rate and median edit distance are measured from real behaviour, not asserted by another model. Make the frontend call it on **all three** outcomes — discards are the most informative signal and the easiest to forget to capture.

Emit both as Micrometer metrics.

**⛓️ Dependencies** Task 19.
**✅ Expected Output** All three actions record correctly with server-side edit distance. A second call returns `409`. Metrics exposed.
**⏱️** 1.5 hours.

---

## TASK 21 — Wire `fromDraftId` validation into messages

**📝 Description**
Fill the gap left in [10 Task 14](10-TASK-BREAKDOWN-PHASE-5.md), where `fromDraftId` was accepted and stored unvalidated: the draft must exist, belong to this ticket, and have status `SHOWN`. Otherwise `422 INVALID_DRAFT_REFERENCE`.

When a message carries a valid `fromDraftId` and no action has been recorded yet, **record the action automatically** by comparing the message body to `assembledText` — identical → `SENT_AS_IS`, otherwise `EDITED` with the distance.

**Automatic capture is what makes the metric trustworthy.** Relying on the frontend to call `/action` means it gets missed, and a `SENT_AS_IS` rate computed from partial data is worse than no rate at all.

**⛓️ Dependencies** Task 20.
**✅ Expected Output** Sending a message with a valid draft reference records the action automatically. An invalid reference → `422`. Editing before sending records `EDITED` with the correct distance.
**⏱️** 1 hour.

---

# PHASE 7D — Grounding & Refusal Evaluation

## TASK 22 — Build the grounding evaluation suite

**📝 Description**
~150 hand-labelled claims. Generate drafts for 40 tickets, then for each claim label `SUPPORTED | PARTIAL | NOT_SUPPORTED` **by reading the claim against its cited span yourself.**

Compute and report **both**:
- **Claim-level groundedness** — supported claims ÷ total claims
- **Response-level groundedness** — drafts where every claim is supported ÷ total drafts
- Citation precision — cited spans that actually support their claim

**Report both numbers side by side, because the gap between them is the finding.** This targets the documented 2026 case where a groundedness metric read **0.92** while a claim-level audit found hallucinations in **30%** of responses — both correct, because the metric averaged support per *response* while the audit decomposed into *claims*. Your README paragraph explaining that is worth more than either number.

Also measure the numeric pre-filter's contribution separately: run the suite with it disabled and report how many fabrications it catches for zero LLM cost.

**⛓️ Dependencies** Task 19.
**✅ Expected Output** 150 labelled claims committed. Both groundedness numbers reported with the gap discussed. The pre-filter's standalone contribution quantified.
**⏱️** 3.5 hours.

---

## TASK 23 — Build the refusal suite

**📝 Description**
20 tickets whose answers are **deliberately absent** from the KB — built from the five coverage gaps planted in Task 2, plus questions adjacent to but not covered by existing articles.

**The correct behaviour for all 20 is suppression.** Assert `status ∈ {SUPPRESSED_LOW_COVERAGE, SUPPRESSED_NO_EVIDENCE}`, `assembledText == null`, and `recommendation == "ESCALATE_TO_HUMAN"`.

**The gate requires 100%, not a baseline.** Every other suite gates on regression against a committed number; this one gates on an absolute. A system that confidently answers a question it has no evidence for has failed in a way that a 95% pass rate does not capture — the 5% is precisely the dangerous case.

Add 5 near-miss cases where coverage lands just above threshold, to confirm you are not suppressing everything.

**⛓️ Dependencies** Tasks 2, 16.
**✅ Expected Output** All 20 suppress. All 5 near-misses show. The suite fails the build if a single refusal case produces a draft.
**⏱️** 2 hours.

---

## TASK 24 — Wire both suites into CI with baselines

**📝 Description**
Extend the [11 Task 34](11-TASK-BREAKDOWN-PHASE-6.md) `EvalRunner` with `RETRIEVAL`, `GROUNDING` and `REFUSAL`. Commit baselines as a file; CI fails on regression, and on **any** refusal failure.

Surface all five suites in `GET /admin/eval/runs`.

**Then break each one on purpose and watch CI go red.** Degrade the draft prompt → grounding drops. Remove the numeric filter → grounding drops. Lower the coverage threshold to 0.1 → refusal fails. Revert each. **A gate you have never seen fail is a gate you do not know works** — this is the fourth time that sentence appears in this folder, because it is the instruction people skip.

**⛓️ Dependencies** Tasks 10, 22, 23.
**✅ Expected Output** Five suites in CI. Each deliberately broken and observed red, then reverted.
**⏱️** 1.5 hours.

---

# PHASE 7E — Resolved-Ticket Indexing & Close

## TASK 25 — Implement resolved-ticket indexing

**📝 Description**
Fill the TODO from [10 Task 16](10-TASK-BREAKDOWN-PHASE-5.md): on resolve, publish a `TICKET_RESOLVED` event; a worker creates a `knowledge_document` with `source = RESOLVED_TICKET` from the subject, the problem statement and the resolution.

**Three quality gates — indexing every resolved ticket would poison retrieval:**
1. Resolution text at least 100 characters — one-liners like "fixed" teach nothing
2. Not linked to an incident — 38 tickets from one outage would add 38 near-duplicate documents
3. Not reopened — a resolution that did not hold is a bad precedent

**PII must be redacted before indexing.** A resolved ticket contains customer names and order references, and indexing the raw text would leak them into every future draft that retrieves it. Reuse [11 Task 12](11-TASK-BREAKDOWN-PHASE-6.md) and store the redacted form.

Tier as `PRECEDENT`, never `AUTHORITATIVE`.

**⛓️ Dependencies** Tasks 4, 8.
**✅ Expected Output** Resolving a substantive ticket adds a KB document within ~30 s, PII-redacted. Thin, incident-linked and reopened tickets are skipped with a logged reason.
**⏱️** 2 hours.

---

## TASK 26 — Write the drafting test suite

**📝 Description**
1. **Retrieval determinism** — the same query twice returns an identical ranking
2. **Numeric filter** — a claim with a fabricated amount is dropped with zero LLM calls; assert via WireMock request count
3. **Entailment drops** — an unsupported claim is dropped and appears in the response with its reason
4. **Suppression** — a low-coverage draft returns `assembledText: null`
5. **Citation integrity** — every kept claim has at least one citation resolving to a real chunk with valid offsets
6. **Concurrent draft requests** — two requests for one ticket → one `202`, one `409`
7. **Provider failure mid-verification** — the draft ends `FAILED`, never partially verified and shown

Test 7 is the important one: a draft where two of five claims were verified before the provider died must **not** be shown with a coverage computed from three unverified claims.

**⛓️ Dependencies** Tasks 18–21.
**✅ Expected Output** All seven green over 10 runs.
**⏱️** 2.5 hours.

---

## TASK 27 — Clean up, verify and tag Phase 7

**📝 Description**
Full clean verify from empty. README section "Knowledge & citation-enforced drafting": why hybrid rather than semantic-only, **with the Task 11 comparison table**; why RRF fuses ranks and not scores; the claim-level verification pipeline; **why claim-level groundedness rather than response-level**, with the Task 22 gap; and why a suppressed draft returns null rather than a hedge.

Record the third demo GIF for [13 Task 22](13-TASK-BREAKDOWN-PHASE-9-10.md): a draft rendering with two claims kept and one struck through with its rejection reason. **That GIF replaces the priority-rationale one** as the clearest single image of the project's argument.

Extend Postman. Update [13 T24](13-TASK-BREAKDOWN-PHASE-9-10.md)'s README section 12 — drafting is no longer on the "not built" list.

Commit and tag `phase-7-complete`.

**⛓️ Dependencies** Task 26.
**✅ Expected Output** Clean verify. Tuning and groundedness tables in the README. Demo GIF recorded. Tag pushed.
**⏱️** 1.5 hours.

---

## 📅 Suggested Daily Schedule

### 7A — Ingestion (4 days)

| Day | Hours | Tasks |
|---|---|---|
| 1 | 3.5 | T1 Entities (2.0) · start T2 |
| 2 | 3.0 | T2 KB corpus (3.0) |
| 3 | 3.0 | T3 Chunker (3.0) |
| 4 | 4.5 | T4 Upload + IndexWorker (2.5) · T5 Reindex (2.0) |
| 5 | 2.0 | T6 CRUD endpoints (2.0) |

### 7B — Hybrid retrieval (4 days)

| Day | Hours | Tasks |
|---|---|---|
| 6 | 3.5 | T7 RRF query (3.5) |
| 7 | 4.0 | T8 Tier + pre-filter (2.0) · T9 search/explain (2.0) |
| 8 | 3.0 | T10 Retrieval eval suite (3.0) |
| 9 | 2.5 | **T11 Tuning (2.5)** — measurement, not coding |

**→ Path C+ ends here.**

### 7C — Drafting (6 days)

| Day | Hours | Tasks |
|---|---|---|
| 10 | 3.5 | T12 Prompts (2.0) · start T13 |
| 11 | 3.0 | T13 Claim generation (3.0) |
| 12 | 2.5 | T14 Numeric pre-filter (2.5) |
| 13 | 2.5 | T15 Entailment verifier (2.5) |
| 14 | 3.5 | T16 Coverage + suppression (2.0) · T17 unresolvedAspects (1.5) |
| 15 | 4.5 | T18 Endpoint + worker (2.5) · T19 GET /drafts (2.0) |
| 16 | 2.5 | T20 Action (1.5) · T21 fromDraftId (1.0) |

### 7D–7E — Evaluation & close (4 days)

| Day | Hours | Tasks |
|---|---|---|
| 17 | 3.5 | T22 Grounding suite (3.5) |
| 18 | 3.5 | T23 Refusal suite (2.0) · T24 CI wiring (1.5) |
| 19 | 4.5 | T25 Resolved-ticket indexing (2.0) · T26 Test suite (2.5) |
| 20 | 1.5 | T27 Close (1.5) |

**Day 21 — Buffer.** Most likely T14 (numeric normalisation has more formatting variants than you expect) or T22 (labelling 150 claims by hand takes longer than it reads).

---

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PHASE SUMMARY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Total Tasks    : 27   (7A: 6 · 7B: 5 · 7C: 10 · 7D: 3 · 7E: 3)
Total Estimate : 61 hours full  ·  27.5 hours for 7A+7B only
Suggested Days : 20 working days + 1 buffer   (9 days for 7A+7B)
                 (doc 07 said 6–8 days — 2.7×)

Hardest Task   : Task 7 — the RRF hybrid query.
                 The SQL is the easy half. The hard half is
                 understanding WHY rank fusion rather than score
                 normalisation, and being able to say it: ts_rank and
                 cosine distance have incomparable scales and
                 length-dependent distributions, so any normalisation
                 you invent is an unjustifiable hyperparameter. RRF
                 discards the scores and fuses on rank, which is
                 unit-free. Get that wrong and you have a working
                 query you cannot defend.

Runner-up      : Task 14 — the numeric pre-filter.
                 Conceptually trivial, fiddly in practice. Too strict
                 and it drops valid claims, making the feature
                 useless; too loose and fabricated amounts survive,
                 which is the failure it exists to prevent.

Most Skipped   : Task 11 — tuning, and specifically the
                 lexical-vs-vector-vs-hybrid comparison table.
                 It produces no code, so it feels optional. It is the
                 ONLY thing that turns "I used hybrid search" into
                 "hybrid improved Recall@5 by N points and the whole
                 gain came from the identifier query group" — which
                 is the difference between a claim and a result.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## Phase 7 exit checklist

**7A + 7B (Path C+):**
- [ ] 75 documents ingested, chunked with offsets and heading-path prefixes
- [ ] Hybrid RRF query returns fused results with per-source contributions
- [ ] **Tenant pre-filter verified inside both CTEs by `EXPLAIN`**
- [ ] `explain=true` shows the full score breakdown
- [ ] Retrieval eval reports Recall@5/MRR **per query group**
- [ ] Tuning tables committed; hybrid-vs-single-method gain quantified
- [ ] A documented reranking decision, made by measuring

**Full phase, additionally:**
- [ ] Drafts return claims with per-claim verdicts, **including dropped ones with reasons**
- [ ] The numeric pre-filter catches fabricated amounts at zero LLM cost
- [ ] Low-coverage drafts return `assembledText: null`, never a hedge
- [ ] **Refusal suite passes 100% and you have watched it fail on purpose**
- [ ] Claim-level and response-level groundedness both reported, with the gap discussed
- [ ] Resolved tickets are indexed PII-redacted, with all three quality gates
- [ ] Every new endpoint is in the Phase 4 cross-tenant `@MethodSource`

---

## Closing note

> ✅ **Phase 7 broken down. Planning is now genuinely complete: 16 documents, 226 tasks, every phase at task level.**
>
> **What this breakdown changed:** I recommended cutting Phase 7 wholesale. Having costed it properly, that was too blunt. **7A+7B is 9 days and carries the best interview material in the phase** — a search endpoint that *shows you* why neither lexical nor semantic retrieval alone is sufficient. 7C–7E is a further 11 days for the part that most resembles every other AI portfolio project.
>
> **Revised recommendation: Path C+ — build 7A and 7B, stop at the checkpoint, skip drafting.** ~24 weeks. If you later have time, 7C–7E slots in without rework.
>
> **And a note on the estimates, since this is the last one.** Every phase came in at 1.7–3.5× its original figure, and that consistency is the useful part: **the original numbers were not random, they were systematically missing the same things** — tests, DTOs, error paths, governance, tuning, and the measurement tasks that produce no code. If you plan another project after this one, multiply your instinct by two and budget explicitly for the tasks whose deliverable is a number rather than a feature.
