import { Siren } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useToast } from '@/components/ui/toast'
import { useAuth } from '@/features/auth/auth-context'
import { ApiError } from '@/lib/api-client'
import { track } from '@/lib/analytics'
import { cn } from '@/lib/utils'
import { useIsDemoWorkspace, useStartStorm } from './api'

/**
 * `demo/storm.sh`, as a button: 38 tickets about one payment outage, posted over ~20s, so
 * the correlation sweep proposes an incident within about a minute. Team leads and admins
 * in the demo workspace only (the backend enforces both).
 */
export function SimulateOutageButton({ size = 'sm' }: { size?: 'sm' | 'xs' }) {
  const { user } = useAuth()
  const inDemo = useIsDemoWorkspace(user?.tenantSlug)
  const start = useStartStorm()
  const { push } = useToast()

  if (!inDemo || !(user?.role === 'TEAM_LEAD' || user?.role === 'ADMIN')) return null

  async function run() {
    try {
      const res = await start.mutateAsync()
      track('storm_started')
      push('success', `${res.detail} Watch the Incidents board.`)
    } catch (err) {
      push('info', err instanceof ApiError ? err.problem.detail : 'Could not start the simulation.')
    }
  }

  return (
    <Button
      variant="secondary"
      size="sm"
      loading={start.isPending}
      onClick={run}
      className={cn(size === 'xs' && 'h-7 px-2.5 text-xs')}
    >
      <Siren className="size-3.5" aria-hidden /> Simulate a payment outage
    </Button>
  )
}
