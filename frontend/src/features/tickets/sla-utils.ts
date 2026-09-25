import type { SlaChipClock, SlaSummary } from '@/lib/types'

/**
 * The clock an agent has to beat next, and what to call it.
 *
 * First response is always the shorter promise, so until it is MET it is the one that
 * runs out first — the queue used to show the resolution clock whenever one existed, so a
 * new P1 read "4h left" while its first reply was due in 30 minutes.
 */
export function primarySlaClock(sla: SlaSummary | null | undefined): { clock: SlaChipClock; label: string } | null {
  if (!sla) return null
  if (sla.firstResponse && sla.firstResponse.state !== 'MET') return { clock: sla.firstResponse, label: '1st reply' }
  if (sla.resolution) return { clock: sla.resolution, label: 'Resolve' }
  if (sla.firstResponse) return { clock: sla.firstResponse, label: '1st reply' }
  return null
}
