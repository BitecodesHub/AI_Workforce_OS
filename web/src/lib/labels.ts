import { sentenceCase } from './format'

/*
 * Words and tones for the codes the platform returns.
 *
 * One status word must mean one thing. "Pending" is a document queued for indexing, an approval
 * nobody has decided and a task whose turn has not come, so each kind of record has its own map
 * rather than sharing one, and a status is always looked up together with the kind of thing it
 * belongs to. Anything without an entry reads in sentence case, never as a raw code;
 * labels.test.ts checks that every status the database allows has an entry of its own.
 *
 * Pure: no React, no imports from the UI kit, so the UI kit can re-export TagTone from here
 * without an import cycle.
 */

export type TagTone =
  | 'blue'
  | 'success'
  | 'warning'
  | 'danger'
  | 'neutral'
  | 'operations'
  | 'engineering'
  | 'growth'
  | 'support'

export type StatusKind =
  | 'run'
  | 'task'
  | 'goal'
  | 'approval'
  | 'agent'
  | 'source'
  | 'document'
  | 'integration'
  | 'invitation'
  | 'member'
  | 'outcome'
  | 'circuit'
  | 'toolCall'

export type StatusEntry = { tone: TagTone; label: string }

const entry = (tone: TagTone, label: string): StatusEntry => ({ tone, label })

const RUN_LIKE = {
  running: entry('blue', 'Running'),
  waiting_approval: entry('warning', 'Waiting for approval'),
  completed: entry('success', 'Completed'),
  failed: entry('danger', 'Failed'),
  cancelled: entry('neutral', 'Cancelled'),
}

const STATUS: Record<StatusKind, Record<string, StatusEntry>> = {
  run: {
    ...RUN_LIKE,
    abandoned: entry('neutral', 'Abandoned'),
  },
  task: {
    pending: entry('neutral', 'Waiting to start'),
    ready: entry('neutral', 'Ready to start'),
    ...RUN_LIKE,
    skipped: entry('neutral', 'Skipped'),
  },
  goal: {
    planning: entry('blue', 'Planning'),
    running: entry('blue', 'Running'),
    waiting: entry('warning', 'Waiting'),
    completed: RUN_LIKE.completed,
    failed: RUN_LIKE.failed,
    cancelled: RUN_LIKE.cancelled,
  },
  approval: {
    pending: entry('warning', 'Awaiting decision'),
    approved: entry('success', 'Approved'),
    rejected: entry('danger', 'Rejected'),
    expired: entry('neutral', 'Expired'),
    cancelled: entry('neutral', 'Cancelled'),
  },
  agent: {
    active: entry('success', 'Active'),
    paused: entry('neutral', 'Paused'),
    retired: entry('neutral', 'Retired'),
  },
  source: {
    idle: entry('neutral', 'Not indexed yet'),
    ingesting: entry('blue', 'Indexing'),
    ready: entry('success', 'Ready'),
    failed: entry('danger', 'Failed'),
    reconnect_required: entry('warning', 'Reconnect needed'),
  },
  document: {
    indexed: entry('success', 'Indexed'),
    pending: entry('neutral', 'Queued'),
    skipped: entry('warning', 'Not indexable'),
    failed: entry('danger', 'Failed'),
    tombstoned: entry('neutral', 'Removed at source'),
  },
  integration: {
    sandbox: entry('neutral', 'Sandbox'),
    connected: entry('success', 'Connected'),
    // Not a stored status: the console shows it when a connection's reconnect flag is set.
    reconnect_required: entry('warning', 'Reconnect needed'),
    disconnected: entry('neutral', 'Not connected'),
    // A refresh that failed. The service sets the reconnect flag at the same time.
    error: entry('danger', 'Connection error'),
    revoked: entry('neutral', 'Access revoked'),
  },
  invitation: {
    pending: entry('blue', 'Waiting to be accepted'),
    accepted: entry('success', 'Accepted'),
    revoked: entry('neutral', 'Revoked'),
    expired: entry('neutral', 'Expired'),
  },
  member: {
    active: entry('success', 'Active'),
    invited: entry('blue', 'Invited'),
    suspended: entry('warning', 'Suspended'),
    removed: entry('neutral', 'Removed'),
  },
  outcome: {
    succeeded: entry('success', 'Succeeded'),
    failed: entry('danger', 'Failed'),
    denied: entry('warning', 'Denied'),
  },
  // Resilience4j circuit breaker states, as the provider list reports them (upper case).
  circuit: {
    closed: entry('success', 'Healthy'),
    half_open: entry('warning', 'Recovering'),
    open: entry('danger', 'Paused after failures'),
    forced_open: entry('danger', 'Paused'),
    disabled: entry('neutral', 'Not monitored'),
    metrics_only: entry('neutral', 'Never paused'),
    // Reported when the service did not look the breaker up, for example in the response to
    // turning a provider on or off. The next refresh of the list has the real state.
    unknown: entry('neutral', 'Not checked yet'),
  },
  toolCall: {
    succeeded: entry('success', 'Succeeded'),
    failed: entry('danger', 'Failed'),
    blocked: entry('warning', 'Blocked'),
    // A call that timed out on a tool that cannot safely be repeated: it may or may not have
    // happened, and the console must not guess which.
    indeterminate: entry('warning', 'Outcome unknown'),
  },
}

