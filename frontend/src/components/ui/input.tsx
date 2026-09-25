import * as React from 'react'
import { cn } from '@/lib/utils'

export interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  error?: boolean
}

export const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, error, ...props }, ref) => (
    <input
      ref={ref}
      className={cn(
        'flex h-10 w-full rounded-md border bg-surface px-3 py-2 text-base text-text transition-colors placeholder:text-text-subtle',
        'border-border-control hover:border-text-subtle',
        'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-primary',
        'disabled:cursor-not-allowed disabled:bg-surface-2 disabled:text-text-subtle',
        error && 'border-danger focus-visible:border-danger focus-visible:ring-danger-bg',
        className,
      )}
      aria-invalid={error || undefined}
      {...props}
    />
  ),
)
Input.displayName = 'Input'

export const Textarea = React.forwardRef<
  HTMLTextAreaElement,
  React.TextareaHTMLAttributes<HTMLTextAreaElement> & { error?: boolean }
>(({ className, error, ...props }, ref) => (
  <textarea
    ref={ref}
    className={cn(
      'flex w-full rounded-md border bg-surface px-3 py-2.5 text-base text-text transition-colors placeholder:text-text-subtle resize-y',
      'border-border-control hover:border-text-subtle',
      'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-primary',
      'disabled:cursor-not-allowed disabled:bg-surface-2 disabled:text-text-subtle',
      error && 'border-danger focus-visible:border-danger focus-visible:ring-danger-bg',
      className,
    )}
    aria-invalid={error || undefined}
    {...props}
  />
))
Textarea.displayName = 'Textarea'
