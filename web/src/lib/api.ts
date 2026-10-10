// @find: api client, fetch wrapper, http request, ApiError, access token, refresh token, 401 retry, timeout, network failure, error messages, describeApiError, restore session, sign in silently, timeAgo, formatDuration
// @what: The single HTTP client every query and mutation uses: attaches the bearer token, renews it on 401, applies timeouts and turns failures into readable messages.
// @flow: Called by every *Queries.ts hook; uses session.ts for tokens and format.ts for times
import { formatElapsed, formatRelative } from './format'
import {
  accessToken,
  clearSession,
  forgetSignedInHint,
  getRefreshPromise,
  profile,
  saveSession,
  setRefreshPromise,
  shareRenewal,
  takeRecentRenewal,
  type Profile,
} from './session'

/*
 * One way to talk to the platform.
 *
 * Every screen goes through this, so the rules live in one place: the bearer token is attached,
 * a problem document from the server becomes an ApiError the interface can read, and an expired
 * session sends the person back to sign in - remembering where they were - instead of leaving
 * them staring at a screen full of failures.
 */

// @find: api error, error class, http status, field errors
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly retryable: boolean
  readonly fields: Record<string, string>
  /** Correlates the failure with the service logs; worth quoting when something broke on the server. */
  readonly requestId?: string

  constructor(
    status: number,
    code: string,
    message: string,
    retryable: boolean,
    fields: Record<string, string>,
    requestId?: string,
  ) {
    super(message)
    this.status = status
    this.code = code
    this.retryable = retryable
    this.fields = fields
    if (requestId) this.requestId = requestId
  }

  get isPermissionDenied() {
    return this.status === 403
  }

  get isNotFound() {
    return this.status === 404
  }
}

type Options = {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  form?: FormData
  /**
   * Cancels the request when it aborts. A query passes the signal TanStack Query gives it, so
   * leaving a screen, or a newer request for the same data, stops the one still waiting.
   */
  signal?: AbortSignal
  /**
   * How long to wait for the whole answer, in milliseconds, or null to wait for as long as it
   * takes. The default follows from the kind of request (see timeoutFor).
   */
  timeoutMs?: number | null
}

/** A read that takes longer than this is a stalled service, not a slow one. */
export const READ_TIMEOUT_MS = 20_000
/**
 * A write gets far longer: posting a chat message can run model calls before it answers, and
 * giving up on the client does not stop the server, so a short limit would invite a second send.
 */
export const WRITE_TIMEOUT_MS = 120_000

const GENERIC_FAILURE = 'Something went wrong. Try again.'

/** What platform-core's ErrorCode.VALIDATION_FAILED says when it has nothing more specific. */
const GENERIC_VALIDATION_DETAIL = 'Some of the values supplied are not valid.'

/** Detail keys the platform attaches to failures that describe the resource, not a form field. */
const NON_FIELD_KEYS = new Set(['resource', 'id', 'holders', 'requiredPermission', 'requiredPermissions'])

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** What a person is told when no request reaches the service at all (status 0). */
export const NETWORK_FAILURE =
  'We could not reach the service. Check your connection and try again. If it keeps happening, tell your administrator.'

function networkError(): ApiError {
  return new ApiError(0, 'network_error', NETWORK_FAILURE, true, {})
}

/** What a person is told when the service did not answer in time. */
export const TIMEOUT_FAILURE = 'The platform is taking too long to answer.'
/** Added for a write: the server may have carried on after the client stopped waiting. */
const MAY_HAVE_WORKED = 'This may still have gone through; check before retrying.'

function timeoutError(method: string): ApiError {
  const message = method === 'GET' ? TIMEOUT_FAILURE : `${TIMEOUT_FAILURE} ${MAY_HAVE_WORKED}`
  return new ApiError(0, 'timeout', message, true, {})
}

/**
 * How long a request may take. Uploads and voice take as long as the file or the speech does, so
 * they wait; a read gets a short limit, because its answer is cheap and a stalled one should show
 * its error and retry button; a write gets a long one.
 */
function timeoutFor(path: string, options: Options): number | null {
  if (options.timeoutMs !== undefined) return options.timeoutMs
  if (options.form !== undefined || path.startsWith('/api/voice/')) return null
  return (options.method ?? 'GET') === 'GET' ? READ_TIMEOUT_MS : WRITE_TIMEOUT_MS
}

