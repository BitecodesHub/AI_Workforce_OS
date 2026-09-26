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
