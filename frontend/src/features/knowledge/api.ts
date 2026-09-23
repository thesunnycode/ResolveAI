import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import type { CursorPage, KnowledgeDocumentSummary } from '@/lib/types'

export function useKnowledgeDocuments() {
  return useQuery({
    queryKey: ['knowledge-documents'],
    queryFn: async () => (await api.get<CursorPage<KnowledgeDocumentSummary>>('/knowledge/documents')).data,
  })
}

export function useCreateKnowledgeDocument() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (body: { title: string; source: string; body: string; uri?: string }) =>
      (await api.post('/knowledge/documents', body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['knowledge-documents'] }),
  })
}

export function useDeleteKnowledgeDocument() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (id: number) => api.delete(`/knowledge/documents/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['knowledge-documents'] }),
  })
}