/** AbortSignal.any, for browsers that predate it. */
function anySignal(signals: AbortSignal[]): AbortSignal {
  if (typeof AbortSignal.any === 'function') return AbortSignal.any(signals)
  const controller = new AbortController()
  for (const signal of signals) {
    if (signal.aborted) {
      controller.abort(signal.reason)
      break
    }
    signal.addEventListener('abort', () => controller.abort(signal.reason), { once: true })
  }
  return controller.signal
}

/**
 * The limits on one attempt at a request: the signal to hand to fetch (the caller's, the
 * timeout's, or both), and what to throw when fetch or reading the body fails.
 *
 * A request the caller cancelled is rethrown as the AbortError it is, so TanStack Query treats it
 * as a cancellation and nothing is shown. Only the timeout becomes an ApiError, which the error
 * state and its retry button already know how to show. Anything else is a network failure.
 */
function limitsFor(path: string, options: Options) {
  const timeoutMs = timeoutFor(path, options)
  const timeout = timeoutMs === null ? undefined : AbortSignal.timeout(timeoutMs)
  const signals = [options.signal, timeout].filter((signal): signal is AbortSignal => signal !== undefined)
  const signal = signals.length > 1 ? anySignal(signals) : signals[0]
  const failure = (error: unknown): Error => {
    if (options.signal?.aborted) {
      return error instanceof Error ? error : new DOMException('The request was cancelled.', 'AbortError')
    }
    if (timeout?.aborted) return timeoutError(options.method ?? 'GET')
    return networkError()
  }
  return { signal, failure }
}

// @find: field errors, validation errors, form field problems
/**
 * Field problems from a problem document's `errors`, keyed by field name.
 *
 * A failure raised in code (ApiException.validation) arrives as one `{ field, problem }` pair;
 * bean validation arrives as one entry per field. Entries that describe the resource rather than
 * a field (resource, id, holders, requiredPermission) are left out.
 */
export function normaliseFields(errors: unknown): Record<string, string> {
  if (!isRecord(errors)) return {}
  if (typeof errors.field === 'string' && typeof errors.problem === 'string') {
    return { [errors.field]: errors.problem }
  }
  const fields: Record<string, string> = {}
  for (const [key, value] of Object.entries(errors)) {
    if (typeof value === 'string' && !NON_FIELD_KEYS.has(key)) fields[key] = value
  }
  return fields
}

/** 'systemPrompt' -> 'System prompt', 'candidates[0].modelId' -> 'Candidates 1 model id'. */
function fieldLabel(field: string, labels?: Record<string, string>): string {
  const given = labels?.[field]
  if (given) return given
  const words = field
    .replace(/\[(\d+)\]/g, (_, index: string) => ` ${Number(index) + 1} `)
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[._-]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase()
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : 'A field'
}

function withFullStop(text: string): string {
  return /[.!?]$/.test(text) ? text : `${text}.`
}

function fieldSentence(fields: Record<string, string>, labels?: Record<string, string>): string {
  return Object.entries(fields)
    .map(([field, problem]) => `${fieldLabel(field, labels)}: ${problem}`)
    .join('; ')
}

/** Turns a failed response and its parsed body into the one error shape the interface reads. */
function toApiError(response: Response, body: unknown): ApiError {
  const problem = isRecord(body) ? body : {}
  const code = typeof problem.code === 'string' && problem.code ? problem.code : 'unknown_error'
  const fields = normaliseFields(problem.errors)
  const requestId =
    (typeof problem.requestId === 'string' && problem.requestId) || response.headers.get('X-Request-Id') || undefined

  let message = typeof problem.detail === 'string' && problem.detail ? problem.detail : GENERIC_FAILURE
  // The generic validation sentence names no field. With exactly one problem, saying which field
  // and what is wrong improves every screen that shows error.message, with no change to it.
  if (message === GENERIC_VALIDATION_DETAIL && Object.keys(fields).length === 1) {
    message = withFullStop(fieldSentence(fields))
  }

  const retryable = typeof problem.retryable === 'boolean' ? problem.retryable : response.status >= 500
  return new ApiError(response.status, code, message, retryable, fields, requestId)
}

