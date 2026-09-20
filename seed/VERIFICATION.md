# Corpus verification — starter corpus

**File:** `seed/generated/tickets-starter.json`
**Generated:** 2026-09-20 · `--count 200 --seed 42 --days 14 --batch 20`
**Model:** `gpt-4.1-mini` (OpenAI) · 23,870 input / 13,568 output tokens · **≈ $0.03**
**Revision 2** — three findings from revision 1 fixed; see *What was fixed* below.
**Reviewed by:** hand, 30 tickets read in full, stratified across all 8 categories

> Doc 14 Task 10: *"Reading thirty tickets is not optional and it is not busywork. A
> corpus you have not read is a corpus you cannot defend."* This file is that record.

---

## Distributions — all within tolerance

| | Target | Actual |
|---|---|---|
| PAYMENT / INTEGRATION / ONBOARDING / AUTH | 24 / 16 / 11 / 14 % | 23.0 / 15.5 / 15.5 / 12.5 % |
| PERFORMANCE / DATA / API / BILLING | 9 / 12 / 6 / 8 % | 11.0 / 9.5 / 7.0 / 6.0 % |
| Priority P1 / P2 / P3 / P4 | 6 / 22 / 48 / 24 % | 6.5 / 22.0 / 47.0 / 24.5 % |
| Persona OWNER / ACCOUNTANT / DEVELOPER | 55 / 30 / 15 % | 54.5 / 30.5 / 15.0 % |
| Tenant acme / bluestone / chai | 50 / 33 / 17 % | 53.5 / 32.5 / 14.0 % |
| Tickets naming an entity | ~50 % | **45.5 %** |
| Implausible service↔category pairings | 0 | **0** |

**ONBOARDING over-represented** (15.5% against 11%) and **DATA under** (9.5% against 12%)
— sampling variance at n=200, expected to converge in the 2,000-ticket run. Not corrected;
over-fitting a synthetic distribution is not worth the time.

**Entity density** was 41.5% in revision 1; per-persona densities were nudged
(0.92/0.42/0.28 → 0.95/0.52/0.36) and it now sits at **45.5%**. Doc 14 asks for "roughly
half"; 54.5% of tickets still carry no extractable entity, preserving the property that
matters — entity extraction must not look trivially reliable.

**Entity fidelity: 99.5%** (193/194 entity values appear verbatim). The one miss is a
dropped `auth-service`. An apparent second miss was my check's fault: `amountInr=54181`
appears as `₹54,181`, which is *more* realistic than a bare integer — the check now
normalises separators.

---

## What reading them found

### ✅ Holds up

