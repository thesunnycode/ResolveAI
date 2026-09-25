import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import type { Role } from '@/lib/types'

/**
 * Demo mode (backend: com.resolveai.demo). When the deployment has no demo, GET /demo is a
 * 404 and every hook here reports "not enabled" — the app then looks exactly as it would
 * without this module.
 */
export interface DemoInfo {
  enabled: boolean
  tenantSlug: string
  roles: Role[]
}

export interface ShowcaseItem {
  id: number
  reference: string
  title: string
}

export interface Showcase {
  pausedTicket: ShowcaseItem | null
  draftTicket: ShowcaseItem | null
  incident: ShowcaseItem | null
}

export function useDemoInfo() {
  return useQuery({
    queryKey: ['demo-info'],
    queryFn: async (): Promise<DemoInfo> => {
      try {
        return (await api.get<DemoInfo>('/demo')).data
      } catch {
        return { enabled: false, tenantSlug: '', roles: [] }
      }
    },
    staleTime: 5 * 60_000,
    retry: false,
  })
}

/** True when the signed-in user is inside the demo workspace. */
export function useIsDemoWorkspace(tenantSlug: string | undefined) {
  const demo = useDemoInfo()
  return !!demo.data?.enabled && !!tenantSlug && demo.data.tenantSlug === tenantSlug
}

export function useShowcase(enabled: boolean) {
  return useQuery({
    queryKey: ['demo-showcase'],
    queryFn: async () => (await api.get<Showcase>('/demo/showcase')).data,
    enabled,
    retry: false,
    refetchInterval: 30_000,
  })
}

export function useStartStorm() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async () => (await api.post<{ status: string; detail: string }>('/demo/storm')).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['tickets'] })
      qc.invalidateQueries({ queryKey: ['incidents'] })
    },
  })
}
