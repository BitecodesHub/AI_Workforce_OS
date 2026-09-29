import { formatElapsed, formatRelative } from './format'
import {
  accessToken,
  announceSessionChange,
  clearSession,
  getRefreshPromise,
  profile,
  saveSession,
  setRefreshPromise,
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
}

const GENERIC_FAILURE = 'Something went wrong. Try again.'

/** What platform-core's ErrorCode.VALIDATION_FAILED says when it has nothing more specific. */
const GENERIC_VALIDATION_DETAIL = 'Some of the values supplied are not valid.'

/** Detail keys the platform attaches to failures that describe the resource, not a form field. */
const NON_FIELD_KEYS = new Set(['resource', 'id', 'holders', 'requiredPermission', 'requiredPermissions'])

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function networkError(): ApiError {
  return new ApiError(0, 'network_error', 'The platform could not be reached. Check that it is running.', true, {})
}

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

/**
 * Exchanges the refresh cookie for a new access token.
 *
 * The identity service reads the role afresh on every refresh (AuthService.issue) and returns the
 * permissions, role and name with the token, so the stored profile is rewritten from it. That is
 * what lets a role change reach an open console at the next renewal instead of the next sign-in.
 * A network failure is thrown rather than reported as a failed refresh, so an outage is not
 * mistaken for an expired session.
 */
async function doRefresh(): Promise<boolean> {
  let response: Response
  try {
    response = await fetch('/api/auth/refresh', {
      method: 'POST',
      credentials: 'include',
    })
  } catch {
    throw networkError()
  }
  if (!response.ok) return false
  const data: unknown = await response.json().catch(() => null)
  if (!isRecord(data) || typeof data.accessToken !== 'string' || !data.accessToken) return false
  saveSession(data.accessToken, renewedProfile(profile(), data))
  announceSessionChange()
  return true
}

async function send(path: string, options: Options, token: string | null): Promise<Response> {
  const headers: Record<string, string> = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  try {
    return await fetch(path, {
      method: options.method ?? 'GET',
      headers,
      credentials: 'include',
      body: options.form ?? (options.body !== undefined ? JSON.stringify(options.body) : null),
    })
  } catch {
    throw networkError()
  }
}

async function read<T>(response: Response): Promise<T> {
  if (response.status === 204) return undefined as T
  const text = await response.text()
  const body = text ? safeJson(text) : null
  if (!response.ok) throw toApiError(response, body)
  return body as T
}

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

export async function api<T>(path: string, options: Options = {}): Promise<T> {
  const token = accessToken()
  const response = await send(path, options, token)

  if (response.status === 401 && token) {
    const refreshed = await refreshAccessToken()

    if (refreshed) return read<T>(await send(path, options, accessToken()))

    clearSession()
    const next = encodeURIComponent(window.location.pathname + window.location.search)
    window.location.href = `/sign-in?next=${next}&expired=1`
    throw new ApiError(401, 'token_expired', 'Your session has ended. Sign in again.', false, {})
  }

  return read<T>(response)
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
