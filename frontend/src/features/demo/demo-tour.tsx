import { CheckCircle2, Circle, Clock3, Siren, Sparkles, X, type LucideIcon } from 'lucide-react'
import * as React from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '@/features/auth/auth-context'
import { track } from '@/lib/analytics'
import { cn } from '@/lib/utils'
import { useIsDemoWorkspace, useShowcase } from './api'
import { SimulateOutageButton } from './simulate-outage-button'

const DONE_KEY = 'resolveai.tour.done'
const DISMISSED_KEY = 'resolveai.tour.dismissed'

type Step = 'sla' | 'draft' | 'incident'

function readDone(): Set<Step> {
  try {
    return new Set(JSON.parse(localStorage.getItem(DONE_KEY) ?? '[]') as Step[])
  } catch {
    return new Set()
  }
}

/**
 * "Tour the three mechanisms" — the first-run guide for someone evaluating ResolveAI.
 *
 * The queue greets agents with "work the oldest at-risk ticket first", which is advice for
 * people who already know the product. A first-time visitor needs to know which three
 * things to look at; this points at a live example of each and ticks them off. Demo
 * workspace only, dismissible, and remembered once dismissed.
 */
export function DemoTour() {
  const { user } = useAuth()
  const inDemo = useIsDemoWorkspace(user?.tenantSlug)
  const [dismissed, setDismissed] = React.useState(() => localStorage.getItem(DISMISSED_KEY) === '1')
  const [done, setDone] = React.useState<Set<Step>>(readDone)
  const showcase = useShowcase(inDemo && !dismissed)

  if (!inDemo || dismissed || !user) return null
  const canSimulate = user.role === 'TEAM_LEAD' || user.role === 'ADMIN'
  const s = showcase.data

  function complete(step: Step) {
    setDone((prev) => {
      const next = new Set(prev).add(step)
      localStorage.setItem(DONE_KEY, JSON.stringify([...next]))
      return next
    })
    track('tour_item_completed', { step })
  }

  function dismiss() {
    localStorage.setItem(DISMISSED_KEY, '1')
    setDismissed(true)
    track('tour_dismissed', { completed: done.size })
  }

  const steps: {
    key: Step
    icon: LucideIcon
    title: string
    body: string
    to: string | null
    missing: React.ReactNode
  }[] = [
    {
      key: 'draft',
      icon: Sparkles,
      title: 'A reply drafted from the knowledge base',
      body: 'Every sentence cites its source; unsupported ones are struck out.',
      to: s?.draftTicket ? `/tickets/${s.draftTicket.id}` : null,
      missing: 'Open any ticket and press “Draft a reply”.',
    },
    {
      key: 'sla',
      icon: Clock3,
      title: 'An SLA clock that paused itself',
      body: 'Waiting on the customer stops the clock — business hours only.',
      to: s?.pausedTicket ? `/tickets/${s.pausedTicket.id}` : null,
      missing: 'Move any ticket to “Waiting on customer” to watch the clock pause.',
    },
    {
      key: 'incident',
      icon: Siren,
      title: 'Forty tickets, one incident',
      body: 'A statistical gate — not the model — decides when a burst is an outage.',
      to: s?.incident ? `/incidents/${s.incident.id}` : null,
      missing: canSimulate ? (
        <SimulateOutageButton size="xs" />
      ) : (
        'Sign in as a team lead to simulate an outage.'
      ),
    },
  ]

  return (
    <section aria-labelledby="tour-heading" className="glass mb-6 rounded-xl p-5">
      <div className="mb-3 flex items-start justify-between gap-4">
        <div>
          <p id="tour-heading" className="text-base font-semibold text-text">
            Tour the three things that make this more than a chatbot
          </p>
          <p className="mt-0.5 text-sm text-text-muted">
            {done.size} of 3 seen · each one is live data in this demo workspace
          </p>
        </div>
        <button onClick={dismiss} className="text-text-subtle hover:text-text" aria-label="Dismiss the tour">
          <X className="size-4" aria-hidden />
        </button>
      </div>
      <ol className="grid gap-2 md:grid-cols-3">
        {steps.map((step) => {
          const isDone = done.has(step.key)
          const content = (
            <>
              <span className="flex items-center gap-2 text-sm font-medium text-text">
                {isDone ? (
                  <CheckCircle2 className="size-4 shrink-0 text-success" aria-label="Seen" />
                ) : (
                  <Circle className="size-4 shrink-0 text-text-subtle" aria-hidden />
                )}
                <step.icon className="size-3.5 shrink-0 text-primary" aria-hidden />
                {step.title}
              </span>
              <span className="mt-1 block pl-6 text-xs leading-relaxed text-text-muted">{step.body}</span>
            </>
          )
          return (
            <li key={step.key}>
              {step.to ? (
                <Link
                  to={step.to}
                  onClick={() => complete(step.key)}
                  className={cn(
                    'block h-full rounded-lg border border-border bg-surface-2/40 p-3 transition-colors hover:border-border-strong hover:bg-surface-2',
                    isDone && 'opacity-70',
                  )}
                >
                  {content}
                </Link>
              ) : (
                <div className="h-full rounded-lg border border-dashed border-border p-3">
                  {content}
                  <div className="mt-2 pl-6 text-xs text-text-subtle">{step.missing}</div>
                </div>
              )}
            </li>
          )
        })}
      </ol>
    </section>
  )
}
