export const PASSWORD_RULE = 'At least 10 characters, with a letter and a number.'

export function passwordStrength(pw: string): { label: string; ok: boolean } | null {
  if (pw.length === 0) return null
  const hasLetter = /[A-Za-z]/.test(pw)
  const hasDigit = /\d/.test(pw)
  if (pw.length < 10) return { label: `${pw.length} of 10 characters`, ok: false }
  if (!hasLetter || !hasDigit) return { label: 'Needs a letter and a number', ok: false }
  return { label: 'Looks good', ok: true }
}

/**
 * The server's rules, checked before the round trip. The server validates the password
 * before it looks the workspace up, so without this a visitor fixed one error, submitted,
 * and only then learned about the next. Local checks surface every rule at once.
 */
export function localErrors(fullName: string, password: string): Record<string, string> {
  const errors: Record<string, string> = {}
  if (fullName.trim().length < 2) errors.fullName = 'Enter your full name.'
  const strength = passwordStrength(password)
  if (!strength || !strength.ok) errors.password = PASSWORD_RULE
  return errors
}
