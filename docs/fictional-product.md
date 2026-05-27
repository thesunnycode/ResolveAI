# The Fictional Product — Ledgerly

> Phase 1, Task 7. **Everything in the corpus is about this product.**
>
> This is not world-building for its own sake. The service names, error codes, regions
> and payment methods defined here are read by **two** consumers that must agree exactly:
> the corpus generator ([14 T9](planning/14-TASK-BREAKDOWN-PHASE-1.md)) and the
> deterministic entity extractor ([12 T3](planning/12-TASK-BREAKDOWN-PHASE-8.md)).
>
> If the corpus says `payments-service` and the extractor looks for `payment-service`,
> extraction silently returns nothing, the clustering boost in
> [12 T6](planning/12-TASK-BREAKDOWN-PHASE-8.md) does nothing, and **no error appears
> anywhere**. Hence: one source of truth, in `seed/domain/`.

---

## What Ledgerly is

**Ledgerly** is a B2B invoicing and payments SaaS for Indian small and medium businesses.
A shop, agency or small manufacturer uses it to:

- raise and send GST-compliant invoices to their customers
- collect payment by UPI, card, netbanking or bank transfer
- reconcile incoming payments against outstanding invoices automatically
- sync the resulting ledger into Tally or Zoho Books
- file GST returns from the reconciled data

Roughly 4,000 businesses use it. Plans are **FREE** (5 invoices/month), **PRO**
(₹999/month) and **ENTERPRISE** (custom, with a contractual SLA).

## Why this product and not another

Three reasons, all of them about what the corpus needs to be able to demonstrate:

1. **Payment failure has enormous linguistic variety.** The same underlying outage
   produces *"card declined"*, *"money deducted but no invoice marked paid"*, *"UPI
   collect request expired"*, *"paid twice, charged once"*, *"stuck on processing"*.
   Those share almost no vocabulary. A keyword matcher cannot group them; embeddings
   can. That is exactly the demonstration [12 T21](planning/12-TASK-BREAKDOWN-PHASE-8.md)
   needs, and a product without this property would make the whole incident-correlation
   feature look like a solved problem.

2. **It has a natural spread across eight distinct support categories** — payments,
   auth, billing, integrations, data, API, onboarding/KYC, performance — without them
   being artificially separated.

3. **It generates real-sounding entities.** Service names, error codes, regions and
   payment methods appear naturally in how customers describe problems, which gives the
   deterministic extractor something genuine to find.

## Who raises tickets

| Persona | Share | How they write |
|---|---|---|
| **Business owner** | ~55% | Non-technical, often frustrated, describes symptoms not causes. Mixes Hindi/English occasionally. Rarely includes error codes. |
| **Accountant / bookkeeper** | ~30% | Precise about amounts, dates and invoice numbers. Cares about reconciliation and GST correctness. Often includes invoice references. |
| **Developer** | ~15% | Uses the API. Includes error codes, HTTP statuses, request IDs and timestamps. Terse. |

**The mix matters.** A corpus written entirely in developer voice makes entity extraction
trivially easy and classification unrealistically accurate. Roughly half the tickets
should contain **no** extractable entity at all.

## System architecture (in-fiction)

Eight backing services, which is where service names in tickets come from:

| Service | Owns |
|---|---|
| `payment-service` | Payment initiation, gateway callbacks, refunds |
| `auth-service` | Login, sessions, 2FA, API keys |
| `ledger-service` | Invoices, credit notes, the reconciled ledger |
| `sync-service` | Tally and Zoho Books connectors, bank feed ingestion |
| `report-service` | GST returns, exports, dashboards |
| `api-gateway` | Public API, rate limiting, webhooks |
| `kyc-service` | Business verification, document review |
| `notification-service` | Email, SMS and WhatsApp delivery |

Deployed in `ap-south-1` (primary) and `ap-southeast-1` (DR).

## The support organisation

Four teams, matching `seed/domain/taxonomy.yml`:

| Team | Handles | Default? |
|---|---|---|
| **Payments** | `PAYMENT`, `BILLING` | no |
| **Platform** | `AUTH`, `API`, `PERFORMANCE` | no |
| **Integrations** | `INTEGRATION`, `DATA` | no |
| **Customer Success** | `ONBOARDING` | **yes** — catch-all |

## Tenants in the demo data

Three, one per plan tier — so the priority policy's `PLAN_TIER_BUMP` rule
([11 T22](planning/11-TASK-BREAKDOWN-PHASE-6.md)) has something to act on:

| Tenant | Slug | Plan | Character |
|---|---|---|---|
| Acme Traders | `acme` | ENTERPRISE | High volume, contractual SLA, impatient |
| Bluestone Design | `bluestone` | PRO | Mid volume, mostly integration issues |
| Chai Corner | `chai` | FREE | Low volume, onboarding-heavy, non-technical |

## Planted incident storms

Three, injected into the timeline by [14 T13](planning/14-TASK-BREAKDOWN-PHASE-1.md):

| | Scenario | Size / window | Purpose |
|---|---|---|---|
| **A** | `payment-service` gateway timeout — UPI and card both failing | 38 tickets / 18 min, Fri 14:02 | The demo. Must be the most linguistically varied. |
| **B** | `auth-service` session invalidation after a bad deploy | 22 tickets / 25 min, Tue 09:40 | Second positive, different category |
| **C** | `sync-service` Tally connector lagging | 12 tickets / 28 min, Wed 16:15 | **Borderline** — near the gate threshold, keeps tuning honest |

## Deliberate knowledge-base gaps

[15 T2](planning/15-TASK-BREAKDOWN-PHASE-7.md) writes 60 articles and 15 runbooks — and
must leave **five specific scenarios uncovered**, because the refusal suite in
[15 T23](planning/15-TASK-BREAKDOWN-PHASE-7.md) has nothing to test otherwise:

1. Partial refunds on a cancelled annual subscription
2. GST treatment of a credit note issued across financial years
3. Bank feed reconciliation when the same UTR appears twice
4. Recovering API access after a key is revoked for abuse
5. Compensation policy for SLA breaches on ENTERPRISE plans

A knowledge base with complete coverage makes the suppression path untestable, and
suppression is the whole point of citation-enforced drafting.

---

## Writing guidance for the generator

**Do:**
- Vary length — 15-word one-liners through 150-word essays
- Let the business owner persona be vague, emotional and occasionally mistaken about cause
- Include invoice references (`INV-2026-0451`), UTRs, amounts in ₹ — these are what
  [15 T14](planning/15-TASK-BREAKDOWN-PHASE-7.md)'s numeric pre-filter operates on
- Let ~50% of tickets contain no extractable entity
- Occasionally mis-spell a service name the way a real user would

**Do not:**
- Use the category name in the ticket body. A `PAYMENT` ticket containing the word
  "payment" turns classification into keyword matching and makes both the eval and the
  storm demo meaningless. This is enforced as a generator instruction.
- Make every ticket well-structured. Real tickets are one run-on sentence at 11pm.
- Reuse phrasings across the storm tickets — see [14 T13](planning/14-TASK-BREAKDOWN-PHASE-1.md).
