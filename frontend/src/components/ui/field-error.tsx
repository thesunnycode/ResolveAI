import { AlertCircle } from 'lucide-react'

export function FieldError({ message }: { message?: string | null }) {
  if (!message) return null
  return (
    <p role="alert" className="mt-1.5 flex items-center gap-1 text-sm text-danger">
      <AlertCircle className="size-3.5 shrink-0" aria-hidden />
      {message}
    </p>
  )
}

export function Label({
  children,
  htmlFor,
  required,
}: {
  children: React.ReactNode
  htmlFor?: string
  required?: boolean
}) {
  return (
    <label htmlFor={htmlFor} className="mb-1.5 block text-sm font-medium text-text-muted">
      {children}
      {required && <span className="text-danger ml-0.5">*</span>}
    </label>
  )
}
