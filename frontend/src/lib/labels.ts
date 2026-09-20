/**
 * Enum → words people read (audit V6). "PAYMENT", "SINGLE_USER" and "TEAM_LEAD" are API
 * values; the UI says "Payment", "Single user", "Team lead".
 */
const OVERRIDES: Record<string, string> = {
  API: 'API',
  AUTH: 'Sign-in & access',
  SINGLE_USER: 'Single user',
  MULTIPLE_USERS: 'Several users',
  ALL_USERS: 'All users',
}

export function humanize(value: string | null | undefined): string {
  if (!value) return ''
  if (OVERRIDES[value]) return OVERRIDES[value]
  const words = value.replace(/_/g, ' ').toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}
