import * as React from 'react'
import { api } from '@/lib/api-client'
import { authStorage } from '@/lib/auth-storage'
import type { AuthUser, CurrentUserResponse, Role } from '../../lib/types'

interface AuthContextValue {
  user: AuthUser | null
  loading: boolean
  login: (tenantSlug: string, email: string, password: string) => Promise<void>
  register: (tenantSlug: string, email: string, password: string, fullName: string) => Promise<void>
  /** Creates a new tenant and its founding Admin, then signs them straight in. */
  registerBusiness: (businessName: string, adminFullName: string, adminEmail: string, password: string) => Promise<void>
  /** Redeems a team-invite token: creates the Agent/Team Lead account and signs them in. */
  acceptInvite: (token: string, fullName: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = React.createContext<AuthContextValue | null>(null)

/** Where a signed-in user lands: the only two "homes" the app has. */
export function homeFor(role: Role): string {
  return role === 'CUSTOMER' ? '/tickets' : '/queue'
}

function toAuthUser(me: CurrentUserResponse): AuthUser {
  return {
    id: me.id,
    email: me.email,
    fullName: me.fullName,
    role: me.role,
    tenantId: me.tenantId,
    tenantSlug: me.tenantSlug,
    teamId: me.teamId,
    teamName: me.teamName,
    permissions: me.permissions,
    agentProfile: me.agentProfile,
  }
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = React.useState<AuthUser | null>(authStorage.getUser())
  const [loading, setLoading] = React.useState(true)

  React.useEffect(() => {
    // Revalidate against /auth/me on mount so role/team changes made server-side
    // (or a token that quietly expired) are caught before the app trusts them.
    if (!authStorage.getAccessToken()) {
      setLoading(false)
      return
    }
    api
      .get<CurrentUserResponse>('/auth/me')
      .then((res) => {
        const authUser = toAuthUser(res.data)
        setUser(authUser)
        authStorage.set(authStorage.getAccessToken()!, authStorage.getRefreshToken()!, authUser)
      })
      .catch(() => {
        authStorage.clear()
        setUser(null)
      })
      .finally(() => setLoading(false))
  }, [])

  async function completeLogin(accessToken: string, refreshToken: string) {
    authStorage.set(accessToken, refreshToken, {} as AuthUser)
    const me = await api.get<CurrentUserResponse>('/auth/me')
    const authUser = toAuthUser(me.data)
    authStorage.set(accessToken, refreshToken, authUser)
    setUser(authUser)
  }

  async function login(tenantSlug: string, email: string, password: string) {
    // Remembered for the sign-in page: "Welcome back" and a prefilled workspace (audit U9).
    try {
      localStorage.setItem(LAST_WORKSPACE_KEY, tenantSlug)
    } catch {
      // Private mode: the page just greets everyone the same way.
    }
    const res = await api.post('/auth/login', { tenantSlug, email, password })
    await completeLogin(res.data.accessToken, res.data.refreshToken)
  }

  async function register(tenantSlug: string, email: string, password: string, fullName: string) {
    await api.post('/auth/register', { tenantSlug, email, password, fullName })
    await login(tenantSlug, email, password)
  }

  async function registerBusiness(
    businessName: string,
    adminFullName: string,
    adminEmail: string,
    password: string,
  ) {
    const res = await api.post('/tenants', { businessName, adminFullName, adminEmail, password })
    await completeLogin(res.data.accessToken, res.data.refreshToken)
  }

  async function acceptInvite(token: string, fullName: string, password: string) {
    const res = await api.post(`/invites/${token}/accept`, { fullName, password })
    await completeLogin(res.data.accessToken, res.data.refreshToken)
  }

  async function logout() {
    const refreshToken = authStorage.getRefreshToken()
    try {
      if (refreshToken) await api.post('/auth/logout', { refreshToken })
    } catch {
      // Best-effort: the client forgets its tokens either way.
    }
    authStorage.clear()
    setUser(null)
  }

  return (
    <AuthContext.Provider
      value={{ user, loading, login, register, registerBusiness, acceptInvite, logout }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export const LAST_WORKSPACE_KEY = 'resolveai.lastWorkspace'

export function useAuth() {
  const ctx = React.useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
