import { CheckCircle2, Clock, Loader2 } from 'lucide-react'
import type { ResponseTarget, TicketStatus } from '@/lib/types'
import { cn, formatDuration } from '@/lib/utils'

const DAY = ['', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

/** "Mon–Fri 09:00–18:00 (Asia/Kolkata)" / "around the clock". */
function describeHours(t: ResponseTarget): string {
  const days = [...t.workingDays].sort((a, b) => a - b)
  const allDay = t.dayStart.startsWith('00:00') && t.dayEnd.startsWith('23:5')
  if (days.length === 7 && allDay) return 'around the clock'
  const contiguous = days.every((d, i) => i === 0 || d === days[i - 1] + 1)
  const dayText =
    days.length === 7 ? 'every day' : contiguous && days.length > 2 ? `${DAY[days[0]]}–${DAY[days[days.length - 1]]}` : days.map((d) => DAY[d]).join(', ')
  const hours = allDay ? '' : ` ${t.dayStart.slice(0, 5)}–${t.dayEnd.slice(0, 5)}`
  return `${dayText}${hours} (${t.timezone})`
}

/**
 * The customer's answer to "did this go anywhere, and when will someone reply?".
 *
 * The new-ticket page promises "every ticket carries a response target"; this is where
 * that promise is shown. Before triage has set it (a few seconds) it says the ticket is
 * being routed, and the page polls until the target arrives.
 */
export function ResponsePromise({
  target,
  status,
  justCreated,
}: {
  target: ResponseTarget | null | undefined
  status: TicketStatus
  justCreated: boolean
}) {
  if (status === 'RESOLVED' || status === 'CLOSED') return null

  let icon = <Clock className="size-4" aria-hidden />
  let tone = 'border-primary/25 bg-primary-bg/50'
  let title: string
  let body: string

  if (!target) {
    icon = <Loader2 className="size-4 animate-spin" aria-hidden />
    title = justCreated ? 'Received — thanks for the detail.' : 'Being routed to the right team'
    body = 'We read it and send it to the team that owns it, usually within a minute. This page updates by itself.'
  } else if (target.state === 'MET') {
    icon = <CheckCircle2 className="size-4" aria-hidden />
    tone = 'border-success/25 bg-success-bg/60'
    title = 'An agent has replied'
    body = 'Reply below any time — the whole conversation stays on this ticket.'
  } else if (target.state === 'BREACHED') {
    tone = 'border-warning/30 bg-warning-bg/60'
    title = "We're behind on this one"
    body = `We aimed to reply within ${formatDuration(target.targetBusinessMinutes)} and haven't yet. It has been flagged to a team lead.`
  } else {
    title = justCreated ? 'Received — a person will reply soon' : 'Waiting for our reply'
    body = `We aim to reply within ${formatDuration(target.targetBusinessMinutes)}, counted in support hours: ${describeHours(target)}.`
  }

  return (
    <div role="status" className={cn('mt-5 flex gap-3 rounded-xl border px-4 py-3', tone)}>
      <span className="mt-0.5 shrink-0 text-text-muted">{icon}</span>
      <div>
        <p className="text-[13.5px] font-medium text-text">{title}</p>
        <p className="mt-0.5 text-[12.5px] leading-relaxed text-text-muted">{body}</p>
      </div>
    </div>
  )
}