function statusKey(value: string): string {
  return value.trim().toLowerCase().replace(/-/g, '_')
}

/**
 * The words and tone for a status of a given kind of record. Case and hyphens are ignored, so
 * 'CLOSED', 'half-open' and 'HALF_OPEN' all resolve. An unknown status reads in sentence case in
 * a neutral tone; an empty one reads 'Unknown'.
 */
export function statusLabel(kind: StatusKind, value?: string | null): StatusEntry {
  if (!value || !value.trim()) return entry('neutral', 'Unknown')
  return STATUS[kind][statusKey(value)] ?? entry('neutral', sentenceCase(value))
}

/** True when the status has a label of its own rather than the sentence-case fallback. */
export function hasExplicitStatus(kind: StatusKind, value: string): boolean {
  return Object.hasOwn(STATUS[kind], statusKey(value))
}

/* ---- Audit outcomes --------------------------------------------------------------------------- */

export type AuditOutcome = 'succeeded' | 'failed' | 'denied'

export const OUTCOME_LABEL: Record<AuditOutcome, string> = {
  succeeded: STATUS.outcome.succeeded!.label,
  failed: STATUS.outcome.failed!.label,
  denied: STATUS.outcome.denied!.label,
}

export const OUTCOME_TONE: Record<AuditOutcome, TagTone> = {
  succeeded: STATUS.outcome.succeeded!.tone,
  failed: STATUS.outcome.failed!.tone,
  denied: STATUS.outcome.denied!.tone,
}

/* ---- Agent categories -------------------------------------------------------------------------- */

export const CATEGORY_LABEL: Record<string, string> = {
  operations: 'Operations',
  engineering: 'Engineering',
  growth: 'Growth',
  support: 'Support',
}

/** The category's own tone, or neutral for anything that is not one of the four categories. */
export function categoryTone(category?: string | null): TagTone {
  return category === 'operations' || category === 'engineering' || category === 'growth' || category === 'support'
    ? category
    : 'neutral'
}

/* ---- Roles ------------------------------------------------------------------------------------- */

const SYSTEM_ROLES: Record<string, string> = {
  owner: 'Owner',
  admin: 'Admin',
  manager: 'Manager',
  employee: 'Employee',
  viewer: 'Viewer',
}

/** The five built-in roles capitalised; a workspace's own roles exactly as it named them. */
export function roleLabel(name?: string | null): string {
  if (!name) return 'No role'
  return SYSTEM_ROLES[name] ?? name
}

/* ---- Audit actions ------------------------------------------------------------------------------ */

const AUDIT_ACTIONS: Record<string, string> = {
  'run.complete': 'Run completed',
  'run.fail': 'Run failed',
}

/** 'run.fail' reads 'Run failed'; an approval decision says which way it went when it can. */
export function auditActionLabel(action?: string | null, detail?: Record<string, unknown> | null): string {
  if (!action) return 'Unknown action'
  if (action === 'approval.decide') {
    if (detail?.approved === true) return 'Approved an action'
    if (detail?.approved === false) return 'Rejected an action'
    return 'Decided an approval'
  }
  return AUDIT_ACTIONS[action] ?? sentenceCase(action)
}

/* ---- Runs --------------------------------------------------------------------------------------- */

/** What started a run: a person's direct instruction, or a task in a goal. */
export function startedByLabel(run: { trigger: string; taskId?: string | null }, taskTitle?: string | null): string {
  if (run.trigger === 'manual') return 'Direct instruction'
  if (run.trigger === 'task') return taskTitle ? `Goal task: ${taskTitle}` : 'A goal task'
  return sentenceCase(run.trigger) || 'Unknown'
}

/* ---- Knowledge ------------------------------------------------------------------------------------ */

