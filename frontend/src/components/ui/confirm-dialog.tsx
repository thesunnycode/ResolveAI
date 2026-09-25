import * as Dialog from '@radix-ui/react-dialog'
import { AlertTriangle, X } from 'lucide-react'
import * as React from 'react'
import { Button } from './button'
import { FieldError, Label } from './field-error'
import { Textarea } from './input'

export interface ConfirmDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  /** Plain-language consequences, per doc 06 §4.3 — "confirming is a high-blast-radius action." */
  consequences?: string[]
  requireReason?: boolean
  reasonLabel?: string
  reasonMinLength?: number
  confirmLabel?: string
  destructive?: boolean
  onConfirm: (reason?: string) => void | Promise<void>
}

export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  consequences,
  requireReason,
  reasonLabel = 'Reason',
  reasonMinLength = 10,
  confirmLabel = 'Confirm',
  destructive,
  onConfirm,
}: ConfirmDialogProps) {
  const reasonId = React.useId()
  const [reason, setReason] = React.useState('')
  const [submitting, setSubmitting] = React.useState(false)
  const [touched, setTouched] = React.useState(false)

  const reasonTooShort = requireReason && reason.trim().length < reasonMinLength

  React.useEffect(() => {
    if (open) {
      setReason('')
      setTouched(false)
    }
  }, [open])

  async function handleConfirm() {
    setTouched(true)
    if (reasonTooShort) return
    setSubmitting(true)
    try {
      await onConfirm(requireReason ? reason.trim() : undefined)
      onOpenChange(false)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm data-[state=open]:animate-fade-in" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2 rounded-xl border border-border bg-surface p-6 shadow-popover data-[state=open]:animate-slide-up">
          <div className="flex items-start justify-between gap-3">
            <div className="flex items-center gap-2.5">
              {destructive && (
                <span className="flex size-8 shrink-0 items-center justify-center rounded-full bg-danger-bg">
                  <AlertTriangle className="size-4 text-danger" aria-hidden />
                </span>
              )}
              <Dialog.Title className="text-lg font-semibold text-text">{title}</Dialog.Title>
            </div>
            <Dialog.Close
              className="-m-1.5 flex size-8 items-center justify-center rounded-md text-text-subtle hover:bg-surface-2 hover:text-text"
              aria-label="Close"
            >
              <X className="size-4" aria-hidden />
            </Dialog.Close>
          </div>

          {consequences && consequences.length > 0 ? (
            <Dialog.Description asChild>
              <ul className="mt-3 space-y-1 rounded-md bg-surface-2 p-3 text-sm text-text-muted">
                {consequences.map((c, i) => (
                  <li key={i} className="flex gap-1.5">
                    <span aria-hidden className="text-text-subtle">&bull;</span>
                    {c}
                  </li>
                ))}
              </ul>
            </Dialog.Description>
          ) : (
            <Dialog.Description className="sr-only">
              {requireReason ? `Enter a ${reasonLabel.toLowerCase()} to continue.` : 'Confirm or cancel.'}
            </Dialog.Description>
          )}

          {requireReason && (
            <div className="mt-4">
              <Label htmlFor={reasonId} required>
                {reasonLabel}
              </Label>
              <Textarea
                id={reasonId}
                rows={3}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                onBlur={() => setTouched(true)}
                error={touched && reasonTooShort}
                placeholder={`At least ${reasonMinLength} characters`}
              />
              {touched && reasonTooShort && (
                <FieldError message={`Reason must be at least ${reasonMinLength} characters.`} />
              )}
            </div>
          )}

          <div className="mt-5 flex justify-end gap-2">
            <Dialog.Close asChild>
              <Button variant="secondary">Cancel</Button>
            </Dialog.Close>
            <Button
              variant={destructive ? 'danger' : 'primary'}
              loading={submitting}
              onClick={handleConfirm}
            >
              {confirmLabel}
            </Button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
