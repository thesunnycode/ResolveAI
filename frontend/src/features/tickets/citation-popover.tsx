import * as Popover from '@radix-ui/react-popover'
import { FileText } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
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
 * The cited span, on demand — doc 06 component #11. The snippet and its character
 * offsets already came down with the draft (see {@code DraftResponse.CitationView}'s own
 * doc: "the UI popover needs no second call"), so this is pure presentation, no fetch.
 */
export function CitationPopover({ citation }: { citation: CitationView }) {
  return (
    <Popover.Root>
      <Popover.Trigger asChild>
        <button
          type="button"
          className="inline-flex items-center gap-1 rounded-sm bg-success-bg px-1.5 py-0.5 text-[11px] font-medium text-success hover:bg-success/15"
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
            <span className="flex items-center gap-1.5 text-[12px] font-semibold text-text">
              <FileText className="size-3.5 text-text-subtle" aria-hidden />
              {citation.documentTitle}
            </span>
            <Badge variant={SOURCE_VARIANT[citation.source]}>{SOURCE_LABEL[citation.source]}</Badge>
          </div>
          <blockquote className="rounded-md border-l-2 border-success bg-success-bg/40 px-2.5 py-2 text-[12px] leading-relaxed text-text">
            {citation.snippet}
          </blockquote>
          <p className="mt-1.5 text-[11px] text-text-subtle">
            Characters {citation.charStart}–{citation.charEnd} of the source document
          </p>
          <Popover.Arrow className="fill-surface" />
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}
