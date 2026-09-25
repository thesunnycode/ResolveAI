import { Eye, EyeOff } from 'lucide-react'
import * as React from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { ApiError } from '@/lib/api-client'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/field-error'
import { Input } from '@/components/ui/input'
import { DemoAccess } from '@/features/demo/demo-access'
import { track } from '@/lib/analytics'
import { useSlowFlag } from '@/lib/use-slow-flag'
import { AuthShell, OrDivider, SlowServerHint } from './auth-shell'
import { LAST_WORKSPACE_KEY, useAuth } from './auth-context'
import { useDocumentTitle } from '@/components/layout/route-a11y'

export function LoginPage() {
  const { login, user } = useAuth()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const next = params.get('next')

  useDocumentTitle('Sign in')
  // Only someone who has signed in on this browser before is "back" (audit U9).
  const [lastWorkspace] = React.useState(() => {
    try {
      return localStorage.getItem(LAST_WORKSPACE_KEY)
    } catch {
      return null
    }
  })
  const [tenantSlug, setTenantSlug] = React.useState(lastWorkspace ?? '')
  const [email, setEmail] = React.useState('')
  const [password, setPassword] = React.useState('')
  const [showPassword, setShowPassword] = React.useState(false)
  const [submitting, setSubmitting] = React.useState(false)
  const [formError, setFormError] = React.useState<string | null>(null)
  const [showReset, setShowReset] = React.useState(false)
  const slow = useSlowFlag(submitting)

  React.useEffect(() => {
    track('auth_page_viewed', { page: 'login', width: window.innerWidth })
  }, [])

  React.useEffect(() => {
    // No hardcoded default here: "/" resolves to the right role home via
    // RoleHome in App.tsx (a customer landing on /queue would just bounce
    // straight into "not available for your role").
    if (user) navigate(next ? decodeURIComponent(next) : '/', { replace: true })
  }, [user, next, navigate])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setFormError(null)
    setSubmitting(true)
    try {
      await login(tenantSlug.trim(), email.trim(), password)
      track('login_succeeded', { method: 'password' })
    } catch (err) {
      track('auth_failed', {
        endpoint: 'login',
        status: err instanceof ApiError ? err.problem.status : 0,
        code: err instanceof ApiError ? err.problem.errorCode : 'UNKNOWN',
      })
      setFormError(
        err instanceof ApiError
          ? err.problem.detail || 'Invalid credentials.'
          : 'Something went wrong. Try again.',
      )
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AuthShell>
      <div className="animate-slide-up">
        <div className="mb-5">
          <h1 tabIndex={-1} className="text-2xl font-semibold leading-tight tracking-[-0.03em] text-text">
            {lastWorkspace ? 'Welcome back' : 'Sign in to ResolveAI'}
          </h1>
          <p className="mt-1 text-sm text-text-muted">
            {lastWorkspace ? (
              <>
                Signing in to <span className="font-medium text-text">{lastWorkspace}</span>
              </>
            ) : (
              'Sign in to your workspace, or try the demo first.'
            )}
          </p>
        </div>

        <DemoAccess compact />
        <OrDivider label="or sign in to your workspace" />

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
              autoComplete="organization"
              aria-describedby="tenantSlug-help"
              required
            />
            <p id="tenantSlug-help" className="mt-1.5 text-xs text-text-subtle">
              Your company's ResolveAI name, from your invite — for example <span className="font-mono">acme</span>.
            </p>
          </div>

          <div className="mb-4">
            <Label htmlFor="email" required>
              Email
            </Label>
            <Input
              id="email"
              type="email"
              placeholder="you@company.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="username"
              required
            />
          </div>

          <div className="mb-5">
            <div className="flex items-baseline justify-between">
              <Label htmlFor="password" required>
                Password
              </Label>
              <button
                type="button"
                onClick={() => setShowReset((v) => !v)}
                className="inline-flex min-h-6 items-center text-xs font-medium text-primary hover:underline"
                aria-expanded={showReset}
                aria-controls="reset-help"
              >
                Forgot password?
              </button>
            </div>
            <div className="relative">
              <Input
                id="password"
                type={showPassword ? 'text' : 'password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
                required
                className="pr-9"
              />
              <button
                type="button"
                onClick={() => setShowPassword((s) => !s)}
                className="absolute right-1 top-1/2 flex size-8 -translate-y-1/2 items-center justify-center rounded-md text-text-subtle hover:text-text"
                aria-label={showPassword ? 'Hide password' : 'Show password'}
              >
                {showPassword ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
              </button>
            </div>
            {showReset && (
              <p id="reset-help" className="mt-2 rounded-md bg-surface-2 px-3 py-2 text-sm leading-relaxed text-text-muted">
                Passwords are reset by your workspace admin — ask them and they can set a new one for you.
                Self-service reset by email isn't available yet.
              </p>
            )}
          </div>

          <Button type="submit" size="lg" className="w-full" loading={submitting}>
            Sign in
          </Button>
          {slow && <SlowServerHint />}
        </form>

        <p className="mt-5 text-sm text-text-muted">
          New here?{' '}
          <Link to="/register" className="font-medium text-primary hover:underline">
            Create an account
          </Link>
        </p>
      </div>
    </AuthShell>
  )
}
