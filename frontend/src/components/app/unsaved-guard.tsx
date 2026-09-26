import * as React from 'react'

/**
 * Warns before leaving a page with unsaved text.
 *
 * Only guards tab close/refresh (`beforeunload`). react-router-dom's
 * `useBlocker` — which would also cover in-app `<Link>`/`navigate()`
 * navigation — requires a data router (`createBrowserRouter`); this app
 * still renders `<Routes>` under a plain `<BrowserRouter>` (see main.tsx),
 * so in-app navigation isn't blocked here. Migrating the router setup to
 * add that is a separate, larger change than this reskin.
 */
export function UnsavedGuard({ when }: { when: boolean; what?: string }) {
  React.useEffect(() => {
    if (!when) return
    const handler = (e: BeforeUnloadEvent) => {
      e.preventDefault()
      e.returnValue = ''
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [when])

  return null
}
