import { Eye, EyeOff } from 'lucide-react'
import * as React from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError } from '@/lib/api-client'
import { Button } from '@/components/ui/button'
import { FieldError, Label } from '@/components/ui/field-error'
import { Input } from '@/components/ui/input'
import { useDocumentTitle } from '@/components/layout/route-a11y'
import { track } from '@/lib/analytics'
import { useSlowFlag } from '@/lib/use-slow-flag'
import { AuthShell, SlowServerHint } from './auth-shell'
import { homeFor, useAuth } from './auth-context'
import { PASSWORD_RULE, localErrors, passwordStrength } from './password-rules'

function businessErrors(businessName: string): Record<string, string> {
  return businessName.trim().length < 2 ? { businessName: 'Enter your business name.' } : {}
}

export function RegisterBusinessPage() {
  useDocumentTitle('Create a business account')
  const { registerBusiness, user } = useAuth()
  const navigate = useNavigate()

  const [businessName, setBusinessName] = React.useState('')
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
    track('auth_page_viewed', { page: 'register-business', width: window.innerWidth })
  }, [])

  React.useEffect(() => {
    // The founder is always an Admin - homeFor() still used rather than hardcoding
    // /queue, for the same reason register-page.tsx gives: "/" is the public
    // landing page now, this page can't route through it blind.
    if (user) navigate(homeFor(user.role), { replace: true })
  }, [user, navigate])

  const strength = passwordTouched ? passwordStrength(password) : null

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setFormError(null)
    const local = { ...businessErrors(businessName), ...localErrors(fullName, password) }
    setFieldErrors(local)
    if (Object.keys(local).length > 0) {
      setPasswordTouched(true)
      return
    }
    setSubmitting(true)
    track('register_submitted', { method: 'business' })
    try {
      await registerBusiness(businessName.trim(), fullName.trim(), email.trim(), password)
      track('login_succeeded', { method: 'register-business' })
    } catch (err) {
      track('register_failed', {
        method: 'business',
        status: err instanceof ApiError ? err.problem.status : 0,
        code: err instanceof ApiError ? err.problem.errorCode : 'UNKNOWN',
      })
      if (err instanceof ApiError && err.problem.errors) {
        const next: Record<string, string> = {}
        for (const e2 of err.problem.errors) next[e2.field] = e2.message
        setFieldErrors(next)
      } else if (err instanceof ApiError) {
        setFormError(err.problem.detail || 'Could not create your business.')
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
          <h1 className="font-heading text-2xl font-semibold leading-tight tracking-[-0.03em] text-foreground">
            Create a business account
          </h1>
          <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
            Sets up a new workspace for your company, with you as its Admin. From there you can
            create teams and invite Agents and Team Leads.
          </p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-0">
          {formError && (
            <div role="alert" className="mb-4 rounded-md border border-danger-border bg-danger-soft px-3 py-2 text-sm text-danger">
              {formError}
            </div>
          )}

          <div className="mb-4">
            <Label htmlFor="businessName" required>
              Business name
            </Label>
            <Input
              id="businessName"
              placeholder="Acme Retail"
              value={businessName}
              onChange={(e) => setBusinessName(e.target.value)}
              error={!!fieldErrors.businessName}
              autoComplete="organization"
              required
            />
            <FieldError message={fieldErrors.businessName} />
          </div>

          <div className="mb-4">
            <Label htmlFor="fullName" required>
              Your full name
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
                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                aria-label={showPassword ? 'Hide password' : 'Show password'}
              >
                {showPassword ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
              </button>
            </div>
            {fieldErrors.password ? (
              <FieldError message={fieldErrors.password} />
            ) : strength ? (
              <p className={`mt-1.5 text-sm ${strength.ok ? 'text-success' : 'text-muted-foreground'}`}>
                {strength.label}
              </p>
            ) : (
              <p className="mt-1.5 text-xs text-muted-foreground">{PASSWORD_RULE}</p>
            )}
          </div>

          <Button type="submit" size="lg" className="w-full" loading={submitting}>
            Create business account
          </Button>
          {slow && <SlowServerHint />}
        </form>

        <p className="mt-5 text-sm text-muted-foreground">
          Joining an existing business instead?{' '}
          <Link to="/register" className="font-medium text-primary hover:underline">
            Create a customer account
          </Link>
        </p>
      </div>
    </AuthShell>
  )
}
