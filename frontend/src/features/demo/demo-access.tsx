import { ArrowRight, Headset, KanbanSquare, Settings2, UserRound, type LucideIcon } from 'lucide-react'
import * as React from 'react'
import { useAuth } from '@/features/auth/auth-context'
import { ApiError } from '@/lib/api-client'
import { track } from '@/lib/analytics'
import type { Role } from '@/lib/types'
import { cn } from '@/lib/utils'
import { useDemoInfo } from './api'

const ROLE_COPY: Record<Role, { label: string; hint: string; icon: LucideIcon }> = {
  AGENT: { label: 'Agent', hint: 'AI-triaged queue and cited reply drafts', icon: Headset },
  TEAM_LEAD: { label: 'Team lead', hint: 'Confirm incidents, simulate an outage', icon: KanbanSquare },
  CUSTOMER: { label: 'Customer', hint: 'Raise a ticket, see the response promise', icon: UserRound },
  ADMIN: { label: 'Admin', hint: 'SLA policy, knowledge base, evaluation', icon: Settings2 },
}

const ORDER: Role[] = ['AGENT', 'TEAM_LEAD', 'CUSTOMER', 'ADMIN']

/**
 * One-click entry to the demo workspace. Renders nothing when the deployment has no demo
 * (GET /demo is a 404), so a real deployment keeps the plain sign-in form.
 *
 * The first-run audit found every visitor had to know a workspace name, an email and a
 * password that were only printed in the backend's console — this replaces all three.
 */
export function DemoAccess({ compact }: { compact?: boolean }) {
  const demo = useDemoInfo()
  const { loginAsDemo } = useAuth()
  const [pending, setPending] = React.useState<Role | null>(null)
  const [error, setError] = React.useState<string | null>(null)

  if (!demo.data?.enabled || demo.data.roles.length === 0) return null
  const roles = ORDER.filter((r) => demo.data!.roles.includes(r))

  async function enter(role: Role) {
    setError(null)
    setPending(role)
    track('demo_login_clicked', { role })
    try {
      await loginAsDemo(role)
      track('login_succeeded', { method: 'demo', role })
    } catch (err) {
      const problem = err instanceof ApiError ? err.problem : null
      track('auth_failed', { endpoint: 'demo_login', status: problem?.status ?? 0, code: problem?.errorCode })
      setError(problem?.detail ?? 'Could not open the demo. Try again.')
    } finally {
      setPending(null)
    }
  }

  return (
    <section aria-labelledby="demo-heading" className={cn('rounded-xl border border-primary/25 bg-primary-bg/40 p-4', compact ? 'mb-5' : 'mb-7')}>
      <p id="demo-heading" className="text-[13px] font-semibold text-text">
        Try the demo — no account needed
      </p>
      <p className="mt-0.5 text-[12.5px] leading-relaxed text-text-muted">
        A live workspace with seeded tickets. Pick who you want to be:
      </p>
      <div className="mt-3 grid gap-2">
        {roles.map((role) => {
          const copy = ROLE_COPY[role]
          return (
            <button
              key={role}
              type="button"
              onClick={() => void enter(role)}
              disabled={pending !== null}
              className={cn(
                'group flex items-center gap-3 rounded-lg border border-border bg-surface px-3 py-2.5 text-left transition-colors',
                'hover:border-border-strong hover:bg-surface-2 disabled:opacity-60',
                role === 'AGENT' && 'border-primary/40',
              )}
            >
              <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-surface-2 text-text-muted">
                <copy.icon className="size-4" aria-hidden />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-[13.5px] font-medium text-text">
                  Explore as {copy.label}
                  {role === 'AGENT' && <span className="ml-1.5 text-[11.5px] font-normal text-primary">recommended</span>}
                </span>
                <span className="block truncate text-[12px] text-text-subtle">{copy.hint}</span>
              </span>
              <ArrowRight
                className={cn('size-4 shrink-0 text-text-subtle transition-transform group-hover:translate-x-0.5', pending === role && 'animate-pulse')}
                aria-hidden
              />
            </button>
          )
        })}
      </div>
      {error && (
        <p role="alert" className="mt-2 text-[12.5px] text-danger">
          {error}
        </p>
      )}
    </section>
  )
}
