import * as React from 'react'

/** True while the user is typing somewhere a single-letter shortcut must not fire. */
export function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable) return true
  const tag = target.tagName
  if (tag === 'TEXTAREA' || tag === 'SELECT') return true
  if (tag === 'INPUT') {
    const type = (target as HTMLInputElement).type
    return !['checkbox', 'radio', 'button', 'submit'].includes(type)
  }
  // An open menu/listbox owns its own arrow and letter keys.
  return !!target.closest('[role="menu"],[role="listbox"],[role="dialog"]')
}

function isActivatable(target: EventTarget | null) {
  return target instanceof HTMLElement && !!target.closest('button,a,[role="button"],[role="radio"],[role="tab"],summary')
}

/**
 * A single-key shortcut ("j", "?", "/"), or "mod+k" for Cmd/Ctrl. Ignored while typing,
 * and while any modifier other than the one asked for is held, so it never fights the
 * browser's own shortcuts.
 */
export function useHotkey(
  combo: string,
  handler: (e: KeyboardEvent) => void,
  opts: { enabled?: boolean; allowInInputs?: boolean } = {},
) {
  const ref = React.useRef(handler)
  React.useEffect(() => {
    ref.current = handler
  })
  const { enabled = true, allowInInputs = false } = opts

  React.useEffect(() => {
    if (!enabled) return
    const wantsMod = combo.startsWith('mod+')
    const key = wantsMod ? combo.slice(4) : combo
    function onKey(e: KeyboardEvent) {
      const mod = e.metaKey || e.ctrlKey
      if (wantsMod !== mod || e.altKey) return
      if (e.defaultPrevented) return
      if (!wantsMod && !allowInInputs && isTypingTarget(e.target)) return
      if (e.key.toLowerCase() !== key.toLowerCase()) return
      // Enter and Escape already mean something on a focused control or an open overlay.
      if ((key === 'Enter' || key === 'Escape') && isActivatable(e.target)) return
      if (key === 'Escape' && document.querySelector('[role="dialog"],[role="menu"],[role="listbox"]')) return
      e.preventDefault()
      ref.current(e)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [combo, enabled, allowInInputs])
}
