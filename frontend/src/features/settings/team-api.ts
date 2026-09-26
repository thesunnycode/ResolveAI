import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import type { AdminInvite, AdminTeam, AdminTeamMember, Role } from '@/lib/types'

export function useTeams() {
  return useQuery({
    queryKey: ['admin', 'teams'],
    queryFn: async () => (await api.get<AdminTeam[]>('/admin/teams')).data,
  })
}

export function useCreateTeam() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (body: { name: string; skills: string[] }) =>
      (await api.post<AdminTeam>('/admin/teams', body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'teams'] }),
  })
}

export function useTeamMembers() {
  return useQuery({
    queryKey: ['admin', 'members'],
    queryFn: async () => (await api.get<AdminTeamMember[]>('/admin/members')).data,
  })
}

export function useInvites() {
  return useQuery({
    queryKey: ['admin', 'invites'],
    queryFn: async () => (await api.get<AdminInvite[]>('/admin/invites')).data,
  })
}

export function useCreateInvite() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (body: { email: string; role: Role; teamId: number }) =>
      (await api.post<AdminInvite>('/admin/invites', body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'invites'] }),
  })
}
