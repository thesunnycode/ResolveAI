import * as DropdownMenu from '@radix-ui/react-dropdown-menu'
import { Check, ChevronDown, UserRound } from 'lucide-react'
import { useToast } from '@/components/ui/toast'
import { ApiError, api } from '@/lib/api-client'
import type { UserRef } from '@/lib/types'
import { cn } from '@/lib/utils'
import { useAgents, useAssignTicket } from './api'

/**
 * The assignee as a control, not a label (audit U7). Agents decide to take a ticket after
 * reading it, so "Assign to me" lives in the ticket header - Missive's pattern. Leads also
 * get "Assign to…" with each agent's current load, which is what they'd check anyway.
 */
export function AssignControl({
  ticketId,
  reference,
  assignee,
  currentUserId,
  canAssignOthers,
  canSelfAssign,
}: {
  ticketId: number
  reference: string
  assignee: UserRef | null
  currentUserId?: number
  canAssignOthers: boolean
  canSelfAssign: boolean
}) {
  const assign = useAssignTicket()
  const agents = useAgents(canAssignOthers)
  const { push } = useToast()
  const mine = assignee && assignee.id === currentUserId

  async function run(assigneeId?: number, name?: string) {
    try {
      const res = await api.get(`/tickets/${ticketId}`)
      await assign.mutateAsync({ id: ticketId, etag: res.headers.etag, assigneeId, force: !!assignee })
      push('success', assigneeId ? `${reference} assigned to ${name}.` : `${reference} is yours.`)
    } catch (err) {
      push('error', err instanceof ApiError ? err.problem.detail : 'Could not assign this ticket.')
    }
  }

  // Agents without lead rights: a single button, only while nobody owns the ticket.
  if (!canAssignOthers) {
    if (assignee || !canSelfAssign) {
      return (
        <span className="flex items-center gap-1 text-muted-foreground">
          <UserRound className="size-3.5" aria-hidden /> {mine ? 'You' : (assignee?.fullName ?? 'Unassigned')}
        </span>
      )
    }
    return (
      <button
        type="button"
        onClick={() => void run()}
        disabled={assign.isPending}
        className="inline-flex min-h-8 items-center gap-1.5 rounded-md border border-dashed border-warning/60 px-2.5 text-sm font-medium text-warning hover:bg-warning-soft disabled:opacity-60"
      >
        <UserRound className="size-3.5" aria-hidden /> Unassigned · Assign to me
      </button>
    )
  }

  const assignable = (agents.data ?? []).filter((a) => a.openCount != null)

  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger
        disabled={assign.isPending}
        aria-label={`Assignee: ${assignee?.fullName ?? 'unassigned'}. Change assignee`}
        className={cn(
          'glass inline-flex min-h-8 items-center gap-1.5 rounded-md px-2.5 text-sm font-medium hover:border-border focus-visible:outline-2 focus-visible:outline-primary disabled:opacity-60',
          assignee ? 'text-foreground' : 'text-warning',
        )}
      >
        <UserRound className="size-3.5" aria-hidden />
        {assignee ? (mine ? 'You' : assignee.fullName) : 'Unassigned'}
        <ChevronDown className="size-3.5 text-muted-foreground" aria-hidden />
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align="start"
          sideOffset={4}
          className="z-50 max-h-80 min-w-60 overflow-y-auto rounded-md border border-border bg-card p-1 shadow-popover"
        >
          {!mine && canSelfAssign && (
            <DropdownMenu.Item
              onSelect={() => void run()}
              className="rounded px-2.5 py-2 text-sm font-medium text-foreground outline-none data-[highlighted]:bg-muted"
            >
              Assign to me
            </DropdownMenu.Item>
          )}
          <DropdownMenu.Label className="px-2.5 pb-1 pt-2 text-xs text-muted-foreground">Assign to…</DropdownMenu.Label>
          {agents.isLoading && <p className="px-2.5 py-2 text-sm text-muted-foreground">Loading agents…</p>}
          {assignable.map((a) => {
            const full = a.maxConcurrent != null && (a.openCount ?? 0) >= a.maxConcurrent
            return (
              <DropdownMenu.Item
                key={a.id}
                disabled={a.id === assignee?.id}
                onSelect={() => void run(a.id, a.fullName)}
                className="flex items-center gap-2 rounded px-2.5 py-2 text-sm text-foreground outline-none data-[disabled]:opacity-60 data-[highlighted]:bg-muted"
              >
                <span className="flex-1">
                  {a.fullName}
                  {a.teamName && <span className="ml-1.5 text-xs text-muted-foreground">{a.teamName}</span>}
                </span>
                <span className={cn('text-xs tabular-nums', full ? 'text-warning' : 'text-muted-foreground')}>
                  {a.openCount}/{a.maxConcurrent ?? '–'} open{a.available === false ? ' · away' : ''}
                </span>
                {a.id === assignee?.id && <Check className="size-3.5 text-primary" aria-label="current" />}
              </DropdownMenu.Item>
            )
          })}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  )
}
