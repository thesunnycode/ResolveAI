import { BookOpen, Plus, Search, Trash2, Upload } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import { Page, PageHeader } from '@/components/layout/page-header'
import { useDocumentTitle } from '@/components/layout/route-a11y'
import { Segmented } from '@/components/ui/segmented'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/app/confirm-dialog'
import { EmptyState, ErrorState } from '@/components/app/states'
import { SkeletonRow } from '@/components/ui/skeleton'
import { useToast } from '@/components/ui/toast'
import { useAuth } from '@/features/auth/auth-context'
import { ApiError } from '@/lib/api-client'
import { formatRelativeTime } from '@/lib/utils'
import * as React from 'react'
import type { KnowledgeDocumentSummary } from '@/lib/types'
import { useDeleteKnowledgeDocument, useKnowledgeDocuments } from './api'
import { DocumentDrawer } from './document-drawer'

const SOURCE_VARIANT = { RUNBOOK: 'primary', ARTICLE: 'neutral', RESOLVED_TICKET: 'success' } as const
const SOURCE_LABEL = { RUNBOOK: 'Runbook', ARTICLE: 'Article', RESOLVED_TICKET: 'Resolved ticket' } as const
type SourceFilter = 'all' | KnowledgeDocumentSummary['source']

export function KnowledgeBasePage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const { push } = useToast()
  useDocumentTitle('Knowledge base')
  // Audit N4: a source filter (server-side) and a title search (on the loaded page).
  const [source, setSource] = React.useState<SourceFilter>('all')
  const [query, setQuery] = React.useState('')
  const [preview, setPreview] = React.useState<number | null>(null)
  const { data, isLoading, isError, error, refetch } = useKnowledgeDocuments(source === 'all' ? undefined : source)
  const deleteDoc = useDeleteKnowledgeDocument()
  const [deleteTarget, setDeleteTarget] = React.useState<number | null>(null)
  const all = data?.data ?? []
  const q = query.trim().toLowerCase()
  const docs = q ? all.filter((d) => d.title.toLowerCase().includes(q)) : all
  const filtering = source !== 'all' || q !== ''

  return (
    <Page>
      <PageHeader
        title="Knowledge base"
        description="Runbooks, help articles and resolved tickets. Every AI draft is grounded in — and cites — these documents."
        actions={
          user?.role === 'ADMIN' && (
            <div className="flex gap-2">
              <Button variant="secondary" asChild>
                <Link to="/knowledge/bulk-import">
                  <Upload className="size-4" aria-hidden /> Bulk import
                </Link>
              </Button>
              <Button asChild>
                <Link to="/knowledge/new">
                  <Plus className="size-4" aria-hidden /> Add document
                </Link>
              </Button>
            </div>
          )
        }
      />

      <div className="mb-3 flex flex-col gap-2.5 sm:flex-row sm:items-center">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search by title…"
            aria-label="Search documents"
            className="h-10 w-full rounded-lg border border-input bg-card pl-9 pr-3 text-base text-foreground placeholder:text-muted-foreground focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-primary"
          />
        </div>
        <Segmented
          label="Source"
          value={source}
          onChange={setSource}
          options={[
            { value: 'all', label: 'All' },
            { value: 'RUNBOOK', label: 'Runbooks' },
            { value: 'ARTICLE', label: 'Articles' },
            { value: 'RESOLVED_TICKET', label: 'Resolved' },
          ]}
        />
      </div>

      <div className="glass overflow-hidden rounded-xl">
        {isError && (
          <div className="p-4">
            <ErrorState error={error} onRetry={() => refetch()} />
          </div>
        )}
        {isLoading ? (
          <SkeletonRow count={5} />
        ) : docs.length === 0 && filtering ? (
          <EmptyState
            icon={Search}
            title="No documents match"
            body="Try a different word, or show every source."
            action={
              <Button variant="secondary" size="sm" onClick={() => { setQuery(''); setSource('all') }}>
                Clear filters
              </Button>
            }
          />
        ) : docs.length === 0 ? (
          <EmptyState
            icon={BookOpen}
            title="No documents yet"
            body={
              user?.role === 'ADMIN'
                ? 'Runbooks and articles you add here ground every AI draft — with none, every draft is withheld. Start with your most-asked question.'
                : 'Runbooks and articles here ground every AI draft. Your workspace admin adds them; until they do, drafts are withheld.'
            }
            action={
              user?.role === 'ADMIN' ? (
                <Button variant="secondary" size="sm" onClick={() => navigate('/knowledge/new')}>
                  Add your first article
                </Button>
              ) : undefined
            }
          />
        ) : (
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="border-b border-border text-xs uppercase tracking-[0.08em] text-muted-foreground">
                <th className="px-5 py-3 font-medium">Title</th>
                <th className="px-4 py-2.5 font-medium">Source</th>
                <th className="px-4 py-2.5 font-medium">Chunks</th>
                <th className="px-4 py-2.5 font-medium">Status</th>
                <th className="px-4 py-2.5" />
              </tr>
            </thead>
            <tbody>
              {docs.map((d) => (
                <tr key={d.id} className="border-b border-border last:border-0 transition-colors hover:bg-muted/50">
                  <td className="px-5 py-3.5 font-medium text-foreground">
                    <button type="button" onClick={() => setPreview(d.id)} className="text-left hover:text-primary hover:underline">
                      {d.title}
                    </button>
                  </td>
                  <td className="px-4 py-2.5">
                    <Badge variant={SOURCE_VARIANT[d.source]}>{SOURCE_LABEL[d.source]}</Badge>
                  </td>
                  <td className="px-4 py-2.5 text-muted-foreground">{d.chunkCount}</td>
                  <td className="px-4 py-2.5 text-muted-foreground">
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
                      <button
                        onClick={() => setDeleteTarget(d.id)}
                        className="inline-flex size-8 items-center justify-center rounded-md text-muted-foreground hover:bg-danger-soft hover:text-danger"
                        aria-label={`Delete ${d.title}`}
                      >
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

      <DocumentDrawer documentId={preview} onClose={() => setPreview(null)} />

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
