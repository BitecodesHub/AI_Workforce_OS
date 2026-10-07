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
  | 'question'

export type StatusEntry = { tone: TagTone; label: string }

const entry = (tone: TagTone, label: string): StatusEntry => ({ tone, label })

const RUN_LIKE = {
  running: entry('blue', 'Running'),
  waiting_approval: entry('warning', 'Waiting for approval'),
  waiting_input: entry('warning', 'Waiting for an answer'),
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
    // Nothing sets this any more (deleting a document removes it outright); kept for old rows.
    tombstoned: entry('neutral', 'Removed'),
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
    // A sign-in refused because the account was locked after too many wrong passwords.
    locked: entry('warning', 'Locked'),
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
    // An approver rejected the action as written and asked the agent to revise it.
    rejected: entry('warning', 'Sent back'),
  },
  question: {
    pending: entry('warning', 'Waiting for an answer'),
    answered: entry('success', 'Answered'),
    expired: entry('neutral', 'Expired'),
    cancelled: entry('neutral', 'Withdrawn'),
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

export type AuditOutcome = 'succeeded' | 'failed' | 'denied' | 'locked'

export const OUTCOME_LABEL: Record<AuditOutcome, string> = {
  succeeded: STATUS.outcome.succeeded!.label,
  failed: STATUS.outcome.failed!.label,
  denied: STATUS.outcome.denied!.label,
  locked: STATUS.outcome.locked!.label,
}

export const OUTCOME_TONE: Record<AuditOutcome, TagTone> = {
  succeeded: STATUS.outcome.succeeded!.tone,
  failed: STATUS.outcome.failed!.tone,
  denied: STATUS.outcome.denied!.tone,
  locked: STATUS.outcome.locked!.tone,
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

/* ---- Goals -------------------------------------------------------------------------------------- */

const GOAL_SOURCE: Record<string, StatusEntry> = {
  manual: entry('neutral', 'Started by hand'),
  chat: entry('blue', 'From Chat'),
  schedule: entry('operations', 'Scheduled'),
}

/** How a goal came to exist: typed in directly, routed from a chat conversation, or a schedule firing. */
export function goalSourceLabel(source?: string | null): StatusEntry {
  if (!source) return GOAL_SOURCE.manual!
  return GOAL_SOURCE[source.trim().toLowerCase()] ?? entry('neutral', sentenceCase(source))
}

/* ---- Schedules ---------------------------------------------------------------------------------- */

/**
 * A schedule's own state. Not a stored status (the schedules table has no such column): the
 * console reads this straight from `enabled`, so 'Active' and 'Paused' mean exactly what the
 * toggle shows. `done` is a one-off that has already run: nobody paused it, it simply finished,
 * and resuming it would only fire it again at once (lib/schedules.ts isScheduleDone).
 */
export function scheduleStateLabel(enabled: boolean, done = false): StatusEntry {
  if (done) return entry('neutral', 'Done')
  return enabled ? entry('success', 'Active') : entry('neutral', 'Paused')
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
  'run.abandon': 'Run stopped responding',
  'run.cancel': 'Run cancelled',
  'run.stop': 'Stopped a run',
  'question.ask': 'Agent asked a question',
  'question.answer': 'Answered an agent’s question',
  'question.expire': 'Question closed without an answer',
  'question.extend': 'Kept a question open longer',
  'agent.create_from_template': 'Added a ready-made assistant',
  'provider.test_key': 'Checked a model key',
  'approval.decide': 'Decided an approval',
  'approval.expire': 'Approval expired without a decision',
  'approval.settings_update': 'Changed who may approve their own requests',
  'goal.retry': 'Tried a goal again',
  'goal.cancel': 'Cancelled a goal',
  'conversation.delete': 'Deleted a conversation',
  'schedule.create': 'Created a schedule',
  'schedule.update': 'Changed a schedule',
  'schedule.pause': 'Paused a schedule',
  'schedule.resume': 'Resumed a schedule',
  'schedule.run_now': 'Ran a schedule now',
  'schedule.delete': 'Deleted a schedule',
  'schedule.owner_change': 'Handed a schedule to someone else',
  'orchestrator.stop_all': 'Stopped all agent work',
  'provider.enable': 'Turned a provider on for this workspace',
  'provider.disable': 'Turned a provider off for this workspace',
  'notifications.update': 'Changed where this workspace is notified',
  'auth.sign_in': 'Signed in',
  'auth.password_change': 'Changed a password',
  'auth.password_reset_link.create': 'Made a password reset link',
  'auth.password_reset_link.redeem': 'Used a password reset link',
  'session.revoke_all': 'Signed a person out everywhere',
  'session.reuse_detected': 'Ended a session that looked stolen',
  'member.role_change': 'Changed a member’s role',
  'member.remove': 'Removed a member',
  'role.create': 'Created a role',
  'role.update': 'Changed a role',
  'role.delete': 'Deleted a role',
  'invitation.create': 'Invited someone',
  'invitation.accept': 'Accepted an invitation',
  'invitation.revoke': 'Withdrew an invitation',
  'credential.store': 'Stored a key',
  'credential.delete': 'Deleted a key',
  'credential.reveal': 'A service used a stored key',
  'workspace.update': 'Changed the workspace settings',
  'document.upload': 'Uploaded a document',
  'document.replace': 'Replaced a document',
  'document.delete': 'Deleted a document',
  'source.create': 'Created a document source',
  'source.update': 'Changed a document source',
  'source.delete': 'Deleted a document source',
  'chat.answer.rated': 'Rated an answer',
  'chat.read_private': 'Read a private conversation',
  'conversation.share': 'Shared a conversation with the workspace',
  'conversation.make_private': 'Made a conversation private',
  'conversation.add_people': 'Added people to a conversation',
  'approval.request_changes': 'Sent an action back with feedback',
  'retention.update': 'Changed how long run detail is kept',
  'budget.update': 'Changed a spending cap',
  'model_policy.update': 'Changed the workspace’s model policy',
  'agent.model_policy.set': 'Chose models for an agent',
  'agent.model_policy.clear': 'Returned an agent to the workspace’s models',
  'agent.grant.add': 'Gave an agent a tool',
  'agent.grant.update': 'Changed what an agent’s tool may do',
  'agent.grant.remove': 'Took a tool from an agent',
  'memory.create': 'Added to an agent’s memory',
  'memory.update': 'Changed an agent’s memory',
  'memory.delete': 'Removed from an agent’s memory',
}

/** Every action the log can hold a plain-words name for, for the filter list. */
export const AUDIT_ACTION_CODES: string[] = Object.keys(AUDIT_ACTIONS)

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
  if (run.trigger === 'task') return taskTitle ? `Task: ${taskTitle}` : 'A task'
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
  outlook: 'Microsoft Outlook',
  teams: 'Microsoft Teams',
  notion: 'Notion',
  linear: 'Linear',
  hubspot: 'HubSpot',
  salesforce: 'Salesforce',
  zendesk: 'Zendesk',
  confluence: 'Confluence',
  asana: 'Asana',
  sheets: 'Google Sheets',
  stripe: 'Stripe',
  zoom: 'Zoom',
  webhook: 'Webhook',
  person: 'A person',
}

/* ---- Connector categories ------------------------------------------------------------------------ */

/**
 * The catalog's connector categories (mcp-core ConnectorCatalog), in the order the Connectors page
 * offers them as filters. 'other' covers a connector an older service sent without one.
 */
export const CONNECTOR_CATEGORY_LABEL: Record<string, string> = {
  communication: 'Communication',
  productivity: 'Productivity',
  engineering: 'Engineering',
  sales: 'Sales and CRM',
  support: 'Customer support',
  files: 'Files and documents',
  finance: 'Finance',
  automation: 'Automation',
  voice: 'Voice',
  other: 'Other',
}

export const CONNECTOR_CATEGORY_ORDER: readonly string[] = Object.keys(CONNECTOR_CATEGORY_LABEL)

/** 'sales' reads 'Sales and CRM'; anything unknown in sentence case. */
export function connectorCategoryLabel(category?: string | null): string {
  if (!category || !category.trim()) return CONNECTOR_CATEGORY_LABEL.other!
  return CONNECTOR_CATEGORY_LABEL[category.trim().toLowerCase()] ?? sentenceCase(category)
}

/**
 * The three states a connector is shown in (lib/connectors.ts connectorState), with words that say
 * what each means for the person reading: practice data, a live account, or something to fix.
 */
const CONNECTOR_STATES: Record<string, StatusEntry> = {
  connected: entry('success', 'Connected'),
  sandbox: entry('neutral', 'Sandbox'),
  attention: entry('warning', 'Needs attention'),
}

export function connectorStateLabel(state?: string | null): StatusEntry {
  if (!state) return entry('neutral', 'Unknown')
  return CONNECTOR_STATES[state] ?? entry('neutral', sentenceCase(state))
}

/** A tool server's name: its display name when it has a real one, else a known product name. */
export function serverLabel(server?: string | null, displayName?: string | null): string {
  if (displayName && displayName.trim() && displayName !== server) return displayName
  if (!server) return 'Unknown server'
  return SERVERS[server.toLowerCase()] ?? sentenceCase(server)
}

/**
 * 'gmail.send_message' reads 'Gmail · send message'. The model is shown a tool as server__tool,
 * and that form turns up in a trace, so it reads the same. The internal ask tool reads on its own.
 */
export function toolLabel(qualified?: string | null): string {
  if (!qualified || !qualified.trim()) return 'Unknown tool'
  if (qualified === 'person.ask_question' || qualified === 'person__ask_question') return 'Asked a question'
  if (qualified === 'memory.remember' || qualified === 'memory__remember') return 'Remembered something'
  if (qualified === 'memory.recall' || qualified === 'memory__recall') return 'Recalled memory'
  const dot = qualified.indexOf('.')
  const wire = qualified.indexOf('__')
  // The dotted form wins when both appear: a tool name may itself contain two underscores.
  const at = dot > 0 ? dot : wire
  const width = dot > 0 ? 1 : 2
  if (at <= 0 || at + width >= qualified.length) return sentenceCase(qualified)
  const tool = sentenceCase(qualified.slice(at + width)).toLowerCase()
  return `${serverLabel(qualified.slice(0, at))} · ${tool}`
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
