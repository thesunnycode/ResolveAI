import { formatDateTime, formatRelativeTime } from '@/lib/utils'

/**
 * "12m ago" to scan, the exact moment on hover and for screen readers (audit N2) - SLA
 * disputes are argued in exact times, not in "2h ago".
 */
export function RelativeTime({ iso, className }: { iso: string; className?: string }) {
  const exact = formatDateTime(iso)
  return (
    <time dateTime={iso} title={exact} aria-label={exact} className={className}>
      {formatRelativeTime(iso)}
    </time>
  )
}
