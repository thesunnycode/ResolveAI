// Loads seed/domain/*.yml — the SINGLE SOURCE OF TRUTH shared with the
// deterministic entity extractor (doc 12 T3). Nothing here may hardcode a
// service name, error code, region or payment method.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import YAML from 'yaml';

const HERE = dirname(fileURLToPath(import.meta.url));
const DOMAIN_DIR = join(HERE, '..', '..', 'domain');

const load = (name) => YAML.parse(readFileSync(join(DOMAIN_DIR, name), 'utf8'));

export function loadDomain() {
  const taxonomy = load('taxonomy.yml');
  const services = load('services.yml');
  const errorCodes = load('error-codes.yml');
  const regions = load('regions.yml');
  const paymentMethods = load('payment-methods.yml');
  const tenants = load('tenants.yml');

  const categories = Object.keys(taxonomy.categories);

  // Fail loudly on the mismatch class that otherwise fails silently.
  for (const cat of categories) {
    const team = taxonomy.categories[cat].team;
    if (!taxonomy.teams[team]) {
      throw new Error(`taxonomy.yml: category ${cat} routes to unknown team "${team}"`);
    }
    if (!taxonomy.teams[team].skills.includes(cat)) {
      throw new Error(`taxonomy.yml: team "${team}" does not list ${cat} in skills[]`);
    }
    if (!errorCodes.error_codes[cat]) {
      throw new Error(`error-codes.yml: no codes defined for category ${cat}`);
    }
    const cs = services.category_services?.[cat];
    if (!cs) throw new Error(`services.yml: no category_services entry for ${cat}`);
    for (const svc of cs) {
      if (!services.services.includes(svc)) {
        throw new Error(`services.yml: category_services.${cat} references unknown service "${svc}"`);
      }
    }
  }
  const defaults = Object.entries(taxonomy.teams).filter(([, t]) => t.default);
  if (defaults.length !== 1) {
    throw new Error(`taxonomy.yml: expected exactly one default team, found ${defaults.length}`);
  }

  return {
    categories,
    taxonomy,
    teamFor: (cat) => taxonomy.categories[cat].team,
    descriptionFor: (cat) => taxonomy.categories[cat].description,
    services: services.services,
    servicesFor: (cat) => services.category_services?.[cat] ?? services.services,
    serviceAliases: services.aliases ?? {},
    errorCodesFor: (cat) => errorCodes.error_codes[cat],
    httpStatuses: errorCodes.http_statuses,
    regions: regions.regions,
    paymentMethods: paymentMethods.payment_methods,
    paymentAliases: paymentMethods.aliases ?? {},
    tenants: tenants.tenants,
  };
}
