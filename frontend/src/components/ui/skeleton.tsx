import { cn } from '@/lib/utils'

export function Skeleton({ className }: { className?: string }) {
  return <div aria-hidden className={cn('skeleton rounded-md', className)} />
}

/** Same height as a real TicketRow, per doc 06: "a page that jumps on load is worse than a spinner." */
export function SkeletonRow({ count = 5 }: { count?: number }) {
  return (
    <div className="divide-y divide-border" role="status" aria-busy="true" aria-label="Loading">
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className="flex items-center gap-3 px-4 py-3">
          <Skeleton className="h-8 w-1 shrink-0" />
          <div className="flex-1 space-y-2">
            <Skeleton className="h-4 w-2/5" />
            <Skeleton className="h-3 w-1/4" />
          </div>
          <Skeleton className="h-3 w-24" />
        </div>
      ))}
    </div>
  )
}

export function SkeletonCard({ count = 2 }: { count?: number }) {
  return (
    <div className="space-y-4" role="status" aria-busy="true" aria-label="Loading">
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className="glass rounded-xl p-4 space-y-3">
          <Skeleton className="h-4 w-1/3" />
          <Skeleton className="h-5 w-3/4" />
          <Skeleton className="h-3 w-1/2" />
        </div>
      ))}
    </div>
  )
}