// @find: error message, readable error, what went wrong, 403 forbidden, 404, 5xx
/**
 * One sentence to show a person for any thrown value.
 *
 * Validation failures name each field (with `labels` mapping a field name to the label on the
 * form), server faults carry the request reference support needs, and anything that is not an
 * ApiError gets a neutral sentence rather than a stack-trace message.
 */
export function describeApiError(error: unknown, labels?: Record<string, string>): string {
  if (!(error instanceof ApiError)) return GENERIC_FAILURE
  const count = Object.keys(error.fields).length
  if (error.code === 'validation_failed' && count > 0) {
    const lead = count === 1 ? 'Check this field' : 'Check these fields'
    return withFullStop(`${lead}: ${fieldSentence(error.fields, labels)}`)
  }
  if (error.status >= 500 && error.requestId) {
    return `${withFullStop(error.message)} Reference: ${error.requestId}`
  }
  return error.message
}

/** The profile a renewal carries, over the stored one. A field the response leaves out keeps its stored value. */
function renewedProfile(previous: Profile | null, data: Record<string, unknown>): Profile {
  const text = (value: unknown): string | undefined => (typeof value === 'string' ? value : undefined)
  const permissions = Array.isArray(data.permissions)
    ? data.permissions.filter((code): code is string => typeof code === 'string')
    : undefined
  return {
    userId: text(data.userId) ?? previous?.userId ?? '',
    // Omitted (never null) under non_null inclusion when the session has no workspace.
    workspaceId: text(data.workspaceId) ?? previous?.workspaceId ?? null,
    // Always present in a refresh response. An empty list is a real revocation and is stored.
    permissions: permissions ?? previous?.permissions ?? [],
    displayName: text(data.displayName) ?? previous?.displayName ?? '',
    email: text(data.email) ?? previous?.email ?? '',
    role: text(data.role) ?? previous?.role ?? null,
  }
}

/** The Web Lock every tab of the console takes before spending the refresh cookie. */
const REFRESH_LOCK = 'aiwos-refresh'

/**
 * Runs a refresh while holding the lock all tabs share, so no two tabs ever present the same
 * refresh cookie at once (see session.ts). Where the browser has no Web Locks, the refresh runs
 * as it is; the per-tab dedupe in refreshAccessToken still applies.
 */
async function underRefreshLock(work: () => Promise<boolean>): Promise<boolean> {
  const locks = typeof navigator === 'undefined' ? undefined : navigator.locks
  if (!locks || typeof locks.request !== 'function') return work()
  return await locks.request(REFRESH_LOCK, () => work())
}

/**
 * Exchanges the refresh cookie for a new access token.
 *
 * The identity service reads the role afresh on every refresh (AuthService.issue) and returns the
 * permissions, role and name with the token, so the stored profile is rewritten from it. That is
 * what lets a role change reach an open console at the next renewal instead of the next sign-in.
 * A network failure is thrown rather than reported as a failed refresh, so an outage is not
 * mistaken for an expired session.
 *
 * Inside the lock, a renewal another tab finished moments ago is adopted instead: the cookie this
 * tab would present has just been rotated, and the token that tab obtained is as good as a new one.
 */
function doRefresh(): Promise<boolean> {
  return underRefreshLock(async () => {
    const adopted = takeRecentRenewal()
    if (adopted) {
      saveSession(adopted.accessToken, adopted.profile)
      return true
    }
    let response: Response
    try {
      response = await fetch('/api/auth/refresh', {
        method: 'POST',
        credentials: 'include',
      })
    } catch {
      throw networkError()
    }
    // A 5xx is the identity service restarting or overloaded, not a refused cookie: report it as a
    // retryable failure, so neither this tab nor (through clearSession) every other tab signs out.
    if (response.status >= 500) {
      throw new ApiError(response.status, 'service_unavailable', NETWORK_FAILURE, true, {})
    }
    if (!response.ok) return false
    const data: unknown = await response.json().catch(() => null)
    if (!isRecord(data) || typeof data.accessToken !== 'string' || !data.accessToken) return false
    const renewed = renewedProfile(profile(), data)
    saveSession(data.accessToken, renewed)
    shareRenewal(data.accessToken, renewed)
    return true
  })
}

