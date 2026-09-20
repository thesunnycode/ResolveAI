// Everything structural about a ticket is decided HERE, deterministically,
// BEFORE the LLM is asked for anything.
//
// This is the core design decision of the generator (doc 14 T9): the labels are
// not inferred from generated text — the text is generated FROM the labels. That
// yields ground truth by construction, so 2,000 labelled examples cost one
// generation run rather than 2,000 hand-labelling decisions.

const PRIORITY_WEIGHTS = [
  { value: 'P1', weight: 6 },
  { value: 'P2', weight: 22 },
  { value: 'P3', weight: 48 },
  { value: 'P4', weight: 24 },
];

// Not uniform: real support desks are dominated by a few categories.
const CATEGORY_WEIGHTS = {
  PAYMENT: 24, INTEGRATION: 16, AUTH: 14, DATA: 12,
  ONBOARDING: 11, PERFORMANCE: 9, BILLING: 8, API: 6,
};

const PERSONAS = [
  { value: 'OWNER',      weight: 55 },
  { value: 'ACCOUNTANT', weight: 30 },
  { value: 'DEVELOPER',  weight: 15 },
];

// Median business-minutes to resolve, by priority. sigma gives a realistic
// long tail — doc 10 T30's p75 breach prediction needs a spread, not a constant.
const RESOLUTION = {
  P1: { median: 110,  sigma: 0.7 },
  P2: { median: 380,  sigma: 0.8 },
  P3: { median: 1100, sigma: 0.9 },
  P4: { median: 2200, sigma: 1.0 },
};

// Hour-of-day weights, IST. Trough overnight, peak mid-morning, dip at lunch,
// second peak mid-afternoon. Doc 12 T5's arrival baseline is per (dow, hour),
// so a flat distribution would make the correlation gate meaningless.
const HOUR_WEIGHTS = [
  0.05, 0.03, 0.02, 0.02, 0.03, 0.06, 0.15, 0.35,  // 00–07
  0.80, 1.30, 1.80, 1.75, 1.10, 0.85, 1.35, 1.50,  // 08–15
  1.40, 1.00, 0.70, 0.50, 0.40, 0.30, 0.20, 0.10,  // 16–23
];

// Monday heaviest, weekends quiet. Index 0 = Sunday (JS getDay()).
const DOW_WEIGHTS = [0.20, 1.40, 1.20, 1.10, 1.10, 1.00, 0.30];

export function pickStructure(rng, domain) {
  const category = rng.weighted(
    domain.categories.map((c) => ({ value: c, weight: CATEGORY_WEIGHTS[c] ?? 10 }))
  );
  const priority = rng.weighted(PRIORITY_WEIGHTS);
  const persona = rng.weighted(PERSONAS);
  const tenant = rng.weighted(
    domain.tenants.map((t) => ({ value: t, weight: Math.round(t.ticket_share * 100) }))
  );

  return {
    category,
    priority,
    persona,
    tenant,
    team: domain.teamFor(category),
    entities: pickEntities(rng, domain, category, persona),
    resolutionBusinessMinutes: rng.logNormal(RESOLUTION[priority].median, RESOLUTION[priority].sigma),
  };
}

// ~50% of tickets must contain NO extractable entity. A corpus where every
// ticket names a service makes entity extraction look trivially reliable and
// inflates the clustering boost in doc 12 T6 against reality.
function pickEntities(rng, domain, category, persona) {
  const density = persona === 'DEVELOPER' ? 0.92 : persona === 'ACCOUNTANT' ? 0.42 : 0.28;
  if (!rng.bool(density)) return {};

  const e = {};
  if (rng.bool(0.70)) e.service = rng.pick(domain.services);
  if (persona === 'DEVELOPER' && rng.bool(0.80)) e.errorCode = rng.pick(domain.errorCodesFor(category));
  else if (rng.bool(0.15)) e.errorCode = rng.pick(domain.errorCodesFor(category));
  if (persona === 'DEVELOPER' && rng.bool(0.55)) e.httpStatus = rng.pick(domain.httpStatuses);
  if (rng.bool(0.18)) e.region = rng.pick(domain.regions);
  if (category === 'PAYMENT' && rng.bool(0.75)) e.paymentMethod = rng.pick(domain.paymentMethods);
  if (rng.bool(0.30)) e.invoiceRef = `INV-2026-${String(rng.int(1, 9999)).padStart(4, '0')}`;
  if (category === 'PAYMENT' && rng.bool(0.40)) e.amountInr = rng.int(250, 180000);
  return e;
}

/**
 * Arrival timestamps with a realistic weekday × hour shape.
 * Doc 14 T12 scales this to 28 days; the shape function lives here so the
 * starter corpus and the full corpus cannot drift apart.
 */
export function generateArrivalTimes(rng, count, days, endDate = new Date()) {
  const slots = [];
  const end = new Date(endDate);
  end.setUTCHours(0, 0, 0, 0);

  for (let d = days - 1; d >= 0; d--) {
    const day = new Date(end);
    day.setUTCDate(day.getUTCDate() - d);
    // IST is UTC+5:30 — model local hours, then convert back.
    const dowIst = new Date(day.getTime() + 5.5 * 3600e3).getUTCDay();
    for (let h = 0; h < 24; h++) {
      slots.push({ day, hourIst: h, weight: DOW_WEIGHTS[dowIst] * HOUR_WEIGHTS[h] });
    }
  }

  const times = [];
  for (let i = 0; i < count; i++) {
    const slot = rng.weighted(slots.map((s) => ({ value: s, weight: s.weight })));
    const t = new Date(slot.day);
    // hourIst → UTC
    t.setUTCHours(slot.hourIst - 5, rng.int(0, 59) - 30, rng.int(0, 59), 0);
    times.push(t);
  }
  return times.sort((a, b) => a - b);
}

export { RESOLUTION, CATEGORY_WEIGHTS, DOW_WEIGHTS, HOUR_WEIGHTS };
