import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, api, normaliseFields } from './api'
import { announceSessionChange, clearSession, saveSession } from './session'

/*
 * The signed-in person's own account: the devices they are signed in on, their password, and the
 * steps either side of signing in that happen before a workspace is chosen.
 *
 * Signing in, choosing a workspace and using a reset link run before there is a session to
 * renew, so those three talk to the identity service with fetch directly, the way SignIn always
 * has. Everything else goes through api() like the rest of the console.
 */

/** One device the account is signed in on: the live link of one session family. */
export type AccountSession = {
  /** What to pass to useEndSession to sign this device out. */
  familyId: string
  userAgent?: string | null
  ipAddress?: string | null
  /** When the device last renewed its session, which is when it was last active. */
  issuedAt: string
  /** When the device signed in. */
  signedInAt?: string | null
  expiresAt?: string | null
  /** The device this request came from. */
  current: boolean
}

/** A workspace the account belongs to, as GET /api/users/me/workspaces lists it. */
export type WorkspaceChoice = {
  orgId: string
  /** Null when the workspace's name could not be looked up. */
  name?: string | null
  role?: string | null
}

/** What /api/auth/sign-in and /api/auth/refresh return. */
export type SessionPayload = {
  accessToken: string
  userId: string
  workspaceId?: string | null
  permissions?: string[]
  displayName?: string
  email?: string
  role?: string | null
}

/** The sentence for a request that never reached the service, on every signed-out screen. */
export const NETWORK_ERROR_COPY =
  'We could not reach the service. Check your connection and try again. If it keeps happening, tell your administrator.'

const SESSIONS_KEY = ['account', 'sessions'] as const

/** Stores a session the identity service just issued, the same shape SignIn has always stored. */
export function storeSession(session: SessionPayload, fallbackEmail = '') {
  saveSession(session.accessToken, {
    userId: session.userId,
    workspaceId: session.workspaceId ?? null,
    permissions: session.permissions ?? [],
    displayName: session.displayName ?? fallbackEmail,
    email: session.email ?? fallbackEmail,
    role: session.role ?? null,
  })
}

/** An ApiError from a failed response, carrying the server's own sentence when it sent one. */
async function problemError(response: Response, fallback: string): Promise<ApiError> {
  const body: unknown = await response.json().catch(() => null)
  const problem = typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {}
  const detail = typeof problem.detail === 'string' && problem.detail ? problem.detail : fallback
  const code = typeof problem.code === 'string' ? problem.code : 'unknown_error'
  return new ApiError(response.status, code, detail, response.status >= 500, normaliseFields(problem.errors))
}

/** A fetch that turns "could not connect" into the network ApiError every screen understands. */
async function reach(path: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(path, { credentials: 'include', ...init })
  } catch {
    throw new ApiError(0, 'network_error', NETWORK_ERROR_COPY, true, {})
  }
}

/**
 * Runs `work` while holding the cross-tab refresh lock, where the browser has one.
 *
 * Refresh tokens rotate, and presenting one twice revokes the whole session. Re-issuing the
 * session for a workspace is a refresh, so it waits for any renewal another tab is running.
 */
async function withRefreshLock<T>(work: () => Promise<T>): Promise<T> {
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined
  if (!locks?.request) return work()
  return locks.request('aiwos-refresh', work) as Promise<T>
}

/** The workspaces the signed-in account belongs to, for the picker after sign-in. */
export function fetchWorkspaces(): Promise<WorkspaceChoice[]> {
  return api<WorkspaceChoice[]>('/api/users/me/workspaces')
}

/**
 * Re-issues the session for one workspace and stores it.
 *
 * A session signed in without a workspace carries no permissions. Refreshing with the chosen
 * workspace is what gives it that workspace's role, and the refresh cookie set at sign-in is the
 * credential for it.
 */
export async function enterWorkspace(workspaceId: string): Promise<SessionPayload> {
  return withRefreshLock(async () => {
    const response = await reach(`/api/auth/refresh?workspaceId=${encodeURIComponent(workspaceId)}`, {
      method: 'POST',
    })
    if (!response.ok) throw await problemError(response, 'That workspace could not be opened. Try again.')
    const session = (await response.json()) as SessionPayload
    storeSession(session)
    announceSessionChange()
    return session
  })
}

/** Sets a new password with a reset link's token. Resolves when the password has changed. */
export async function resetPassword(token: string, newPassword: string): Promise<void> {
  const response = await reach('/api/auth/password-reset', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, newPassword }),
  })
  if (!response.ok) throw await problemError(response, 'The password could not be changed. Try again.')
}

/**
 * Ends the session on the server, and here.
 *
 * `everywhere` ends every session the account has, on every device. That one throws when the
 * service cannot be reached, because clearing only this tab would leave the other devices signed
 * in while the screen said otherwise. An ordinary sign-out clears this tab regardless.
 */
export async function signOut(everywhere = false): Promise<void> {
  const path = everywhere ? '/api/auth/sign-out?allDevices=true' : '/api/auth/sign-out'
  if (everywhere) {
    const response = await reach(path, { method: 'POST' })
    if (!response.ok) throw await problemError(response, 'Your other devices could not be signed out. Try again.')
  } else {
    await reach(path, { method: 'POST' }).catch(() => undefined)
  }
  clearSession()
}

/** The devices this account is signed in on, the current one marked. */
export function useAccountSessions() {
  return useQuery({
    queryKey: SESSIONS_KEY,
    queryFn: () => api<AccountSession[]>('/api/users/me/sessions'),
  })
}

/** Signs one device out. */
export function useEndSession() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (familyId: string) =>
      api<void>(`/api/users/me/sessions/${encodeURIComponent(familyId)}`, { method: 'DELETE' }),
    onSettled: () => client.invalidateQueries({ queryKey: SESSIONS_KEY }),
  })
}

/** Changes the password. The server signs out every other device as part of it. */
export function useChangePassword() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { currentPassword: string; newPassword: string }) =>
      api<void>('/api/users/me/password', { method: 'PUT', body: input }),
    onSuccess: () => client.invalidateQueries({ queryKey: SESSIONS_KEY }),
  })
}

/**
 * A device in words, from its user agent: "Chrome on macOS". Plain on purpose - the list only
 * needs to let a person tell their laptop from their phone.
 */
export function describeDevice(userAgent?: string | null): string {
  if (!userAgent) return 'Unknown device'
  const browser = /Edg\//.test(userAgent)
    ? 'Edge'
    : /OPR\/|Opera/.test(userAgent)
      ? 'Opera'
      : /Firefox\//.test(userAgent)
        ? 'Firefox'
        : /Chrome\/|CriOS\//.test(userAgent)
          ? 'Chrome'
          : /Safari\//.test(userAgent)
            ? 'Safari'
            : null
  const system = /iPhone|iPad|iPod/.test(userAgent)
    ? 'iOS'
    : /Android/.test(userAgent)
      ? 'Android'
      : /Mac OS X|Macintosh/.test(userAgent)
        ? 'macOS'
        : /Windows/.test(userAgent)
          ? 'Windows'
          : /CrOS/.test(userAgent)
            ? 'ChromeOS'
            : /Linux/.test(userAgent)
              ? 'Linux'
              : null
  if (browser && system) return `${browser} on ${system}`
  return browser ?? system ?? 'Unknown device'
}
