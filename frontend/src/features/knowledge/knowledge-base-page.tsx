import { BookOpen, Plus, Trash2 } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Page, PageHeader } from '@/components/layout/page-header'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { EmptyState } from '@/components/ui/empty-state'
import { ErrorBanner } from '@/components/ui/error-banner'
import { SkeletonRow } from '@/components/ui/skeleton'
import { useToast } from '@/components/ui/toast'
import { useAuth } from '@/features/auth/auth-context'
import { ApiError } from '@/lib/api-client'
import { formatRelativeTime } from '@/lib/utils'
import * as React from 'react'
import { useDeleteKnowledgeDocument, useKnowledgeDocuments } from './api'

const SOURCE_VARIANT = { RUNBOOK: 'primary', ARTICLE: 'neutral', RESOLVED_TICKET: 'success' } as const

export function KnowledgeBasePage() {
  const { user } = useAuth()
  const { push } = useToast()
  const { data, isLoading, isError, refetch } = useKnowledgeDocuments()
  const deleteDoc = useDeleteKnowledgeDocument()
  const [deleteTarget, setDeleteTarget] = React.useState<number | null>(null)
  const docs = data?.data ?? []

  return (
    <Page>
      <PageHeader
        title="Knowledge base"
        description="Runbooks, help articles and resolved tickets. Every AI draft is grounded in — and cites — these documents."
        actions={
          user?.role === 'ADMIN' && (
            <Button asChild>
              <Link to="/knowledge/new">
                <Plus className="size-4" aria-hidden /> Add document
              </Link>
            </Button>
          )
        }
      />

      <div className="glass overflow-hidden rounded-xl">
        {isError && (
          <div className="p-4">
            <ErrorBanner onRetry={() => refetch()} />
          </div>
        )}
        {isLoading ? (
          <SkeletonRow count={5} />
        ) : docs.length === 0 ? (
          <EmptyState icon={BookOpen} title="No documents yet" description="Runbooks and articles you add here ground every AI draft." />
        ) : (
          <table className="w-full text-left text-[13px]">
            <thead>
              <tr className="border-b border-border text-[11.5px] uppercase tracking-[0.08em] text-text-subtle">
                <th className="px-5 py-3 font-medium">Title</th>
                <th className="px-4 py-2.5 font-medium">Source</th>
                <th className="px-4 py-2.5 font-medium">Chunks</th>
                <th className="px-4 py-2.5 font-medium">Status</th>
                <th className="px-4 py-2.5" />
              </tr>
            </thead>
            <tbody>
              {docs.map((d) => (
                <tr key={d.id} className="border-b border-border last:border-0 transition-colors hover:bg-surface-2/50">
                  <td className="px-5 py-3.5 font-medium text-text">{d.title}</td>
                  <td className="px-4 py-2.5">
                    <Badge variant={SOURCE_VARIANT[d.source]}>{d.source}</Badge>
                  </td>
                  <td className="px-4 py-2.5 text-text-muted">{d.chunkCount}</td>
                  <td className="px-4 py-2.5 text-text-muted">
                    {d.indexed ? (
                      <span className="inline-flex items-center gap-1.5">
                        <span className="size-1.5 rounded-full bg-success" />
                        Ready &middot; added {formatRelativeTime(d.createdAt)}
                      </span>
                    ) : (
                      'Indexing…'
                    )}
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    {user?.role === 'ADMIN' && (
                      <button onClick={() => setDeleteTarget(d.id)} className="text-text-subtle hover:text-danger" aria-label="Delete">
                        <Trash2 className="size-3.5" aria-hidden />
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <ConfirmDialog
        open={deleteTarget !== null}
        onOpenChange={(o) => !o && setDeleteTarget(null)}
        title="Delete this document?"
        consequences={['Its chunks are removed from retrieval immediately.']}
        confirmLabel="Delete"
        destructive
        onConfirm={async () => {
          if (!deleteTarget) return
          try {
            await deleteDoc.mutateAsync(deleteTarget)
            push('success', 'Document deleted.')
          } catch (err) {
            push('error', err instanceof ApiError ? err.problem.detail : 'Could not delete.')
          }
        }}
      />
    </Page>
  )
}