- **Personas are genuinely distinct.** DEVELOPER tickets are fragments with error codes and
  no pleasantries (*"Subscription failed to renew due to ERR_CARD_ON_FILE_EXPIRED with
  invoice INV-2026-7855 on ledger-service. Need to update card details and retry."*).
  OWNER tickets run on and code-switch (*"…Sessions expire too fast, kya karu urgent?"*).
  ACCOUNTANT tickets are formal and reconciliation-focused.
- **Category is inferable from symptoms without the category word.** *"keeps looping or
  session drops suddenly"* → AUTH. *"export missing some recent sales"* → DATA. This is
  the property the whole corpus depends on.
- **Entities are used verbatim** and are category-plausible after the `category_services`
  fix (see below).
- **Length genuinely varies** — 2 sentences to 6.
- **PAYMENT and BILLING are distinguishable** despite the deliberate overlap: BILLING
  tickets talk about plan changes and Ledgerly's own invoices, PAYMENT about customer money
  in flight.

### ⚠️ Three defects found, and what was done

**1. Service↔category pairings were implausible — FIXED before this run.**
The 2-ticket smoke test produced a `PAYMENT` ticket citing `sync-service`. `pickEntities`
was choosing uniformly from all eight services. Added `category_services` to
`seed/domain/services.yml` with weighted selection, plus a startup validation that fails
loudly on an unknown service name. **Verified: 0 implausible pairings in 300 structures.**

Left unfixed this would have been quietly serious — Phase 8's shared-entity boost
(doc 12 T6) would cluster on noise.

**2. The forbidden-word checker had an 11-in-19 false-positive rate — FIXED.**
The first run reported 19 violations. Investigation showed 11 were the *service name
itself*: `payment-service` contains "payment", `auth-service` contains "auth". The model
was correctly obeying the instruction to use entities verbatim; **the check was wrong, not
the model.** The checker now masks entity values before scanning.

**3. Forbidden-word leakage: 4.5% → 0%. FIXED.**

Revision 1 recorded 9/200 genuine leaks and accepted them, having found that strengthening
the prompt moved leakage from 4.0% to 4.5% — i.e. not at all. Two mechanical fixes closed it:

*a. Hard/soft classification.* The `FORBIDDEN` map conflated two different things.
**Hard** words are distinctive category names (`payment`, `billing`, `onboarding`) where a
leak genuinely gives the label away. **Soft** words are ordinary English that happens to be
a category name — `data` and `api`. *"partial data for last month"* is natural writing in a
ticket of **any** category, and a DATA ticket can hardly avoid it. Counting those as defects
overstated the failure rate. They are now reported separately: **8/200 soft hits, not a
defect.** Matching also moved from substring to whole-word regex, so `pay` no longer flags
`paying`.

*b. A targeted repair loop.* After generation, any ticket still containing a hard word is
sent back in a single batch with an explicit *"you used X, rewrite without it, preserve
every identifier"* instruction, up to two rounds. A rewrite of the offenders is the
mechanical fix that works where prompt engineering did not.

**Result: 0/200 hard-word leaks, verified independently** with a re-derivation that does not
share code with the generator.

**4. The generator crashed on a malformed model response. FIXED.**

Regeneration hit `Bad control character in string literal in JSON at position 5656` — the
model emitted a literal newline inside a quoted string. `JSON.parse` is strict, so one stray
newline discarded a whole 20-ticket batch. `extractJsonArray` now retries with a parser that
walks the text tracking string state and escapes raw control characters. Also switched the
error path from `process.exit(1)` to `process.exitCode`, which was tripping a libuv
assertion on Windows.

## What was NOT fixed, and why

| Finding | Status |
|---|---|
| ~3% label noise | **Recorded, not fixed** — see below |
| Soft-word hits, 8/200 | **Not a defect** — reclassified, reported separately |
| Entity fidelity 99.5% (1 miss) | **Accepted** at 0.5% |
| Category distribution variance (ONBOARDING 15.5% vs 11%) | **Accepted** — n=200 sampling variance, expected to converge at 2,000 |
| Tenant distribution drift (chai 14% vs 17%) | **Accepted** — same reason |

---

## The finding that matters most: label noise

**1 clear mislabel in the 30 read = ~3%.**

> Ground truth `API / P4 / OWNER`. Body: *"website slow and sometimes pages do not load
> fully … parts of the page remain blank or take long time to appear."*
>
> An independent reader labels that **PERFORMANCE**, not API.

The generator picked `API`; the model wrote a performance ticket. One or two others
(invoice queries via `api-gateway`) sit on a genuine API/DATA boundary and could go either
way.

**Estimated label noise: 3–8%.**

### Why this is recorded prominently rather than fixed

1. **It caps achievable classification accuracy.** A classifier scoring 94% against these
   labels may be at ceiling. Reporting 94% without this caveat would be misleading.
2. **It is exactly why doc 14 Task 15 requires the 100 evaluation cases to be labelled
   independently, by reading each ticket** — not by reusing the generator's labels.
   Generator labels measure whether the classifier agrees with the process that wrote the
   text, which is a weaker and more flattering claim. That instruction now has evidence
   behind it rather than being a principle.
3. **Real ticket data has mislabels too.** A corpus with 0% label noise would be less
   realistic, not more.

**Action:** when the independent eval set is built (doc 14 T15), record the disagreement
rate between independent labels and generator labels. That number is the honest upper
bound on what any classifier could score, and it belongs in the README.

---

## Reproducibility

```bash
cd seed/generator && npm install
node generate.js --count 200 --seed 42 --days 14 --batch 20 \
     --out ../generated/tickets-starter.json
```

- Same seed → **byte-identical structure**, verified by md5 across repeated `--dry-run`s.
- LLM responses are cached on disk by content hash, so a re-run is free and reproduces the
  committed file exactly. Pass `--no-cache` to force fresh generation (the text will differ;
  the labels will not).
- `--dry-run` produces the full structure and distribution report with **zero LLM calls**.

## Known limitations

- Generated text occasionally contains dates inconsistent with `createdAt` (a model
  invention). Harmless — nothing parses dates out of the body.
- 14 days of arrivals, not 28. The arrival-rate baseline in doc 12 T5 needs 28; that is
  doc 14 Task 12, deferred until just before Phase 8.
- No incident storms, no negative controls, no knowledge base yet — doc 14 Tasks 13–16 and
  doc 15 Task 2, also deferred.
- Priority and linguistic urgency sometimes disagree (a P4 reading as urgent). **Not a
  defect** — `linguisticUrgency` is an explicit signal the model extracts, and the
  deterministic policy is supposed to *not* simply trust the customer's tone. These are
  useful cases.