const SOURCE_KINDS: Record<string, string> = {
  upload: 'Uploaded files',
  google_drive: 'Google Drive',
  notion: 'Notion',
  confluence: 'Confluence',
  github_wiki: 'GitHub wiki',
}

export function sourceKindLabel(kind?: string | null): string {
  if (!kind) return 'Unknown'
  return SOURCE_KINDS[kind] ?? sentenceCase(kind)
}

const MEDIA_TYPES: Record<string, string> = {
  'application/pdf': 'PDF',
  'application/msword': 'Word document',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'Word document',
  'text/plain': 'Plain text',
  'text/markdown': 'Markdown',
  'text/x-markdown': 'Markdown',
  'text/html': 'HTML',
}

const EXTENSIONS: Record<string, string> = {
  pdf: 'PDF',
  doc: 'Word document',
  docx: 'Word document',
  txt: 'Plain text',
  md: 'Markdown',
  markdown: 'Markdown',
  html: 'HTML',
  htm: 'HTML',
}

/** 'application/pdf' reads 'PDF'. Otherwise the file's extension in capitals ('CSV'), or 'File'. */
export function mediaTypeLabel(mime?: string | null, filename?: string | null): string {
  const type = mime?.split(';')[0]?.trim().toLowerCase()
  const known = type ? MEDIA_TYPES[type] : undefined
  if (known) return known
  const dot = filename ? filename.lastIndexOf('.') : -1
  const extension = filename && dot > 0 && dot < filename.length - 1 ? filename.slice(dot + 1).toLowerCase() : ''
  if (extension) return EXTENSIONS[extension] ?? extension.toUpperCase()
  return 'File'
}

/* ---- Tools and integrations ------------------------------------------------------------------------ */

const SERVERS: Record<string, string> = {
  gmail: 'Gmail',
  calendar: 'Google Calendar',
  slack: 'Slack',
  github: 'GitHub',
  jira: 'Jira',
  drive: 'Google Drive',
}

/** A tool server's name: its display name when it has a real one, else a known product name. */
export function serverLabel(server?: string | null, displayName?: string | null): string {
  if (displayName && displayName.trim() && displayName !== server) return displayName
  if (!server) return 'Unknown server'
  return SERVERS[server.toLowerCase()] ?? sentenceCase(server)
}

/** 'gmail.send_message' reads 'Gmail · send message'. */
export function toolLabel(qualified?: string | null): string {
  if (!qualified || !qualified.trim()) return 'Unknown tool'
  const dot = qualified.indexOf('.')
  if (dot <= 0 || dot === qualified.length - 1) return sentenceCase(qualified)
  const tool = sentenceCase(qualified.slice(dot + 1)).toLowerCase()
  return `${serverLabel(qualified.slice(0, dot))} · ${tool}`
}

/* ---- Model providers ----------------------------------------------------------------------------- */

const PROVIDER_KINDS: Record<string, string> = {
  OPENAI_COMPATIBLE: 'OpenAI-compatible',
  ANTHROPIC: 'Anthropic',
  GEMINI: 'Google Gemini',
  BEDROCK: 'AWS Bedrock',
  SANDBOX: 'Offline sandbox',
}

export function providerKindLabel(kind?: string | null): string {
  if (!kind) return 'Unknown'
  return PROVIDER_KINDS[kind.toUpperCase()] ?? sentenceCase(kind)
}

const EMBEDDING_PROVIDERS: Record<string, string> = {
  sandbox: 'Offline sandbox',
  openai: 'OpenAI',
  gemini: 'Google Gemini',
  anthropic: 'Anthropic',
}

/**
 * The embedding provider behind a knowledge source, as people know it. The knowledge service
 * reports a registry id ('openai'), not a provider kind, so providerKindLabel does not fit it.
 */
export function embeddingProviderLabel(provider?: string | null): string {
  if (!provider || !provider.trim()) return 'No provider'
  return EMBEDDING_PROVIDERS[provider.trim().toLowerCase()] ?? sentenceCase(provider)
}

const ACTION_CLASSES: Record<string, string> = {
  OUTBOUND: 'Leaves the workspace',
  DESTRUCTIVE: 'Removes something',
  WRITE: 'Changes data',
  READ: 'Reads data',
}

/** What a class of tool action does, in words: 'OUTBOUND' reads 'Leaves the workspace'. */
export function actionClassLabel(cls?: string | null): string {
  if (!cls) return 'Unknown'
  return ACTION_CLASSES[cls.toUpperCase()] ?? sentenceCase(cls)
}
