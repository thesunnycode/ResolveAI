/**
 * The viewer's theme preference. "system" (the default) follows the OS and keeps following
 * it while the app is open; "light" and "dark" pin it. Stored per browser only.
 */
export type ThemePreference = 'light' | 'dark' | 'system'
export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'resolveai.theme'
const media = () => window.matchMedia('(prefers-color-scheme: dark)')

export function getStoredTheme(): ThemePreference {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    return v === 'light' || v === 'dark' || v === 'system' ? v : 'system'
  } catch {
    return 'system'
  }
}

export function resolveTheme(pref: ThemePreference): Theme {
  if (pref !== 'system') return pref
  return media().matches ? 'dark' : 'light'
}

export function applyTheme(theme: Theme) {
  document.documentElement.classList.toggle('dark', theme === 'dark')
}

let unsubscribe: (() => void) | null = null

function follow(pref: ThemePreference) {
  unsubscribe?.()
  unsubscribe = null
  if (pref !== 'system') return
  const mq = media()
  const onChange = () => applyTheme(mq.matches ? 'dark' : 'light')
  mq.addEventListener('change', onChange)
  unsubscribe = () => mq.removeEventListener('change', onChange)
}

/** Called once, as early as possible, so there's no flash of the wrong theme. */
export function initTheme(): Theme {
  const pref = getStoredTheme()
  const theme = resolveTheme(pref)
  applyTheme(theme)
  follow(pref)
  return theme
}

export function setThemePreference(pref: ThemePreference) {
  try {
    localStorage.setItem(STORAGE_KEY, pref)
  } catch {
    // Per-viewer convenience only; a failed write just means it resets next visit.
  }
  applyTheme(resolveTheme(pref))
  follow(pref)
}
