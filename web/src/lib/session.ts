/*
 * What the client knows about the current session.
 *
 * The access token is held in sessionStorage rather than localStorage: it is short-lived, and a
 * token that outlives the browser being closed outlives the intent of the person who signed in.
 * The refresh token is never here at all - it is an HttpOnly cookie, the only place a long-lived
 * credential is safe from a cross-site script.
 */

const TOKEN_KEY = 'aiwos.accessToken'
const PROFILE_KEY = 'aiwos.profile'

/**
 * Fired on window after a session renewal rewrites the stored profile, so the shell can re-read
 * can() and the role without waiting for the next navigation.
 */
export const SESSION_EVENT = 'aiwos:session'

/**
 * How long a role change takes to reach someone who is already signed in, for any screen that
 * changes or explains a role. Screens use this sentence rather than writing their own.
 *
 * It holds because the refresh response from the identity service carries the permissions, role
 * and name read from the role at that moment, and api.ts stores them on every renewal. Renewal
 * happens when the access token expires, and its lifetime is 15 minutes (access-token-ttl in
 * platform-defaults.yml). No service checks a token against the current role before then, so
 * the server keeps honouring the old permissions for the same window.
 */
export const ROLE_CHANGE_DELAY_COPY = 'A role change reaches the console within 15 minutes, when the session renews.'

export type Profile = {
  userId: string
  workspaceId: string | null
  permissions: string[]
  displayName: string
  email: string
  role: string | null
}

export function saveSession(accessToken: string, profile: Profile) {
  try {
    sessionStorage.setItem(TOKEN_KEY, accessToken)
    sessionStorage.setItem(PROFILE_KEY, JSON.stringify(profile))
  } catch {
    // Private browsing and blocked site data both throw here. The session still works for this
    // page load; it simply will not survive a reload.
  }
}

/** Tells the shell the stored profile changed. Safe where window or Event is unavailable. */
export function announceSessionChange() {
  try {
    window.dispatchEvent(new Event(SESSION_EVENT))
  } catch {
    /* No window to notify, for example under a test runner without a DOM. */
  }
}

export function clearSession() {
  try {
    sessionStorage.removeItem(TOKEN_KEY)
    sessionStorage.removeItem(PROFILE_KEY)
  } catch {
    /* Nothing to clear if storage was never readable. */
  }
}

export function accessToken(): string | null {
  try {
    return sessionStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function profile(): Profile | null {
  try {
    const raw = sessionStorage.getItem(PROFILE_KEY)
    return raw ? (JSON.parse(raw) as Profile) : null
  } catch {
    return null
  }
}

export function isSignedIn(): boolean {
  return accessToken() !== null
}

/** Whether the signed-in account holds a permission, for hiding what it cannot do. */
export function can(permission: string): boolean {
  return profile()?.permissions.includes(permission) ?? false
}

/** Two letters for the avatar, taken from the name the person actually has. */
export function initials(name: string | undefined | null): string {
  if (!name) return '?'
  const parts = name.trim().split(/\s+/).filter(Boolean)
  const letters = parts.length > 1 ? parts[0]![0]! + parts[parts.length - 1]![0]! : parts[0]!.slice(0, 2)
  return letters.toUpperCase()
}

let refreshPromise: Promise<boolean> | null = null

export function getRefreshPromise(): Promise<boolean> | null {
  return refreshPromise
}

export function setRefreshPromise(promise: Promise<boolean> | null) {
  refreshPromise = promise
}
