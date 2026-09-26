import * as Dialog from '@radix-ui/react-dialog'
import { ExternalLink, FileText, X } from 'lucide-react'
import * as React from 'react'
import { Badge } from '@/components/ui/badge'
import { ErrorState } from '@/components/app/states'
import { Skeleton } from '@/components/ui/skeleton'
import { formatRelativeTime } from '@/lib/utils'
import { useKnowledgeDocument } from './api'

const SOURCE_LABEL = { RUNBOOK: 'Runbook', ARTICLE: 'Article', RESOLVED_TICKET: 'Resolved ticket' } as const
const SOURCE_VARIANT = { RUNBOOK: 'primary', ARTICLE: 'neutral', RESOLVED_TICKET: 'success' } as const

/**
 * The whole source document beside the draft (audit N5), with the cited span highlighted
 * and scrolled into view - so an agent checks a claim in context, not from a 300-character
 * excerpt. Offsets come from the citation; if the body has changed since, the snippet
 * itself is searched for instead.
 */
export function DocumentDrawer({
  documentId,
  highlight,
  onClose,
}: {
  documentId: number | null
  highlight?: { start: number; end: number; snippet?: string }
  onClose: () => void
}) {
  const { data: doc, isLoading, isError, error, refetch } = useKnowledgeDocument(documentId)
  const markRef = React.useRef<HTMLElement>(null)

  const range = React.useMemo(() => {
    if (!doc || !highlight) return null
    const { start, end, snippet } = highlight
    if (start >= 0 && end <= doc.body.length && end > start) {
      const slice = doc.body.slice(start, end)
      if (!snippet || slice.trim().slice(0, 24) === snippet.trim().slice(0, 24)) return [start, end] as const
    }
    const at = snippet ? doc.body.indexOf(snippet.trim().slice(0, 80)) : -1
    return at >= 0 ? ([at, at + (snippet?.trim().length ?? 0)] as const) : null
  }, [doc, highlight])

  React.useEffect(() => {
    if (range) requestAnimationFrame(() => markRef.current?.scrollIntoView({ block: 'center' }))
  }, [range])

  return (
    <Dialog.Root open={documentId != null} onOpenChange={(o) => !o && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/30 animate-fade-in" />
        <Dialog.Content className="fixed inset-y-0 right-0 z-50 flex w-full max-w-xl flex-col border-l border-border bg-popover shadow-popover focus:outline-none">
          <div className="flex items-start gap-3 border-b border-border px-5 py-4">
            <FileText className="mt-1 size-4 shrink-0 text-muted-foreground" aria-hidden />
            <div className="min-w-0 flex-1">
              <Dialog.Title className="text-lg font-semibold leading-snug text-foreground">
                {doc?.title ?? 'Source document'}
              </Dialog.Title>
              <Dialog.Description className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                {doc ? (
                  <>
                    <Badge variant={SOURCE_VARIANT[doc.source]}>{SOURCE_LABEL[doc.source]}</Badge>
                    <span>Updated {formatRelativeTime(doc.updatedAt)}</span>
                    {range && <span>· The cited passage is highlighted</span>}
                  </>
                ) : (
                  'Loading the document'
                )}
              </Dialog.Description>
            </div>
            {doc?.uri && (
              <a
                href={doc.uri}
                target="_blank"
                rel="noreferrer"
                className="flex size-8 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground"
                aria-label="Open the original in a new tab"
              >
                <ExternalLink className="size-4" aria-hidden />
              </a>
            )}
            <Dialog.Close
              className="flex size-8 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground"
              aria-label="Close"
            >
              <X className="size-4" aria-hidden />
            </Dialog.Close>
          </div>
          <div className="flex-1 overflow-y-auto px-5 py-4">
            {isError ? (
              <ErrorState error={error} onRetry={() => refetch()} />
            ) : isLoading || !doc ? (
              <div className="space-y-2" aria-busy="true">
                <Skeleton className="h-4 w-full" />
                <Skeleton className="h-4 w-5/6" />
                <Skeleton className="h-4 w-4/6" />
              </div>
            ) : (
              <p className="whitespace-pre-wrap text-sm leading-relaxed text-foreground">
                {range ? (
                  <>
                    {doc.body.slice(0, range[0])}
                    <mark ref={markRef} className="rounded-sm bg-success-soft px-0.5 text-foreground ring-1 ring-success/40">
                      {doc.body.slice(range[0], range[1])}
                    </mark>
                    {doc.body.slice(range[1])}
                  </>
                ) : (
                  doc.body
                )}
              </p>
            )}
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
