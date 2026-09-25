import { useQueryClient } from '@tanstack/react-query'
import * as React from 'react'
import { useToast } from '@/components/ui/toast'
import { ApiError, api } from '@/lib/api-client'
import type { TicketStatus } from '@/lib/types'
import { useChangeStatus } from './api'
import { DEFERRED, REASON_REQUIRED, STATUS_LABEL } from './status-labels'
import { allowedTransitionsFrom } from './ticket-state-machine'

const HOLD_MS = 5000

export interface StatusChangeRequest {
  ticketId: number
  reference: string
  from: TicketStatus
  to: TicketStatus
  reason?: string
  /** Called when the change finishes, fails, or is undone - to clear any pending UI. */
  onSettled?: () => void
}

/**
 * Status changes with a way back (audit U14/F3). A mis-click on "Resolved" used to notify
 * a customer instantly with no undo.
 *
 * - Resolved / Closed are held for five seconds behind an "Undo" toast, then committed -
 *   the Gmail undo-send pattern, because the state machine has no way back from them.
 * - Every other move commits at once; its toast offers Undo only when the reverse move is
 *   actually allowed, so the button never promises something the server will refuse.
 */
export function useStatusChanger() {
  const change = useChangeStatus()
  const qc = useQueryClient()
  const { push } = useToast()
  const timers = React.useRef(new Map<number, number>())

  React.useEffect(() => {
    const pending = timers.current
    // Leaving the page commits nothing silently: pending moves are cancelled.
    return () => pending.forEach((t) => window.clearTimeout(t))
  }, [])

  const commit = React.useCallback(
    async (ticketId: number, to: TicketStatus, reason?: string) => {
      const res = await api.get(`/tickets/${ticketId}`)
      await change.mutateAsync({ ticketId, status: to, reason, etag: res.headers.etag })
      await Promise.all([
        qc.invalidateQueries({ queryKey: ['board-tickets'] }),
        qc.invalidateQueries({ queryKey: ['tickets'] }),
      ])
    },
    [change, qc],
  )

  const request = React.useCallback(
    (r: StatusChangeRequest) => {
      const fail = (err: unknown) => {
        push('error', err instanceof ApiError ? err.problem.detail : 'Could not change the status.')
        r.onSettled?.()
      }

      if (DEFERRED.includes(r.to)) {
        const timer = window.setTimeout(() => {
          timers.current.delete(r.ticketId)
          commit(r.ticketId, r.to, r.reason)
            .then(() => {
              push('success', `${r.reference} is ${STATUS_LABEL[r.to].toLowerCase()}.`)
              r.onSettled?.()
            })
            .catch(fail)
        }, HOLD_MS)
        timers.current.set(r.ticketId, timer)
        push('info', `Marking ${r.reference} ${STATUS_LABEL[r.to].toLowerCase()}…`, {
          duration: HOLD_MS,
          action: {
            label: 'Undo',
            onClick: () => {
              window.clearTimeout(timer)
              timers.current.delete(r.ticketId)
              r.onSettled?.()
              push('info', `${r.reference} stays ${STATUS_LABEL[r.from].toLowerCase()}.`)
            },
          },
        })
        return
      }

      commit(r.ticketId, r.to, r.reason)
        .then(() => {
          const reversible = allowedTransitionsFrom(r.to).includes(r.from) && !REASON_REQUIRED.includes(r.from)
          push('success', `${r.reference} moved to ${STATUS_LABEL[r.to]}.`, {
            action: reversible
              ? {
                  label: 'Undo',
                  onClick: () =>
                    commit(r.ticketId, r.from)
                      .then(() => push('info', `${r.reference} is back to ${STATUS_LABEL[r.from]}.`))
                      .catch(fail),
                }
              : undefined,
          })
          r.onSettled?.()
        })
        .catch(fail)
    },
    [commit, push],
  )

  return { request, pending: change.isPending }
}
