import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import type { AdminAiPolicy, AdminCalendar, AdminSlaPolicyRow } from '@/lib/types'

export function useSlaPolicies() {
  return useQuery({
    queryKey: ['admin', 'sla-policies'],
    queryFn: async () => (await api.get<AdminSlaPolicyRow[]>('/admin/sla-policies')).data,
  })
}

export function useUpdateSlaPolicy() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({
      priority,
      firstResponseMinutes,
      resolutionMinutes,
    }: {
      priority: string
      firstResponseMinutes: number
      resolutionMinutes: number
    }) =>
      (
        await api.put<AdminSlaPolicyRow>(`/admin/sla-policies/${priority}`, {
          firstResponseMinutes,
          resolutionMinutes,
        })
      ).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'sla-policies'] }),
  })
}

export function useCalendar() {
  return useQuery({
    queryKey: ['admin', 'calendar'],
    queryFn: async () => (await api.get<AdminCalendar>('/admin/calendar')).data,
  })
}

export function useUpdateCalendar() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (body: AdminCalendar) => (await api.put<AdminCalendar>('/admin/calendar', body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'calendar'] }),
  })
}

export function useAiPolicy() {
  return useQuery({
    queryKey: ['admin', 'ai-policy'],
    queryFn: async () => (await api.get<AdminAiPolicy>('/admin/ai-policy')).data,
  })
}

export function useUpdateAiPolicy() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (body: {
      externalModelAllowed: boolean
      allowedProviders: string[]
      piiRedactionRequired: boolean
      monthlyBudgetMicros: number
      retentionDays: number
    }) => (await api.put<AdminAiPolicy>('/admin/ai-policy', body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'ai-policy'] }),
  })
}
