/*
 * What the client knows about the current session.
 *
 * The access token is held in sessionStorage, one copy per tab, rather than in localStorage: it
 * is short-lived (5 minutes), and it is the credential a cross-site script would most like to
 * read. The refresh token is never here at all - it is an HttpOnly cookie, the only place a
 * long-lived credential is safe from a cross-site script.
 *
 * A session therefore outlives any one tab. The refresh cookie lasts 30 days from its last use
 * (refresh-token-ttl in platform-defaults.yml), so a new tab, a reload or a pasted link restores
 * the session from it (App asks once, on boot) rather than asking for the password again. It ends
 * when the person signs out, which also tells every other open tab to sign out, or when it goes
 * unused for 30 days.
 *
 * Refresh tokens rotate, and presenting one that was already spent revokes the whole family
 * (AuthService.refresh), signing the person out everywhere. So tabs never refresh with the same
 * cookie at once: api.ts runs each refresh under one lock shared by every tab, and a tab that
 * waited for the lock takes the token the tab before it has just broadcast here, rather than
 * spending the cookie a second time.
 */

const TOKEN_KEY = 'aiwos.accessToken'
const PROFILE_KEY = 'aiwos.profile'

/**
 * A hint, shared by every tab and kept across restarts, that this browser has signed in and not
 * signed out since. It holds no credential. It only decides whether a visit to "/" without a
 * token is worth a refresh request before the public home page shows: a first-time visitor gets
 * the home page at once, with no request and no wait.
 */
const SIGNED_IN_HINT = 'aiwos.signedIn'

/**
 * Fired on window whenever the stored session changes in this tab: a sign-in, a renewal, or a
 * sign-out broadcast by another tab. The shell re-reads can() and the role without waiting for
 * the next navigation.
 */
export const SESSION_EVENT = 'aiwos:session'

/** The detail of a SESSION_EVENT raised because another tab signed out. */
const SIGNED_OUT_ELSEWHERE = 'signed-out-elsewhere'

/** The channel every tab of the console shares, for renewals and sign-outs. */
const CHANNEL_NAME = 'aiwos-session'

/**
 * How recent another tab's renewal must be for this tab to adopt its token rather than refresh.
 * Long enough to cover tabs that queued on the lock together, short enough that the token adopted
 * is nowhere near its 5 minute expiry.
 */
const ADOPT_WITHIN_MS = 10_000

/**
 * How long a role change takes to reach someone who is already signed in, for any screen that
 * changes or explains a role. Screens use this sentence rather than writing their own.
 *
 * It holds because the refresh response from the identity service carries the permissions, role
 * and name read from the role at that moment, and api.ts stores them on every renewal. Renewal
 * happens when the access token expires, and its lifetime is 5 minutes (access-token-ttl in
 * platform-defaults.yml). No service checks a token against the current role before then, so
 * the server keeps honouring the old permissions for the same window.
 */
export const ROLE_CHANGE_DELAY_COPY = 'A role change reaches the console within 5 minutes, when the session renews.'

export type Profile = {
  userId: string
  workspaceId: string | null
  permissions: string[]
  displayName: string
  email: string
  role: string | null
}

type SessionMessage = { type: 'renewed'; accessToken: string; profile: Profile } | { type: 'signed-out' }

type Renewal = { accessToken: string; profile: Profile; receivedAt: number }

/** undefined until first needed; null where the browser has no BroadcastChannel. */
let channel: BroadcastChannel | null | undefined

/** The latest renewal another tab broadcast, kept for a refresh in this tab to adopt. */
let lastRenewal: Renewal | null = null

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isProfile(value: unknown): value is Profile {
  return isRecord(value) && typeof value.userId === 'string' && Array.isArray(value.permissions)
}

/** Opens this tab's end of the shared channel, once. Every other tab hears what it posts. */
function sessionChannel(): BroadcastChannel | null {
  if (channel !== undefined) return channel
  try {
    channel = typeof BroadcastChannel === 'function' ? new BroadcastChannel(CHANNEL_NAME) : null
  } catch {
    channel = null
  }
  if (channel) {
    channel.onmessage = (event: MessageEvent) => receive(event.data)
    // Node's BroadcastChannel (the test runner's) would otherwise keep the process alive.
    // Browsers have no unref.
    ;(channel as BroadcastChannel & { unref?: () => void }).unref?.()
  }
  return channel
}

function post(message: SessionMessage) {
  try {
    sessionChannel()?.postMessage(message)
  } catch {
    /* A closed or unavailable channel: the other tabs find out at their next request instead. */
  }
}

