import { Lock } from 'lucide-react'
import type { MessageView } from '@/lib/types'
import { cn, formatDateTime } from '@/lib/utils'

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
export function MessageBubble({ message }: { message: MessageView }) {
  const isInternal = message.visibility === 'INTERNAL'
  const isCustomer = message.authorRole === 'CUSTOMER'

  return (
    <div className="flex gap-3">
      <span
        className={cn(
          'mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-full text-[11px] font-semibold',
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
        <div className="mb-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-[12.5px]">
          <span className="font-medium text-text">{message.authorName}</span>
          <span className="capitalize text-text-subtle">{isCustomer ? 'customer' : message.authorRole.replace('_', ' ').toLowerCase()}</span>
          {isInternal && (
            <span className="inline-flex items-center gap-1 rounded-md bg-warning/15 px-1.5 py-0.5 text-[11px] font-medium text-warning">
              <Lock className="size-2.5" aria-hidden /> Internal note
            </span>
          )}
          {message.isFirstResponse && (
            <span className="rounded-md bg-primary-bg px-1.5 py-0.5 text-[11px] font-medium text-primary">First response</span>
          )}
          <span className="ml-auto whitespace-nowrap text-text-subtle">{formatDateTime(message.createdAt)}</span>
        </div>
        <p className="whitespace-pre-wrap text-[14px] leading-relaxed text-text">{message.body}</p>
      </div>
    </div>
  )
}
