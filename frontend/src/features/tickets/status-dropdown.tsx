import * as Select from '@radix-ui/react-select'
import { Check, ChevronDown } from 'lucide-react'
import type { TicketStatus } from '@/lib/types'
import { STATUS_LABEL as LABELS } from './status-labels'

/**
 * Reports every selection to the caller as-is - it used to also own a "this status needs a
 * reason" confirm dialog internally, but that meant the `e` (resolve) hotkey had no way to
 * reach the same prompt: it could only call the plain status change directly, silently
 * skipping the reason/resolution text entirely. The dialog now lives one level up, in
 * ticket-detail-page.tsx, shared by both this dropdown and the hotkey.
 */
export function StatusDropdown({
  current,
  allowed,
  onSelect,
  pendingTo,
}: {
  current: TicketStatus
  allowed: TicketStatus[]
  onSelect: (status: TicketStatus) => void
  /** A move being held behind an Undo toast - shown, but not yet committed. */
  pendingTo?: TicketStatus | null
}) {
  return (
    <Select.Root
      value={pendingTo ?? current}
      disabled={!!pendingTo}
      onValueChange={(v) => onSelect(v as TicketStatus)}
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
  )
}
