import * as React from 'react'
import { useLocation } from 'react-router-dom'

const APP = 'ResolveAI'

/**
 * Per-route document title (audit A7). Every route used to be titled "ResolveAI", so
 * browser tabs, history and screen-reader page announcements were indistinguishable.
 */
export function useDocumentTitle(title: string | null | undefined) {
  React.useEffect(() => {
    document.title = title ? `${title} — ${APP}` : APP
  }, [title])
}

/**
 * On client-side navigation, move focus to the new page's heading and announce it -
 * what a full page load would have done for free. Skipped on the first render, where
 * the browser's own focus handling is right.
 */
export function RouteAnnouncer() {
  const location = useLocation()
  const [message, setMessage] = React.useState('')
  const first = React.useRef(true)

  React.useEffect(() => {
    if (first.current) {
      first.current = false
      return
    }
    // Let the page render (and set its title) before moving focus.
    const t = window.setTimeout(() => {
      const heading = document.querySelector<HTMLElement>('main h1')
      if (heading) {
        heading.setAttribute('tabindex', '-1')
        heading.focus({ preventScroll: true })
      }
      setMessage(document.title.replace(` — ${APP}`, ''))
    }, 60)
    return () => window.clearTimeout(t)
  }, [location.pathname])

  return (
    <div role="status" aria-live="polite" className="sr-only">
      {message}
    </div>
  )
}
