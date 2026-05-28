// Seeded PRNG so `--seed 42` reproduces byte-identical output.
// Math.random() would make the corpus non-reproducible, which breaks the
// "commit the generated JSON" requirement in doc 14 T10.

export function makeRng(seed) {
  let a = seed >>> 0;
  const next = () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };

  return {
    next,
    int: (min, max) => min + Math.floor(next() * (max - min + 1)),
    pick: (arr) => arr[Math.floor(next() * arr.length)],
    /** Pick from [{value, weight}] */
    weighted(entries) {
      const total = entries.reduce((s, e) => s + e.weight, 0);
      let r = next() * total;
      for (const e of entries) {
        r -= e.weight;
        if (r <= 0) return e.value;
      }
      return entries[entries.length - 1].value;
    },
    bool: (p) => next() < p,
    shuffle(arr) {
      const a2 = [...arr];
      for (let i = a2.length - 1; i > 0; i--) {
        const j = Math.floor(next() * (i + 1));
        [a2[i], a2[j]] = [a2[j], a2[i]];
      }
      return a2;
    },
    /** Box–Muller, for log-normal resolution durations */
    normal(mean = 0, sd = 1) {
      const u = Math.max(next(), 1e-12);
      const v = next();
      return mean + sd * Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    },
    logNormal(medianMinutes, sigma) {
      return Math.max(1, Math.round(medianMinutes * Math.exp(this.normal(0, sigma))));
    },
  };
}
