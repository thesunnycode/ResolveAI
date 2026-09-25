import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import type { CursorPage, KnowledgeDocumentSummary } from '@/lib/types'

export function useKnowledgeDocuments(source?: KnowledgeDocumentSummary['source']) {
  return useQuery({
    queryKey: ['knowledge-documents', source ?? 'all'],
    queryFn: async () =>
      (await api.get<CursorPage<KnowledgeDocumentSummary>>(`/knowledge/documents?size=100${source ? `&source=${source}` : ''}`)).data,
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

export interface KnowledgeDocumentDetail {
  id: number
  source: KnowledgeDocumentSummary['source']
  title: string
  body: string
  uri: string | null
  indexed: boolean
  createdAt: string
  updatedAt: string
}

/** One document in full - the citation drawer (audit N5) and KB preview. */
export function useKnowledgeDocument(id: number | null) {
  return useQuery({
    queryKey: ['knowledge-document', id],
    queryFn: async () => (await api.get<KnowledgeDocumentDetail>(`/knowledge/documents/${id}`)).data,
    enabled: id != null,
    staleTime: 5 * 60_000,
  })
}
