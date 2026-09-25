import * as Popover from '@radix-ui/react-popover'
import { FileText, PanelRightOpen } from 'lucide-react'
import * as React from 'react'
import { Badge } from '@/components/ui/badge'
import { DocumentDrawer } from '@/features/knowledge/document-drawer'
import type { CitationView } from '@/lib/types'

const SOURCE_LABEL: Record<CitationView['source'], string> = {
  RUNBOOK: 'Runbook',
  ARTICLE: 'Article',
  RESOLVED_TICKET: 'Resolved ticket',
}

const SOURCE_VARIANT: Record<CitationView['source'], 'primary' | 'neutral' | 'success'> = {
  RUNBOOK: 'primary',
  ARTICLE: 'neutral',
  RESOLVED_TICKET: 'success',
}

/**
 * The cited span, on demand — doc 06 component #11. The snippet came down with the draft,
 * so the popover itself needs no fetch. "Open document" shows the whole source with the
 * span highlighted (audit N1/N5) instead of quoting character offsets at the agent.
 */
export function CitationPopover({ citation }: { citation: CitationView }) {
  const [drawer, setDrawer] = React.useState(false)
  return (
    <>
      <Popover.Root>
        <Popover.Trigger asChild>
          <button
            type="button"
            className="inline-flex min-h-6 items-center gap-1 rounded-sm bg-success-bg px-1.5 py-0.5 text-xs font-medium text-success hover:bg-success/15"
          >
            ✓ {citation.documentTitle}
          </button>
        </Popover.Trigger>
        <Popover.Portal>
          <Popover.Content
            side="top"
            align="start"
            sideOffset={6}
            className="z-50 w-72 rounded-lg border border-border bg-surface p-3 shadow-popover animate-fade-in"
          >
            <div className="mb-1.5 flex items-center justify-between gap-2">
              <span className="flex items-center gap-1.5 text-xs font-semibold text-text">
                <FileText className="size-3.5 text-text-subtle" aria-hidden />
                {citation.documentTitle}
              </span>
              <Badge variant={SOURCE_VARIANT[citation.source]}>{SOURCE_LABEL[citation.source]}</Badge>
            </div>
            <blockquote className="rounded-md border-l-2 border-success bg-success-bg/40 px-2.5 py-2 text-xs leading-relaxed text-text">
              {citation.snippet}
            </blockquote>
            <Popover.Close asChild>
              <button
                type="button"
                onClick={() => setDrawer(true)}
                className="mt-2 inline-flex min-h-7 items-center gap-1.5 rounded-md px-1.5 text-xs font-medium text-primary hover:bg-primary-bg"
              >
                <PanelRightOpen className="size-3.5" aria-hidden /> Open document
              </button>
            </Popover.Close>
            <Popover.Arrow className="fill-surface" />
          </Popover.Content>
        </Popover.Portal>
      </Popover.Root>
      {drawer && (
        <DocumentDrawer
          documentId={citation.documentId}
          highlight={{ start: citation.charStart, end: citation.charEnd, snippet: citation.snippet }}
          onClose={() => setDrawer(false)}
        />
      )}
    </>
  )
}
