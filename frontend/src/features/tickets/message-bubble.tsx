import { Lock } from 'lucide-react'
import type { MessageView } from '@/lib/types'
import { RelativeTime } from '@/components/ui/relative-time'
import { cn } from '@/lib/utils'

function initials(name: string) {
  return name
    .split(' ')
    .map((p) => p[0])
    .slice(0, 2)
    .join('')
    .toUpperCase()
}

/**
 * Who said it has to be readable at a glance in a long thread: the customer
 * gets a neutral avatar, the support team a brand-tinted one, and an internal
 * note is dashed and amber so it can never be mistaken for something the
 * customer saw.
 */
export function MessageBubble({ message, pending }: { message: MessageView; /** Optimistic: sent, not yet confirmed (audit F2). */ pending?: boolean }) {
  const isInternal = message.visibility === 'INTERNAL'
  const isCustomer = message.authorRole === 'CUSTOMER'

  return (
    <div className={cn('flex gap-3', pending && 'opacity-70')} aria-busy={pending || undefined}>
      <span
        className={cn(
          'mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-full text-xs font-semibold',
          isCustomer ? 'bg-surface-2 text-text-muted' : 'bg-primary-bg text-primary ring-1 ring-primary/25',
        )}
        aria-hidden
      >
        {initials(message.authorName)}
      </span>
      <div
        className={cn(
          'min-w-0 flex-1 rounded-xl px-4 py-3',
          isInternal ? 'border border-dashed border-warning/40 bg-warning-bg' : 'glass',
        )}
      >
        <div className="mb-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
          <span className="font-medium text-text">{message.authorName}</span>
          <span className="capitalize text-text-subtle">{isCustomer ? 'customer' : message.authorRole.replace('_', ' ').toLowerCase()}</span>
          {isInternal && (
            <span className="inline-flex items-center gap-1 rounded-md bg-warning/15 px-1.5 py-0.5 text-xs font-medium text-warning">
              <Lock className="size-2.5" aria-hidden /> Internal note
            </span>
          )}
          {message.isFirstResponse && (
            <span className="rounded-md bg-primary-bg px-1.5 py-0.5 text-xs font-medium text-primary">First response</span>
          )}
          {pending ? (
            <span className="ml-auto whitespace-nowrap text-text-subtle">Sending…</span>
          ) : (
            <RelativeTime iso={message.createdAt} className="ml-auto whitespace-nowrap text-text-subtle" />
          )}
        </div>
        <p className="whitespace-pre-wrap text-base leading-relaxed text-text">{message.body}</p>
      </div>
    </div>
  )
}
