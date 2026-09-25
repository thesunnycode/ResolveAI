import { Lock } from 'lucide-react'
import { EmptyState } from '@/components/ui/empty-state'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { Loader2 } from 'lucide-react'
import { useSlowFlag } from '@/lib/use-slow-flag'
import { SlowServerHint } from './auth-shell'
import type { Role } from '@/lib/types'
import { useAuth } from './auth-context'

/**
 * Doc 06 §7 "Protected route behaviour":
 * - unauthenticated -> /login?next=<path>, and after login land on that path,
 *   not the role default
 * - wrong role -> a "not available for your role" page, never a silent
 *   redirect, because bouncing someone without explanation reads as a bug
 */
export function ProtectedRoute({
  children,
  roles,
}: {
  children: React.ReactNode
  roles?: Role[]
}) {
  const { user, loading } = useAuth()
  const location = useLocation()
  const slow = useSlowFlag(loading)

  if (loading) {
    return (
      <div className="flex h-svh flex-col items-center justify-center">
        <Loader2 className="size-5 animate-spin text-text-subtle" aria-hidden />
        {slow && <SlowServerHint />}
      </div>
    )
  }

  if (!user) {
    const next = encodeURIComponent(location.pathname + location.search)
    return <Navigate to={`/login?next=${next}`} replace />
  }

  if (roles && !roles.includes(user.role)) {
    return <NotAvailableForRole />
  }

  return <>{children}</>
}

function NotAvailableForRole() {
  // A client-side navigation, not window.location: a full reload here re-downloaded the
  // app and re-validated the session just to change routes.
  const navigate = useNavigate()
  return (
    <div className="flex min-h-[70vh] items-center justify-center px-6">
      <div className="glass w-full max-w-md rounded-xl">
        <EmptyState
          icon={Lock}
          title="Not available for your role"
          description="This page needs a higher permission level than your account has. If you think that's wrong, ask a workspace admin."
          action={{ label: 'Go to my home page', onClick: () => navigate('/') }}
        />
      </div>
    </div>
  )
}
