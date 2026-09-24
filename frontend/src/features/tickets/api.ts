import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import type { AnalysisView, CursorPage, DraftView, PriorityRationaleView, TicketDetail, TicketSummary } from '@/lib/types'

export interface QueueFilters {
  status?: string[]
  priority?: string[]
  assigneeId?: string
  q?: string
  cursor?: string
  size?: number
  requesterId?: number
  sort?: 'created_at' | 'priority' | 'updated_at'
  order?: 'asc' | 'desc'
}

export function useTicketQueue(filters: QueueFilters, opts?: { enabled?: boolean }) {
  return useQuery({
    queryKey: ['tickets', filters],
    enabled: opts?.enabled ?? true,
    queryFn: async () => {
      const params = new URLSearchParams()
      filters.status?.forEach((s) => params.append('status', s))
      filters.priority?.forEach((p) => params.append('priority', p))
      if (filters.assigneeId) params.set('assigneeId', filters.assigneeId)
      if (filters.q) params.set('q', filters.q)
      if (filters.cursor) params.set('cursor', filters.cursor)
      if (filters.size) params.set('size', String(filters.size))
      if (filters.requesterId) params.set('requesterId', String(filters.requesterId))
      if (filters.sort) params.set('sort', filters.sort)
      if (filters.order) params.set('order', filters.order)
      const res = await api.get<CursorPage<TicketSummary>>(`/tickets?${params}`)
      return res.data
    },
    refetchInterval: 20_000,
  })
}

export function useTicket(id: string | number, opts?: { pollWhile?: (t: TicketDetail) => boolean }) {
  return useQuery({
    queryKey: ['ticket', String(id)],
    queryFn: async () => (await api.get<TicketDetail>(`/tickets/${id}?include=timeline`)).data,
    enabled: !!id,
    // e.g. the customer view polls until triage has set the response promise, so the page
    // updates by itself instead of saying "Open" until someone reloads it.
    refetchInterval: (query) =>
      opts?.pollWhile && query.state.data && opts.pollWhile(query.state.data) ? 3000 : false,
    // These polls only run while work is in flight, so let them finish in a background
    // tab too — otherwise switching away mid-draft left "Drafting…" on screen.
    refetchIntervalInBackground: true,
  })
}

export function useTicketAnalysis(id: string | number, opts?: { poll?: boolean }) {
  return useQuery({
    queryKey: ['ticket-analysis', id],
    queryFn: async () => (await api.get<AnalysisView>(`/tickets/${id}/analysis`)).data,
    enabled: !!id,
    refetchInterval: (query) => {
      if (!opts?.poll) return false
      return query.state.data?.status === 'PROCESSING' ? 3000 : false
    },
    refetchIntervalInBackground: true,
  })
}

export function useDraft(id: number | null) {
  return useQuery({
    queryKey: ['draft', id],
    queryFn: async () => (await api.get<DraftView>(`/drafts/${id}`)).data,
    enabled: !!id,
    refetchInterval: (query) => (query.state.data?.status === 'PENDING' ? 2000 : false),
    refetchIntervalInBackground: true,
  })
}

export function usePriorityRationale(id: string | number, opts?: { enabled?: boolean }) {
  return useQuery({
    queryKey: ['priority-rationale', String(id)],
    queryFn: async () => (await api.get<PriorityRationaleView>(`/tickets/${id}/priority-rationale`)).data,
    enabled: !!id && (opts?.enabled ?? true),
    staleTime: Infinity,
  })
}

export function useAssignTicket() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, etag, assigneeId, force }: { id: number; etag: string; assigneeId?: number; force?: boolean }) =>
      api.post(
        `/tickets/${id}/assign`,
        assigneeId ? { assigneeId: String(assigneeId), force: force || undefined } : {},
        { headers: { 'If-Match': etag } },
      ),
    onSuccess: (_d, vars) =>
      Promise.all([
        qc.invalidateQueries({ queryKey: ['tickets'] }),
        qc.invalidateQueries({ queryKey: ['ticket', String(vars.id)] }),
        qc.invalidateQueries({ queryKey: ['agents'] }),
      ]),
  })
}

export interface StaffMember {
  id: number
  fullName: string
  role: 'AGENT' | 'TEAM_LEAD'
  teamName: string | null
  openCount: number | null
  maxConcurrent: number | null
  available: boolean | null
}

/** Assignable staff (audit U7/U18) - names and current load for "Assign to…". */
export function useAgents(enabled = true) {
  return useQuery({
    queryKey: ['agents'],
    queryFn: async () => (await api.get<StaffMember[]>('/agents')).data,
    enabled,
    staleTime: 60_000,
  })
}

export function useRequestDraft() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ ticketId, instruction }: { ticketId: number; instruction?: string }) =>
      (await api.post(`/tickets/${ticketId}/drafts`, instruction ? { instruction } : {})).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['draft'] }),
  })
}

export function useSendMessage() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({
      ticketId,
      body,
      visibility,
      fromDraftId,
    }: {
      ticketId: number
      body: string
      visibility: 'PUBLIC' | 'INTERNAL'
      fromDraftId?: number
    }) => api.post(`/tickets/${ticketId}/messages`, { body, visibility, fromDraftId }),
    onSuccess: (_data, vars) => qc.invalidateQueries({ queryKey: ['ticket', String(vars.ticketId)] }),
  })
}

export function useChangeStatus() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({
      ticketId,
      status,
      reason,
      etag,
    }: {
      ticketId: number
      status: string
      reason?: string
      etag: string
    }) =>
      api.post(
        `/tickets/${ticketId}/status`,
        { status, reason },
        { headers: { 'If-Match': etag } },
      ),
    onSuccess: (_d, vars) => qc.invalidateQueries({ queryKey: ['ticket', String(vars.ticketId)] }),
  })
}

/**
 * The dedicated resolve endpoint - distinct from {@link useChangeStatus} because resolving
 * is not a bare status flip: it saves `resolution` as the closing customer-facing message
 * and is what triggers the knowledge-base indexing check on the resolved thread.
 */
export function useResolveTicket() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({
      ticketId,
      resolution,
      etag,
    }: {
      ticketId: number
      resolution: string
      etag: string
    }) =>
      api.post(
        `/tickets/${ticketId}/resolve`,
        { resolution },
        { headers: { 'If-Match': etag } },
      ),
    onSuccess: (_d, vars) => qc.invalidateQueries({ queryKey: ['ticket', String(vars.ticketId)] }),
  })
}

export function useCreateTicket() {
  return useMutation({
    mutationFn: async ({
      subject,
      body,
      idempotencyKey,
    }: {
      subject: string
      body: string
      idempotencyKey: string
    }) =>
      (
        await api.post(
          '/tickets',
          { subject, body },
          { headers: { 'Idempotency-Key': idempotencyKey } },
        )
      ).data,
  })
}

/**
 * Whether "Assign to me" can succeed: assignment needs an agent profile. Every AGENT has
 * one; a lead or admin only if they also work tickets - otherwise the server answers 404
 * and the button would be a broken promise.
 */
export function useCanSelfAssign(user: { id: number; role: string } | null | undefined) {
  const staffCheck = !!user && user.role !== 'AGENT' && user.role !== 'CUSTOMER'
  const agents = useAgents(staffCheck)
  if (!user || user.role === 'CUSTOMER') return false
  if (user.role === 'AGENT') return true
  return !!agents.data?.some((a) => a.id === user.id && a.openCount != null)
}
