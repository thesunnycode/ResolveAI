import * as React from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '@/lib/api-client'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/field-error'
import { Input } from '@/components/ui/input'
import { useDocumentTitle } from '@/components/layout/route-a11y'
import { useBusinesses } from './api'
import { AuthShell, SlowServerHint } from './auth-shell'
import { useAuth } from './auth-context'
import { useSlowFlag } from '@/lib/use-slow-flag'

export function ForgotPasswordPage() {
  useDocumentTitle('Reset your password')
  const { requestPasswordReset } = useAuth()
  const businesses = useBusinesses()

  const [tenantSlug, setTenantSlug] = React.useState('')
  const [email, setEmail] = React.useState('')
  const [submitting, setSubmitting] = React.useState(false)
  const [sent, setSent] = React.useState(false)
  const [formError, setFormError] = React.useState<string | null>(null)
  const slow = useSlowFlag(submitting)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setFormError(null)
    setSubmitting(true)
    try {
      await requestPasswordReset(tenantSlug, email.trim())
      // Always the same outcome, regardless of whether the account exists - the backend
      // never reveals that, so the UI can't either.
      setSent(true)
    } catch (err) {
      setFormError(err instanceof ApiError ? err.problem.detail : 'Something went wrong. Try again.')
    } finally {
      setSubmitting(false)
    }
  }

  if (sent) {
    return (
      <AuthShell>
        <div className="animate-slide-up">
          <h1 className="font-heading text-2xl font-semibold leading-tight tracking-[-0.03em] text-foreground">
            Check your email
          </h1>
          <p className="mt-3 text-sm leading-relaxed text-muted-foreground">
            If an account exists for <span className="font-medium text-foreground">{email}</span> in that
            workspace, we've sent a link to reset the password. It expires in 1 hour.
          </p>
          <Link to="/login" className="mt-5 inline-block text-sm font-medium text-primary hover:underline">
            Back to sign in
          </Link>
        </div>
      </AuthShell>
    )
  }

  return (
    <AuthShell>
      <div className="animate-slide-up">
        <div className="mb-7">
          <h1 className="font-heading text-2xl font-semibold leading-tight tracking-[-0.03em] text-foreground">
            Reset your password
          </h1>
          <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
            Tell us your workspace and email, and we'll send a link to set a new password.
          </p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-0">
          {formError && (
            <div role="alert" className="mb-4 rounded-md border border-danger-border bg-danger-soft px-3 py-2 text-sm text-danger">
              {formError}
            </div>
          )}

          <div className="mb-4">
            <Label htmlFor="tenantSlug" required>
              Workspace
            </Label>
            <select
              id="tenantSlug"
              value={tenantSlug}
              onChange={(e) => setTenantSlug(e.target.value)}
              required
              className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <option value="" disabled>
                Choose your business
              </option>
              {businesses.data?.map((tenant) => (
                <option key={tenant.slug} value={tenant.slug}>
                  {tenant.name}
                </option>
              ))}
            </select>
          </div>

          <div className="mb-5">
            <Label htmlFor="email" required>
              Email
            </Label>
            <Input
              id="email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="username"
              required
            />
          </div>

          <Button type="submit" size="lg" className="w-full" loading={submitting}>
            Send reset link
          </Button>
          {slow && <SlowServerHint />}
        </form>

        <p className="mt-5 text-sm text-muted-foreground">
          <Link to="/login" className="font-medium text-primary hover:underline">
            Back to sign in
          </Link>
        </p>
      </div>
    </AuthShell>
  )
}
