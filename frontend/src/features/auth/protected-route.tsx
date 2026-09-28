import { NotAllowed } from '@/components/app/states'
import { Navigate, useLocation } from 'react-router-dom'
import { Loader2 } from 'lucide-react'
import { useSlowFlag } from '@/lib/use-slow-flag'
import { SlowServerHint } from './auth-shell'
import type { Role } from '@/lib/types'
import { homeFor, useAuth } from './auth-context'

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
        <Loader2 className="size-5 animate-spin text-muted-foreground" aria-hidden />
        {slow && <SlowServerHint />}
      </div>
    )
  }

  if (!user) {
    const next = encodeURIComponent(location.pathname + location.search)
    return <Navigate to={`/login?next=${next}`} replace />
  }

  if (roles && !roles.includes(user.role)) {
    return <NotAllowed home={homeFor(user.role)} />
  }

  return <>{children}</>
}