function receive(message: unknown) {
  if (!isRecord(message)) return
  if (message.type === 'renewed' && typeof message.accessToken === 'string' && isProfile(message.profile)) {
    lastRenewal = { accessToken: message.accessToken, profile: message.profile, receivedAt: Date.now() }
    return
  }
  // Another tab signed out, which ended the session on the server and cleared the shared cookie.
  // This tab's own copy of the token must not linger for up to 5 minutes after that.
  if (message.type === 'signed-out' && accessToken() !== null) {
    removeStored()
    lastRenewal = null
    dispatch(new CustomEvent(SESSION_EVENT, { detail: SIGNED_OUT_ELSEWHERE }))
  }
}

function dispatch(event: Event) {
  try {
    window.dispatchEvent(event)
  } catch {
    /* No window to notify, for example under a test runner without a DOM. */
  }
}

function removeStored() {
  try {
    sessionStorage.removeItem(TOKEN_KEY)
    sessionStorage.removeItem(PROFILE_KEY)
  } catch {
    /* Nothing to clear if storage was never readable. */
  }
}

function setSignedInHint(signedIn: boolean) {
  try {
    if (signedIn) localStorage.setItem(SIGNED_IN_HINT, '1')
    else localStorage.removeItem(SIGNED_IN_HINT)
  } catch {
    /* Blocked storage: "/" shows the home page, and the sign-in form restores nothing it needs. */
  }
}

/** Whether this browser signed in before and has not signed out since (see SIGNED_IN_HINT). */
export function maySignInSilently(): boolean {
  try {
    return localStorage.getItem(SIGNED_IN_HINT) === '1'
  } catch {
    return false
  }
}

/** Forgets the hint once the refresh cookie has been refused: there is nothing left to restore. */
export function forgetSignedInHint() {
  setSignedInHint(false)
}

/** Stores a session in this tab and tells the shell, which re-reads the role and who is signed in. */
export function saveSession(accessToken: string, profile: Profile) {
  try {
    sessionStorage.setItem(TOKEN_KEY, accessToken)
    sessionStorage.setItem(PROFILE_KEY, JSON.stringify(profile))
  } catch {
    // Private browsing and blocked site data both throw here. The session still works for this
    // page load; it simply will not survive a reload.
  }
  setSignedInHint(true)
  // A renewal another tab shared before this save is for the session this tab just replaced (a
  // sign-in or a workspace switch); adopting it later would quietly switch the tab back.
  lastRenewal = null
  announceSessionChange()
}

/** Tells the shell the stored session changed. Safe where window or Event is unavailable. */
export function announceSessionChange() {
  try {
    dispatch(new Event(SESSION_EVENT))
  } catch {
    /* No Event constructor to build one with. */
  }
}

/**
 * Forgets the session in this tab and tells every other tab to forget it too. The caller moves
 * this tab on (to sign in, usually), as before; the other tabs move themselves.
 */
export function clearSession() {
  removeStored()
  setSignedInHint(false)
  lastRenewal = null
  post({ type: 'signed-out' })
}

/** Offers a renewal this tab just obtained to the other tabs, so one waiting on the lock can adopt it. */
export function shareRenewal(accessToken: string, profile: Profile) {
  lastRenewal = null
  post({ type: 'renewed', accessToken, profile })
}

/**
 * A renewal another tab broadcast in the last few seconds, for a tab about to refresh. Taken at
 * most once, and never when it is the very token this tab already holds (that one has just been
 * refused, or there would be no refresh).
 */
export function takeRecentRenewal(): { accessToken: string; profile: Profile } | null {
  sessionChannel()
  const renewal = lastRenewal
  lastRenewal = null
  if (!renewal || Date.now() - renewal.receivedAt > ADOPT_WITHIN_MS) return null
  if (renewal.accessToken === accessToken()) return null
  return { accessToken: renewal.accessToken, profile: renewal.profile }
}

/** What a change of session meant, for whoever watches it. */
export type SessionOwnerChange = 'switched' | 'signed-out-elsewhere'

/** Whose data the session reads: the person and the workspace. Null when signed out. */
function ownerOf(who: Profile | null): string | null {
  return who ? `${who.userId}/${who.workspaceId ?? ''}` : null
}

/**
 * Calls `onChange` when the session in this tab comes to belong to somebody else - a sign-in as
 * another person or into another workspace, a demo role switch - or when another tab signs out.
 * A renewal for the same person in the same workspace is not a change. Returns the unsubscribe.
 *
 * It remembers the owner it last saw, so a sign-out followed by a sign-in as the same person is
 * no change either, and that person keeps what is already loaded.
 */
export function watchSessionOwner(onChange: (change: SessionOwnerChange) => void): () => void {
  sessionChannel()
  let owner = ownerOf(profile())
  const onSession = (event: Event) => {
    const elsewhere = event instanceof CustomEvent && event.detail === SIGNED_OUT_ELSEWHERE
    const next = ownerOf(profile())
    if (!elsewhere && next === owner) return
    owner = next
    onChange(elsewhere ? 'signed-out-elsewhere' : 'switched')
  }
  window.addEventListener(SESSION_EVENT, onSession)
  return () => window.removeEventListener(SESSION_EVENT, onSession)
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
