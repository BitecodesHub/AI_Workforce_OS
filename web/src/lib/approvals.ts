// @find: approvals, approval request, pending approval, approve, reject, decide, payload, destructive action, preview, bulk approve, group identical, run outcome, decision error, approval summary
// @what: Reads an approval request in plain words (summary, payload fields, destructive warnings, bulk results) so every screen describes it the same way.
// @flow: Used by the Approvals page, chat approval cards, run traces and the Orchestrator Needs you list; labels from labels.ts
import { ApiError, describeApiError } from './api'
import { plural, truncateWords } from './format'
import { statusLabel, toolLabel } from './labels'

/*
 * Reading an approval request in words, shared between the Approvals queue and anywhere else a
 * pending or decided request needs to say the same thing the same way (a run's own trace, a chat
 * thread's inline approval card, the Orchestrator's "Needs you" list).
 */

/** What a request's summary needs to be read in words: its sentence, and the tool the sentence names. */
type Summarised = { tool?: string | null | undefined; summary: string }

/** A tool as the gateway writes it ('gmail.send_message') and as a model is shown it ('gmail__send_message'). */
function toolNameForms(tool: string): string[] {
  const dot = tool.indexOf('.')
  if (dot > 0) return [tool, `${tool.slice(0, dot)}__${tool.slice(dot + 1)}`]
  const wire = tool.indexOf('__')
  if (wire > 0) return [tool, `${tool.slice(0, wire)}.${tool.slice(wire + 2)}`]
  return [tool]
}

// @find: approval summary, readable request, what the agent wants to do
/**
 * The request's summary in words. The gateway writes it as "Send something outside the workspace
 * using gmail.send_message", so the raw tool name, in either form, is swapped for its label.
 */
export function readableSummary(approval: Summarised): string {
  const tool = approval.tool?.trim()
  if (!tool) return approval.summary
  const label = toolLabel(tool)
  let text = approval.summary
  for (const name of toolNameForms(tool)) text = text.split(name).join(label)
  return text
}

/** The backend guarantees valid JSON; the fallback only covers a payload from before this column was populated. */
export function formatPayload(payload: string): string {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2)
  } catch {
    return payload
  }
}

/** The longest note an approver may leave with a decision; the server cuts anything longer. */
export const NOTE_MAX = 1_000

/** The anchor a run trace links to, so /approvals#approval-<id> lands on that card. */
export const approvalAnchor = (approvalId: string): string => `approval-${approvalId}`

/* ---- What a request will do -------------------------------------------------------------------- */

/** What a request does to the world, as the backend classes it. */
export const isDestructive = (actionClass?: string | null): boolean => actionClass?.toUpperCase() === 'DESTRUCTIVE'

const isOutbound = (actionClass?: string | null): boolean => actionClass?.toUpperCase() === 'OUTBOUND'

/** A request that leaves the workspace or removes something is read before it is decided, so it opens expanded. */
export const previewOpensByDefault = (actionClass?: string | null): boolean =>
  isOutbound(actionClass) || isDestructive(actionClass)

/** The promise made over the details of a request, by what it does. */
export function previewCaption(actionClass?: string | null): string {
  if (isOutbound(actionClass)) return 'Exactly what will be sent'
  if (isDestructive(actionClass)) return 'Exactly what will be removed'
  return 'Exactly what will change'
}

/** The label on the control that opens a request's details: 'Show what will be sent'. */
export function previewToggleLabel(actionClass?: string | null): string {
  if (isOutbound(actionClass)) return 'Show what will be sent'
  if (isDestructive(actionClass)) return 'Show what will be removed'
  return 'Show what will change'
}

/* ---- The request, field by field --------------------------------------------------------------- */

/** One top-level field of a request: what it is called, and its value as text. */
export type PayloadRow = { key: string; label: string; value: string }

/**
 * A request read for a person. Every top-level field is a row, so the one an approver does not
 * expect - a bcc, an attachment - is as visible as the one they do. A payload that is not an
 * object, or is not JSON at all, is shown as the text it is.
 */
export type ParsedPayload = { kind: 'fields'; known: PayloadRow[]; other: PayloadRow[] } | { kind: 'raw'; text: string }

/** The fields most requests carry, in the order they are read: who, what it says, where, how much. */
const KNOWN_FIELDS: ReadonlyArray<readonly [string, string]> = [
  ['to', 'To'],
  ['cc', 'Cc'],
  ['bcc', 'Bcc'],
  ['subject', 'Subject'],
  ['body', 'Body'],
  ['text', 'Message'],
  ['channel', 'Channel'],
  ['amount', 'Amount'],
  ['currency', 'Currency'],
]

/** 'reply_to' reads 'Reply to', 'replyTo' too. */
function fieldLabel(key: string): string {
  const spaced = key
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_\-.]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase()
  return spaced ? spaced.charAt(0).toUpperCase() + spaced.slice(1) : key
}

/** Text stays text, so a line break in an email body shows as one; anything nested is compact JSON. */
function fieldValue(value: unknown): string {
  return typeof value === 'string' ? value : JSON.stringify(value)
}

// @find: approval payload, parse payload, fields to be sent, preview what will happen
export function parsePayload(payload: string): ParsedPayload {
  let parsed: unknown
  try {
    parsed = JSON.parse(payload)
  } catch {
    return { kind: 'raw', text: payload }
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return { kind: 'raw', text: formatPayload(payload) }
  }
  const entries = Object.entries(parsed as Record<string, unknown>)
  const known: PayloadRow[] = []
  const claimed = new Set<string>()
  for (const [name, label] of KNOWN_FIELDS) {
    for (const [key, value] of entries) {
      if (key.toLowerCase() !== name || claimed.has(key)) continue
      claimed.add(key)
      known.push({ key, label, value: fieldValue(value) })
    }
  }
  const other = entries
    .filter(([key]) => !claimed.has(key))
    .map(([key, value]) => ({ key, label: fieldLabel(key), value: fieldValue(value) }))
  return { kind: 'fields', known, other }
}

