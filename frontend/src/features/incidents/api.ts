import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import type { CursorPage, IncidentDetail, IncidentSummary } from '@/lib/types'

export function useIncidents(status?: string) {
  return useQuery({
    queryKey: ['incidents', status],
    queryFn: async () => {
      const params = status ? `?status=${status}` : ''
      return (await api.get<CursorPage<IncidentSummary>>(`/incidents${params}`)).data
    },
    refetchInterval: 30_000,
  })
}

export function useIncident(id: string | number) {
  return useQuery({
    queryKey: ['incident', id],
    queryFn: async () => (await api.get<IncidentDetail>(`/incidents/${id}`)).data,
    enabled: !!id,
  })
}

export function useConfirmIncident() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, etag }: { id: number; etag: string }) =>
      api.post(`/incidents/${id}/confirm`, {}, { headers: { 'If-Match': etag } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['incidents'] }),
  })
}

export function useRejectIncident() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, reason, etag }: { id: number; reason: string; etag: string }) =>
      api.post(`/incidents/${id}/reject`, { reason }, { headers: { 'If-Match': etag } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['incidents'] }),
  })
}

export function usePublishUpdate() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({
      incidentId,
      body,
      visibility,
      idempotencyKey,
    }: {
      incidentId: number
      body: string
      visibility: 'PUBLIC' | 'INTERNAL'
      idempotencyKey: string
    }) =>
      (
        await api.post(
          `/incidents/${incidentId}/updates`,
          { body, visibility },
          { headers: { 'Idempotency-Key': idempotencyKey } },
        )
      ).data,
    onSuccess: (_d, vars) => qc.invalidateQueries({ queryKey: ['incident', vars.incidentId] }),
  })
}

export function useResolveIncident() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, resolutionNote, etag }: { id: number; resolutionNote: string; etag?: string }) =>
      api.post(
        `/incidents/${id}/resolve`,
        { resolutionNote, resolveLinkedTickets: false },
        etag ? { headers: { 'If-Match': etag } } : undefined,
      ),
    onSuccess: (_d, vars) => {
      qc.invalidateQueries({ queryKey: ['incident', vars.id] })
      qc.invalidateQueries({ queryKey: ['incidents'] })
    },
  })
}

export function useDetachTicket() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ incidentId, ticketId }: { incidentId: number; ticketId: number }) =>
      api.delete(`/incidents/${incidentId}/tickets/${ticketId}`),
    onSuccess: (_d, vars) => qc.invalidateQueries({ queryKey: ['incident', vars.incidentId] }),
  })
}
