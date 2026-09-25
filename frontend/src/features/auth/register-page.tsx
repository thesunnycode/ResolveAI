import { Eye, EyeOff } from 'lucide-react'
import * as React from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError } from '@/lib/api-client'
import { Button } from '@/components/ui/button'
import { FieldError, Label } from '@/components/ui/field-error'
import { Input } from '@/components/ui/input'
import { DemoAccess } from '@/features/demo/demo-access'
import { useDocumentTitle } from '@/components/layout/route-a11y'
import { track } from '@/lib/analytics'
import { useSlowFlag } from '@/lib/use-slow-flag'
import { AuthShell, OrDivider, SlowServerHint } from './auth-shell'
import { useAuth } from './auth-context'

const PASSWORD_RULE = 'At least 10 characters, with a letter and a number.'

function passwordStrength(pw: string): { label: string; ok: boolean } | null {
  if (pw.length === 0) return null
  const hasLetter = /[A-Za-z]/.test(pw)
  const hasDigit = /\d/.test(pw)
  if (pw.length < 10) return { label: `${pw.length} of 10 characters`, ok: false }
  if (!hasLetter || !hasDigit) return { label: 'Needs a letter and a number', ok: false }
  return { label: 'Looks good', ok: true }
}

/**
 * The server's rules, checked before the round trip. The server validates the password
 * before it looks the workspace up, so without this a visitor fixed one error, submitted,
 * and only then learned about the next. Local checks surface every rule at once.
 */
function localErrors(fullName: string, password: string): Record<string, string> {
  const errors: Record<string, string> = {}
  if (fullName.trim().length < 2) errors.fullName = 'Enter your full name.'
  const strength = passwordStrength(password)
  if (!strength || !strength.ok) errors.password = PASSWORD_RULE
  return errors
}

export function RegisterPage() {
  useDocumentTitle('Create an account')
  const { register, user } = useAuth()
  const navigate = useNavigate()

  const [tenantSlug, setTenantSlug] = React.useState('')
  const [fullName, setFullName] = React.useState('')
  const [email, setEmail] = React.useState('')
  const [password, setPassword] = React.useState('')
  const [showPassword, setShowPassword] = React.useState(false)
  const [passwordTouched, setPasswordTouched] = React.useState(false)
  const [submitting, setSubmitting] = React.useState(false)
  const [formError, setFormError] = React.useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = React.useState<Record<string, string>>({})
  const slow = useSlowFlag(submitting)

  React.useEffect(() => {
    track('auth_page_viewed', { page: 'register', width: window.innerWidth })
  }, [])

  React.useEffect(() => {
    // Registration always creates a CUSTOMER (the backend's RegisterRequest
    // has no role field, deliberately), so this could hardcode /my-tickets -
    // but routing through "/" keeps this page from ever having to know that.
    if (user) navigate('/', { replace: true })
  }, [user, navigate])

  const strength = passwordTouched ? passwordStrength(password) : null

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setFormError(null)
    const local = localErrors(fullName, password)
    setFieldErrors(local)
    if (Object.keys(local).length > 0) {
      setPasswordTouched(true)
      return
    }
    setSubmitting(true)
    track('register_submitted')
    try {
      await register(tenantSlug.trim(), email.trim(), password, fullName.trim())
      track('login_succeeded', { method: 'register' })
    } catch (err) {
      track('register_failed', {
        status: err instanceof ApiError ? err.problem.status : 0,
        code: err instanceof ApiError ? err.problem.errorCode : 'UNKNOWN',
      })
      if (err instanceof ApiError && err.problem.errorCode === 'TENANT_NOT_FOUND') {
        // Inline, under the field it is about, rather than a banner above the form.
        setFieldErrors({ tenantSlug: err.problem.detail })
      } else if (err instanceof ApiError && err.problem.errors) {
        const next: Record<string, string> = {}
        for (const e2 of err.problem.errors) next[e2.field] = e2.message
        setFieldErrors(next)
      } else if (err instanceof ApiError) {
        setFormError(err.problem.detail || 'Could not create your account.')
      } else {
        setFormError('Something went wrong. Try again.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AuthShell>
      <div className="animate-slide-up">
        <div className="mb-7">
          <h1 className="text-2xl font-semibold leading-tight tracking-[-0.03em] text-text">Create your account</h1>
          <p className="mt-1 text-sm leading-relaxed text-text-muted">
            For customers of a company that uses ResolveAI. Support agents get an invite from their workspace admin
            instead.
          </p>
        </div>

        <DemoAccess compact />
        <OrDivider label="or create a customer account" />

        <form onSubmit={handleSubmit} className="space-y-0">
          {formError && (
            <div role="alert" className="mb-4 rounded-md border border-danger/20 bg-danger-bg px-3 py-2 text-sm text-danger">
              {formError}
            </div>
          )}

          <div className="mb-4">
            <Label htmlFor="tenantSlug" required>
              Workspace
            </Label>
            <Input
              id="tenantSlug"
              placeholder="acme"
              value={tenantSlug}
              onChange={(e) => setTenantSlug(e.target.value)}
              error={!!fieldErrors.tenantSlug}
              aria-describedby="tenantSlug-help"
              required
            />
            {fieldErrors.tenantSlug ? (
              <FieldError message={fieldErrors.tenantSlug} />
            ) : (
              <p id="tenantSlug-help" className="mt-1.5 text-xs text-text-subtle">
                The company name from the support link you were given — for example <span className="font-mono">acme</span>.
              </p>
            )}
          </div>

          <div className="mb-4">
            <Label htmlFor="fullName" required>
              Full name
            </Label>
            <Input
              id="fullName"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              error={!!fieldErrors.fullName}
              autoComplete="name"
              required
            />
            <FieldError message={fieldErrors.fullName} />
          </div>

          <div className="mb-4">
            <Label htmlFor="email" required>
              Email
            </Label>
            <Input
              id="email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              error={!!fieldErrors.email}
              autoComplete="email"
              required
            />
            <FieldError message={fieldErrors.email} />
          </div>

          <div className="mb-5">
            <Label htmlFor="password" required>
              Password
            </Label>
            <div className="relative">
              <Input
                id="password"
                type={showPassword ? 'text' : 'password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                onBlur={() => setPasswordTouched(true)}
                error={!!fieldErrors.password}
                autoComplete="new-password"
                required
                className="pr-9"
              />
              <button
                type="button"
                onClick={() => setShowPassword((s) => !s)}
                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-text-subtle hover:text-text"
                aria-label={showPassword ? 'Hide password' : 'Show password'}
              >
                {showPassword ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
              </button>
            </div>
            {/* On blur, not on keystroke — live validation while someone is
                still typing their password is hostile. Doc 06 §4.5. */}
            {fieldErrors.password ? (
              <FieldError message={fieldErrors.password} />
            ) : strength ? (
              <p className={`mt-1.5 text-sm ${strength.ok ? 'text-success' : 'text-text-muted'}`}>
                {strength.label}
              </p>
            ) : (
              // The rule up front, so nobody has to fail once to learn it.
              <p className="mt-1.5 text-xs text-text-subtle">{PASSWORD_RULE}</p>
            )}
          </div>

          <Button type="submit" size="lg" className="w-full" loading={submitting}>
            Create account
          </Button>
          {slow && <SlowServerHint />}
        </form>

        <p className="mt-5 text-sm text-text-muted">
          Already have an account?{' '}
          <Link to="/login" className="font-medium text-primary hover:underline">
            Sign in
          </Link>
        </p>
      </div>
    </AuthShell>
  )
}
