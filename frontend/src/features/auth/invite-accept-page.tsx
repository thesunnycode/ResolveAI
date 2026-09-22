import { Eye, EyeOff, MailWarning } from 'lucide-react'
import * as React from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { EmptyState } from '@/components/app/states'
import { ApiError } from '@/lib/api-client'
import { Button } from '@/components/ui/button'
import { FieldError, Label } from '@/components/ui/field-error'
import { Input } from '@/components/ui/input'
import { useDocumentTitle } from '@/components/layout/route-a11y'
import { useSlowFlag } from '@/lib/use-slow-flag'
import { AuthShell, SlowServerHint } from './auth-shell'
import { homeFor, useAuth } from './auth-context'
import { useInvitePreview } from './api'
import { PASSWORD_RULE, localErrors, passwordStrength } from './password-rules'

const ROLE_LABEL: Record<string, string> = { AGENT: 'Agent', TEAM_LEAD: 'Team Lead' }

export function InviteAcceptPage() {
  useDocumentTitle('Accept your invite')
  const [params] = useSearchParams()
  const token = params.get('token')
  const { acceptInvite, user } = useAuth()
  const navigate = useNavigate()
  const preview = useInvitePreview(token)

  const [fullName, setFullName] = React.useState('')
  const [password, setPassword] = React.useState('')
  const [showPassword, setShowPassword] = React.useState(false)
  const [passwordTouched, setPasswordTouched] = React.useState(false)
  const [submitting, setSubmitting] = React.useState(false)
  const [formError, setFormError] = React.useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = React.useState<Record<string, string>>({})
  const slow = useSlowFlag(submitting)

  React.useEffect(() => {
    if (user) navigate(homeFor(user.role), { replace: true })
  }, [user, navigate])

  const strength = passwordTouched ? passwordStrength(password) : null

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!token) return
    setFormError(null)
    const local = localErrors(fullName, password)
    setFieldErrors(local)
    if (Object.keys(local).length > 0) {
      setPasswordTouched(true)
      return
    }
    setSubmitting(true)
    try {
      await acceptInvite(token, fullName.trim(), password)
    } catch (err) {
      if (err instanceof ApiError && err.problem.errors) {
        const next: Record<string, string> = {}
        for (const e2 of err.problem.errors) next[e2.field] = e2.message
        setFieldErrors(next)
      } else if (err instanceof ApiError) {
        setFormError(err.problem.detail || 'Could not accept this invite.')
      } else {
        setFormError('Something went wrong. Try again.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  if (!token || preview.isError) {
    const detail =
      preview.error instanceof ApiError ? preview.error.problem.detail : 'This invite link is not valid.'
    return (
      <AuthShell>
        <EmptyState
          icon={MailWarning}
          title="Invite not valid"
          body={detail}
          action={
            <Link to="/login">
              <Button variant="secondary" size="sm">
                Go to sign in
              </Button>
            </Link>
          }
        />
      </AuthShell>
    )
  }

  if (preview.isLoading) {
    return <AuthShell>{null}</AuthShell>
  }

  return (
    <AuthShell>
      <div className="animate-slide-up">
        <div className="mb-7">
          <h1 className="font-heading text-2xl font-semibold leading-tight tracking-[-0.03em] text-foreground">
            Join {preview.data?.tenantName}
          </h1>
          <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
            You've been invited as {ROLE_LABEL[preview.data?.role ?? ''] ?? preview.data?.role} for{' '}
            <span className="font-medium text-foreground">{preview.data?.email}</span>. Set a password
            to finish creating your account.
          </p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-0">
          {formError && (
            <div role="alert" className="mb-4 rounded-md border border-danger-border bg-danger-soft px-3 py-2 text-sm text-danger">
              {formError}
            </div>
          )}

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
            Create account
          </Button>
          {slow && <SlowServerHint />}
        </form>
      </div>
    </AuthShell>
  )
}
