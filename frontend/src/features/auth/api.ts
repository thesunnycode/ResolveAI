import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import type { Role, TenantSummary } from '@/lib/types'

/** The public "pick your business" list on the login/register pages. */
export function useBusinesses() {
  return useQuery({
    queryKey: ['tenants'],
    queryFn: async () => (await api.get<TenantSummary[]>('/tenants')).data,
    staleTime: 60_000,
  })
}

export interface InvitePreview {
  tenantName: string
  email: string
  role: Role
}

/** What the accept-invite page shows before anyone has typed anything. */
export function useInvitePreview(token: string | null) {
  return useQuery({
    queryKey: ['invite-preview', token],
    queryFn: async () => (await api.get<InvitePreview>(`/invites/${token}`)).data,
    enabled: !!token,
    retry: false,
  })
}
