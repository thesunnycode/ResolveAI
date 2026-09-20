import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { authStorage } from './auth-storage'
import type { ProblemDetail } from './types'

export class ApiError extends Error {
  problem: ProblemDetail
  constructor(problem: ProblemDetail) {
    super(problem.detail || problem.title)
    this.problem = problem
  }
}

export const api = axios.create({
  baseURL: '/api/v1',
  headers: { 'Content-Type': 'application/json' },
})

api.interceptors.request.use((config) => {
  const token = authStorage.getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// Doc 06 §7: a 401 is intercepted, the refresh token is exchanged once, the
// original request is replayed transparently. Only a failed refresh reaches
// the login screen. Concurrent 401s during the same refresh share one
// in-flight promise so a burst of requests doesn't burn the single-use
// refresh token more than once.
let refreshPromise: Promise<string> | null = null

async function refreshAccessToken(): Promise<string> {
  const refreshToken = authStorage.getRefreshToken()
  if (!refreshToken) {
    throw new Error('No refresh token')
  }
  const response = await axios.post('/api/v1/auth/refresh', { refreshToken })
  const { accessToken } = response.data as { accessToken: string; refreshToken: string }
  authStorage.setAccessToken(accessToken)
  if (response.data.refreshToken) {
    authStorage.set(accessToken, response.data.refreshToken, authStorage.getUser()!)
  }
  return accessToken
}

api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config as (InternalAxiosRequestConfig & { _retried?: boolean }) | undefined

    if (error.response?.status === 401 && original && !original._retried && !original.url?.includes('/auth/')) {
      original._retried = true
      try {
        refreshPromise ??= refreshAccessToken().finally(() => {
          refreshPromise = null
        })
        const newToken = await refreshPromise
        original.headers.Authorization = `Bearer ${newToken}`
        return api(original)
      } catch {
        authStorage.clear()
        const next = encodeURIComponent(window.location.pathname + window.location.search)
        window.location.href = `/login?next=${next}`
        return Promise.reject(error)
      }
    }

    if (error.response?.data && typeof error.response.data === 'object') {
      return Promise.reject(new ApiError(error.response.data as ProblemDetail))
    }
    // No RFC 7807 body: a CORS rejection (a bare 403), a proxy error, or no response at
    // all. Surfacing these as a generic "Something went wrong" left a first-time user with
    // nothing to act on, so they become a readable ApiError like every other failure.
    return Promise.reject(new ApiError(bodilessProblem(error)))
  },
)

function bodilessProblem(error: AxiosError): ProblemDetail {
  const status = error.response?.status ?? 0
  const detail =
    status === 0
      ? "We couldn't reach ResolveAI. Check your connection and try again — if the demo was idle it may take a few seconds to wake up."
      : status === 403
        ? import.meta.env.DEV
          ? 'The server refused this request (403, no body). In local development this is almost always CORS: add this page\'s origin to CORS_ALLOWED_ORIGINS.'
          : 'The server refused this request. Try again, or contact your workspace admin if it keeps happening.'
        : status >= 500
          ? 'ResolveAI hit a problem on its side. Try again in a moment.'
          : `The request failed (HTTP ${status}). Try again.`
  return {
    type: 'about:blank',
    title: 'Request failed',
    status,
    detail,
    instance: error.config?.url ?? '',
    errorCode: status === 0 ? 'NETWORK_ERROR' : 'HTTP_' + status,
    traceId: '',
    timestamp: new Date().toISOString(),
  }
}