type Limits = ReturnType<typeof limitsFor>

async function send(path: string, options: Options, token: string | null, limits: Limits): Promise<Response> {
  const headers: Record<string, string> = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  try {
    return await fetch(path, {
      method: options.method ?? 'GET',
      headers,
      credentials: 'include',
      body: options.form ?? (options.body !== undefined ? JSON.stringify(options.body) : null),
      ...(limits.signal ? { signal: limits.signal } : {}),
    })
  } catch (error) {
    throw limits.failure(error)
  }
}

async function read<T>(response: Response, limits: Limits): Promise<T> {
  if (response.status === 204) return undefined as T
  let text: string
  try {
    // The same signal stops a body that stalls after its headers arrive.
    text = await response.text()
  } catch (error) {
    throw limits.failure(error)
  }
  const body = text ? safeJson(text) : null
  if (!response.ok) throw toApiError(response, body)
  return body as T
}

// @find: refresh token, renew access token, session renewal, POST /api/auth/refresh
/**
 * One refresh at a time, shared with anyone else already waiting on one (api() itself, and
 * fetchAudio in voice.ts for the one binary endpoint api() cannot serve). A network failure
 * propagates as the same ApiError doRefresh throws, rather than being read as an expired session.
 */
export async function refreshAccessToken(): Promise<boolean> {
  const existing = getRefreshPromise()
  if (existing) return existing
  const promise = doRefresh()
  setRefreshPromise(promise)
  try {
    return await promise
  } finally {
    setRefreshPromise(null)
  }
}

/** How restoring a session on boot ended. 'unreachable' is a network failure, worth a retry. */
export type RestoreOutcome = 'restored' | 'signed-out' | 'unreachable'

// @find: restore session, stay signed in, reload sign in, silent sign in
/**
 * One attempt to restore the session from the refresh cookie, for a tab that opened without an
 * access token: a new tab, a reload after closing the browser, a link pasted from elsewhere.
 * Only a failure to reach the service at all is told apart; any refusal reads as signed out, so
 * the person lands where they always did - the home page at "/", otherwise the sign-in form.
 */
export async function restoreSession(): Promise<RestoreOutcome> {
  try {
    if (await refreshAccessToken()) return 'restored'
    forgetSignedInHint()
    return 'signed-out'
  } catch (error) {
    return error instanceof ApiError && (error.status === 0 || error.status >= 500) ? 'unreachable' : 'signed-out'
  }
}

// @find: api call, fetch, request backend, bearer token, GET POST PUT DELETE, timeout
export async function api<T>(path: string, options: Options = {}): Promise<T> {
  const token = accessToken()
  const limits = limitsFor(path, options)
  const response = await send(path, options, token, limits)

  if (response.status === 401 && token) {
    const refreshed = await refreshAccessToken()

    if (refreshed) {
      // The same caller signal for the second attempt, with a fresh time limit of its own: the
      // renewal took part of the first one's.
      const retry = limitsFor(path, options)
      return read<T>(await send(path, options, accessToken(), retry), retry)
    }

    clearSession()
    // The #fragment too, so a link to one approval (/approvals#approval-...) still lands on it.
    const next = encodeURIComponent(window.location.pathname + window.location.search + window.location.hash)
    window.location.href = `/sign-in?next=${next}&expired=1`
    throw new ApiError(401, 'token_expired', 'Your session has ended. Sign in again.', false, {})
  }

  return read<T>(response, limits)
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

/**
 * Human-readable relative time, for "requested 22 minutes ago".
 * @deprecated Use formatRelative from './format', or the <Time> component.
 */
export function timeAgo(iso: string | null | undefined): string {
  return formatRelative(iso)
}

/**
 * "in 23 hours", for approval deadlines. A time already past reads "5 minutes ago".
 * @deprecated Use formatRelative from './format', or the <Time> component.
 */
export function timeUntil(iso: string | null | undefined): string {
  return formatRelative(iso)
}

/**
 * Time between two instants, or from one instant until now.
 * @deprecated Use formatElapsed (or formatRunElapsed for a run) from './format'.
 */
export function formatDuration(from: string | null | undefined, to: string | null | undefined): string {
  return formatElapsed(from, to)
}
