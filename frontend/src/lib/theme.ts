export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'resolveai.theme'

export function getStoredTheme(): Theme | null {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    return v === 'light' || v === 'dark' ? v : null
  } catch {
    return null
  }
}

export function applyTheme(theme: Theme) {
  document.documentElement.classList.toggle('dark', theme === 'dark')
}

/** Called once, as early as possible, so there's no flash of the wrong theme. */
export function initTheme(): Theme {
  const theme = getStoredTheme() ?? 'light'
  applyTheme(theme)
  return theme
}

export function setTheme(theme: Theme) {
  try {
    localStorage.setItem(STORAGE_KEY, theme)
  } catch {
    // Per-viewer convenience only; a failed write just means it resets next visit.
  }
  applyTheme(theme)
}
