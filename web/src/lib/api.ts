import { accessToken, clearSession, getRefreshPromise, setRefreshPromise } from './session'

/*
 * One way to talk to the platform.
 *
 * Every screen goes through this, so the rules live in one place: the bearer token is attached,
 * a problem document from the server becomes an ApiError the interface can read, and an expired
 * session sends the person back to sign in - remembering where they were - instead of leaving
 * them staring at a screen full of failures.
 */

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly retryable: boolean
  readonly fields: Record<string, string>

  constructor(status: number, code: string, message: string, retryable: boolean, fields: Record<string, string>) {
    super(message)
    this.status = status
    this.code = code
    this.retryable = retryable
    this.fields = fields
  }

  get isPermissionDenied() {
    return this.status === 403
  }

  get isNotFound() {
    return this.status === 404
  }
}

type Options = {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  form?: FormData
}

async function doRefresh(): Promise<boolean> {
  const response = await fetch('/api/auth/refresh', {
    method: 'POST',
    credentials: 'include',
  })
  if (!response.ok) return false
  const data = await response.json().catch(() => null)
  if (data?.accessToken) {
    sessionStorage.setItem('aiwos.accessToken', data.accessToken)
    return true
  }
  return false
}

export async function api<T>(path: string, options: Options = {}): Promise<T> {
  const headers: Record<string, string> = {}
  const token = accessToken()
  if (token) headers.Authorization = `Bearer ${token}`
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'

  let response: Response
  try {
    response = await fetch(path, {
      method: options.method ?? 'GET',
      headers,
      credentials: 'include',
      body: options.form ?? (options.body !== undefined ? JSON.stringify(options.body) : null),
    })
  } catch {
    throw new ApiError(0, 'network_error', 'The platform could not be reached. Check that it is running.', true, {})
  }

  if (response.status === 401 && token) {
    const existing = getRefreshPromise()
    const refreshed = existing
      ? await existing
      : await (async () => {
          const promise = doRefresh()
          setRefreshPromise(promise)
          try {
            return await promise
          } finally {
            setRefreshPromise(null)
          }
        })()

    if (refreshed) {
      const newToken = accessToken()
      const retryHeaders: Record<string, string> = {}
      if (newToken) retryHeaders.Authorization = `Bearer ${newToken}`
      if (options.body !== undefined) retryHeaders['Content-Type'] = 'application/json'

      const retryResponse = await fetch(path, {
        method: options.method ?? 'GET',
        headers: retryHeaders,
        credentials: 'include',
        body: options.form ?? (options.body !== undefined ? JSON.stringify(options.body) : null),
      })

      if (retryResponse.status === 204) return undefined as T

      const retryText = await retryResponse.text()
      const retryBody = retryText ? safeJson(retryText) : null

      if (!retryResponse.ok) {
        const problem = (retryBody ?? {}) as { code?: string; detail?: string; retryable?: boolean; errors?: Record<string, unknown> }
        const fields: Record<string, string> = {}
        for (const [key, value] of Object.entries(problem.errors ?? {})) {
          if (typeof value === 'string') fields[key] = value
        }
        throw new ApiError(
          retryResponse.status,
          problem.code ?? 'unknown_error',
          problem.detail ?? 'Something went wrong. Try again.',
          problem.retryable ?? retryResponse.status >= 500,
          fields,
        )
      }
      return retryBody as T
    }

    clearSession()
    const next = encodeURIComponent(window.location.pathname)
    window.location.href = `/sign-in?next=${next}&expired=1`
    throw new ApiError(401, 'token_expired', 'Your session has ended. Sign in again.', false, {})
  }

  if (response.status === 204) return undefined as T

  const text = await response.text()
  const body = text ? safeJson(text) : null

  if (!response.ok) {
    const problem = (body ?? {}) as { code?: string; detail?: string; retryable?: boolean; errors?: Record<string, unknown> }
    const fields: Record<string, string> = {}
    for (const [key, value] of Object.entries(problem.errors ?? {})) {
      if (typeof value === 'string') fields[key] = value
    }
    throw new ApiError(
      response.status,
      problem.code ?? 'unknown_error',
      problem.detail ?? 'Something went wrong. Try again.',
      problem.retryable ?? response.status >= 500,
      fields,
    )
  }
  return body as T
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

/** Human-readable relative time, for "requested 22 minutes ago". */
export function timeAgo(iso: string | null | undefined): string {
  if (!iso) return '—'
  const seconds = Math.round((Date.now() - new Date(iso).getTime()) / 1000)
  if (seconds < 45) return 'just now'
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? '' : 's'} ago`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} hour${hours === 1 ? '' : 's'} ago`
  const days = Math.round(hours / 24)
  return `${days} day${days === 1 ? '' : 's'} ago`
}

/** "in 23 hours", for approval deadlines. */
export function timeUntil(iso: string | null | undefined): string {
  if (!iso) return '—'
  const minutes = Math.round((new Date(iso).getTime() - Date.now()) / 60000)
  if (minutes <= 0) return 'now'
  if (minutes < 60) return `in ${minutes} minute${minutes === 1 ? '' : 's'}`
  const hours = Math.round(minutes / 60)
  return `in ${hours} hour${hours === 1 ? '' : 's'}`
}

export function formatDuration(from: string | null | undefined, to: string | null | undefined): string {
  if (!from) return '—'
  const end = to ? new Date(to).getTime() : Date.now()
  const seconds = Math.max(0, Math.round((end - new Date(from).getTime()) / 1000))
  if (seconds < 60) return `${seconds}s`
  return `${Math.floor(seconds / 60)}m ${String(seconds % 60).padStart(2, '0')}s`
}
