import * as Select from '@radix-ui/react-select'
import { Check, ChevronDown } from 'lucide-react'
import * as React from 'react'
import { ConfirmDialog } from '@/components/app/confirm-dialog'
import type { TicketStatus } from '@/lib/types'
import { REASON_REQUIRED, STATUS_LABEL as LABELS } from './status-labels'

export function StatusDropdown({
  current,
  allowed,
  onChange,
  pendingTo,
}: {
  current: TicketStatus
  allowed: TicketStatus[]
  onChange: (status: TicketStatus, reason?: string) => void | Promise<void>
  /** A move being held behind an Undo toast - shown, but not yet committed. */
  pendingTo?: TicketStatus | null
}) {
  const [pending, setPending] = React.useState<TicketStatus | null>(null)

  async function commit(status: TicketStatus, reason?: string) {
    await onChange(status, reason)
    setPending(null)
  }

  return (
    <>
      <Select.Root
        value={pendingTo ?? current}
        disabled={!!pendingTo}
        onValueChange={(v) => {
          const status = v as TicketStatus
          if (REASON_REQUIRED.includes(status)) {
            setPending(status)
          } else {
            void commit(status)
          }
        }}
      >
        <Select.Trigger
          aria-label="Status"
          className="glass inline-flex min-h-8 items-center gap-1.5 rounded-md px-2.5 py-1 text-sm font-medium text-foreground hover:border-border focus-visible:outline-2 focus-visible:outline-primary disabled:opacity-70"
        >
          <Select.Value>{pendingTo ? `${LABELS[pendingTo]}…` : LABELS[current]}</Select.Value>
          <Select.Icon>
            <ChevronDown className="size-3.5 text-muted-foreground" />
          </Select.Icon>
        </Select.Trigger>
        <Select.Portal>
          <Select.Content className="z-50 overflow-hidden rounded-md border border-border bg-card shadow-popover">
            <Select.Viewport className="p-1">
              <Select.Item
                value={pendingTo ?? current}
                disabled
                className="flex items-center gap-2 rounded px-2.5 py-1.5 text-sm text-muted-foreground"
              >
                <Select.ItemText>{LABELS[current]}</Select.ItemText>
                <span className="ml-auto text-xs text-muted-foreground">current</span>
              </Select.Item>
              {allowed.map((s) => (
                <Select.Item
                  key={s}
                  value={s}
                  className="flex cursor-pointer items-center gap-2 rounded px-2.5 py-1.5 text-sm text-foreground outline-none data-[highlighted]:bg-muted"
                >
                  <Select.ItemIndicator>
                    <Check className="size-3" />
                  </Select.ItemIndicator>
                  <Select.ItemText>{LABELS[s]}</Select.ItemText>
                </Select.Item>
              ))}
            </Select.Viewport>
          </Select.Content>
        </Select.Portal>
      </Select.Root>

      <ConfirmDialog
        open={pending !== null}
        onOpenChange={(open) => !open && setPending(null)}
        title={pending ? `Move to ${LABELS[pending]}` : ''}
        requireReason
        reasonLabel="Reason"
        confirmLabel="Confirm"
        onConfirm={(reason) => {
          if (pending) return commit(pending, reason)
        }}
      />
    </>
  )
}