/**
 * The first couple of fields on one line - 'To: jane@example.com · Subject: Welcome' - to tell
 * apart requests that read alike.
 */
export function payloadHeadline(payload: string, fields = 2, each = 60): string {
  const parsed = parsePayload(payload)
  if (parsed.kind === 'raw') return truncateWords(parsed.text, each * fields)
  return [...parsed.known, ...parsed.other]
    .slice(0, fields)
    .map((row) => `${row.label}: ${truncateWords(row.value, each)}`)
    .join(' · ')
}

/* ---- Who and what a request is for -------------------------------------------------------------- */

/** The person who asked for the work, in the words an approval card uses. */
function askedBy(requestedBy: string | null, me: string | null, nameOf: (userId: string) => string): string | null {
  if (!requestedBy) return null
  return me != null && requestedBy === me ? 'You' : nameOf(requestedBy)
}

/**
 * 'For: <goal> · asked by <person>'. A run started directly on an agent has no goal; work nobody
 * asked for, such as a schedule nobody owns, has no person to name.
 */
export function contextLine(
  approval: { goalTitle: string | null; requestedBy: string | null },
  me: string | null,
  nameOf: (userId: string) => string,
): string {
  const who = askedBy(approval.requestedBy, me, nameOf)
  const goal = `For: ${approval.goalTitle?.trim() || 'a direct instruction'}`
  return who ? `${goal} · asked by ${who}` : goal
}

/* ---- Several at once ----------------------------------------------------------------------------- */

/** Requests that say the same thing, from the same agent, through the same tool. */
export type ApprovalGroup<T> = { key: string; items: T[] }

// @find: group identical approvals, bulk approve, same request many times
/**
 * Identical requests, together: a schedule that posts the same standup summary every morning
 * leaves a queue of them. Only groups of at least `min` are returned, in the order they first
 * appear.
 */
export function groupIdentical<T extends { agentId: string; tool: string | null; summary: string }>(
  items: readonly T[],
  min = 2,
): ApprovalGroup<T>[] {
  const groups = new Map<string, T[]>()
  for (const item of items) {
    const key = JSON.stringify([item.agentId, item.tool, item.summary])
    const group = groups.get(key)
    if (group) group.push(item)
    else groups.set(key, [item])
  }
  return [...groups]
    .filter(([, group]) => group.length >= min)
    .map(([key, group]) => ({ key, items: group }))
}

/** What the server says happened to each approval in a bulk decision. */
export type BulkResult = 'decided' | 'already_decided' | 'expired' | 'forbidden' | 'not_found'

// @find: bulk approve result, approve all, decided already expired
/** One sentence for a bulk decision: what was decided, then what could not be. */
export function bulkSummary(results: ReadonlyArray<{ result: string }>, approved: boolean): string {
  const count = (kind: BulkResult) => results.filter((item) => item.result === kind).length
  const decided = count('decided')
  const parts: string[] = []
  parts.push(
    decided > 0
      ? `${approved ? 'Approved' : 'Rejected'} ${decided}.`
      : `Nothing was ${approved ? 'approved' : 'rejected'}.`,
  )
  const already = count('already_decided')
  if (already > 0) parts.push(`${plural(already, 'request was', 'requests were')} already decided by someone else.`)
  const expired = count('expired')
  if (expired > 0) parts.push(`${plural(expired, 'request', 'requests')} expired before a decision was made.`)
  const forbidden = count('forbidden')
  if (forbidden > 0) parts.push(`${plural(forbidden, 'request was', 'requests were')} not yours to decide.`)
  const missing = count('not_found')
  if (missing > 0) parts.push(`${plural(missing, 'request', 'requests')} could not be found.`)
  return parts.join(' ')
}

/* ---- After a decision ---------------------------------------------------------------------------- */

// @find: run outcome after approval, what happened next
/** What happened to the run once the decision was made, from DecisionResult.runStatus. */
export function runOutcome(runStatus: string | null | undefined): string {
  switch (runStatus?.toLowerCase()) {
    // The usual answer: approving hands the run back to its agent, which carries on in the
    // background rather than inside the approver's request.
    case 'running':
      return 'Approved. The agent is continuing; open the run to follow along.'
    case 'completed':
      return 'The run finished.'
    case 'waiting_approval':
      return 'The run is waiting for another approval.'
    case 'waiting_input':
      return 'The run is waiting for an answer to a question.'
    case 'failed':
      return 'The run failed.'
    case 'cancelled':
      return 'The run was stopped.'
    case undefined:
    case '':
      return 'Open the run to see where it stands.'
    default:
      return `Run status: ${statusLabel('run', runStatus).label}.`
  }
}

// @find: approval decision error, could not approve, already decided
/** Why a decision did not go through, in words. The queue refreshes after any failure. */
export function decisionError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'approval_already_decided') return 'Someone already decided this request.'
    if (error.code === 'approval_expired') return 'This request expired, so the run was stopped.'
    // The workspace asks for a second person to decide: the server's own sentence says so.
    if (error.code === 'policy_violation') return `${error.message.replace(/\.$/, '')}.`
    if (error.isPermissionDenied) return 'Deciding this request needs a permission your role does not have.'
    // The gateway stopped waiting, but the decision had already been saved.
    if (error.status === 504) return 'The decision was recorded. The agent is still working; the run will update shortly.'
  }
  return describeApiError(error)
}
