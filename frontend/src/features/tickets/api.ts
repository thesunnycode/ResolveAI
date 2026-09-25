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
}

export function useTicketQueue(filters: QueueFilters) {
  return useQuery({
    queryKey: ['tickets', filters],
    queryFn: async () => {
      const params = new URLSearchParams()
      filters.status?.forEach((s) => params.append('status', s))
      filters.priority?.forEach((p) => params.append('priority', p))
      if (filters.assigneeId) params.set('assigneeId', filters.assigneeId)
      if (filters.q) params.set('q', filters.q)
      if (filters.cursor) params.set('cursor', filters.cursor)
      if (filters.size) params.set('size', String(filters.size))
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
    mutationFn: async ({ id, etag }: { id: number; etag: string }) =>
      api.post(`/tickets/${id}/assign`, {}, { headers: { 'If-Match': etag } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['tickets'] }),
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
