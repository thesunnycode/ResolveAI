import { api } from './api-client'

/**
 * First-party onboarding analytics (POST /api/v1/events). The backend only accepts this
 * vocabulary; anything else is dropped there, so keep the two lists in step with
 * ProductEventController.EVENTS.
 */
export type ProductEvent =
  | 'auth_page_viewed'
  | 'demo_login_clicked'
  | 'auth_failed'
  | 'login_succeeded'
  | 'register_submitted'
  | 'register_failed'
  | 'first_page_loaded'
  | 'ticket_opened'
  | 'draft_requested'
  | 'draft_result'
  | 'draft_used'
  | 'reply_sent_from_draft'
  | 'incident_viewed'
  | 'storm_started'
  | 'customer_ticket_created'
  | 'tour_item_completed'
  | 'tour_dismissed'
  | 'eval_run_started'

/** Codes, statuses, counts and flags only — never ticket text or emails. */
type Props = Record<string, string | number | boolean | null | undefined>

const SESSION_KEY = 'resolveai.analyticsSession'

function sessionId(): string {
  let id = sessionStorage.getItem(SESSION_KEY)
  if (!id) {
    id = crypto.randomUUID()
    sessionStorage.setItem(SESSION_KEY, id)
  }
  return id
}

let queue: { name: ProductEvent; path: string; sessionId: string; props: Props }[] = []
let timer: ReturnType<typeof setTimeout> | null = null

function flush() {
  timer = null
  if (queue.length === 0) return
  const events = queue.splice(0, 25)
  // Fire-and-forget: analytics must never surface an error or slow a user down.
  api.post('/events', { events }).catch(() => undefined)
  if (queue.length > 0) timer = setTimeout(flush, 1000)
}

export function track(name: ProductEvent, props: Props = {}) {
  try {
    queue.push({ name, path: window.location.pathname, sessionId: sessionId(), props })
    if (!timer) timer = setTimeout(flush, 1500)
  } catch {
    // sessionStorage can be unavailable (private mode); losing an event is fine.
  }
}

/** Once per browser session per key — for "first X" events. */
export function trackOnce(key: string, name: ProductEvent, props: Props = {}) {
  try {
    const k = `resolveai.tracked.${key}`
    if (sessionStorage.getItem(k)) return
    sessionStorage.setItem(k, '1')
  } catch {
    return
  }
  track(name, props)
}

if (typeof window !== 'undefined') {
  window.addEventListener('pagehide', flush)
}
