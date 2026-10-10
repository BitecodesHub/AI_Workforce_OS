// @find: connectors, integrations, capabilities, tool groups, asks first, grant tools, calls per run limit, connector state, connected, sandbox, OAuth return, docs url, web address check, agents using connector, Connectors page, grant dialog
// @what: Plain-word rules for connectors: tool groups, approval-first tools, grant selection, connector state, OAuth return handling and field problems.
// @flow: Shared by the Connectors page and the agent grant dialog; typed structurally against connector query types
import { formatCount, sentenceCase } from './format'

/*
 * What a connector can do, in the words a person reads, and the rules the Connectors page and an
 * agent's grant dialog share.
 *
 * A tool's side-effect class decides its group. The platform (mcp-core ToolDefinition) makes every
 * OUTBOUND and DESTRUCTIVE tool wait for a person whatever a grant says, so those two groups carry
 * "asks first" in their names; any other tool flagged alwaysRequiresApproval asks first as well,
 * and the screens mark it one by one.
 *
 * Pure, and typed structurally so it accepts the query types without importing them.
 */

export type SideEffect = 'READ' | 'WRITE' | 'OUTBOUND' | 'DESTRUCTIVE'

export type CapabilityTool = {
  name: string
  description?: string | null
  sideEffect: SideEffect | string
  alwaysRequiresApproval?: boolean | null
}

export type CapabilityGroupKey = 'reads' | 'edits' | 'sends' | 'deletes'

export type CapabilityGroupInfo = {
  key: CapabilityGroupKey
  /** The heading over the group: "Reads", "Sends — asks first". */
  label: string
  /** True when every tool in the group waits for a person before it acts. */
  asksFirst: boolean
}

/** In the order they appear on a card and in the dialog: the safest first. */
export const CAPABILITY_GROUPS: readonly CapabilityGroupInfo[] = [
  { key: 'reads', label: 'Reads', asksFirst: false },
  { key: 'edits', label: 'Creates and edits', asksFirst: false },
  { key: 'sends', label: 'Sends — asks first', asksFirst: true },
  { key: 'deletes', label: 'Deletes — asks first', asksFirst: true },
]

const GROUP_OF: Record<string, CapabilityGroupKey> = {
  READ: 'reads',
  WRITE: 'edits',
  OUTBOUND: 'sends',
  DESTRUCTIVE: 'deletes',
}

// @find: capability group, reads edits sends deletes, side effect class
/**
 * The group a tool belongs to. An unknown class reads as "Creates and edits" rather than "Reads":
 * a tool whose effect is not known is never presented as harmless.
 */
export function capabilityGroup(tool: Pick<CapabilityTool, 'sideEffect'>): CapabilityGroupKey {
  return GROUP_OF[String(tool.sideEffect).toUpperCase()] ?? 'edits'
}

// @find: asks first, requires approval, outbound destructive tools
/**
 * Whether a person is asked before this tool acts: always for sending and deleting, for a tool
 * flagged to, and for everything when the grant asks for every call.
 */
export function asksFirst(tool: CapabilityTool, grantRequiresApproval = false): boolean {
  if (grantRequiresApproval || tool.alwaysRequiresApproval) return true
  const group = capabilityGroup(tool)
  return group === 'sends' || group === 'deletes'
}

export type CapabilityGroup<T> = CapabilityGroupInfo & { tools: T[] }

// @find: group tools, connector capabilities list
/** The tools in their groups, in CAPABILITY_GROUPS order, leaving out groups with no tools. */
export function groupCapabilities<T extends CapabilityTool>(tools: readonly T[]): CapabilityGroup<T>[] {
  return CAPABILITY_GROUPS.map((group) => ({
    ...group,
    tools: tools.filter((tool) => capabilityGroup(tool) === group.key),
  })).filter((group) => group.tools.length > 0)
}

/** A tool's name in plain words: 'list_issues' reads 'List issues'. */
export function capabilityLabel(tool: Pick<CapabilityTool, 'name'>): string {
  return sentenceCase(tool.name) || tool.name
}

/* ---- Grants --------------------------------------------------------------------------------- */

// @find: default tool selection, grant dialog defaults
/**
 * What a new grant starts with: everything that only reads. Creating, sending and deleting are
 * choices somebody makes on purpose, so they start unticked.
 */
export function defaultSelection(tools: readonly CapabilityTool[]): string[] {
  return tools.filter((tool) => capabilityGroup(tool) === 'reads').map((tool) => tool.name)
}

/**
 * The tools an existing grant allows, among those the connector offers. An empty list on a grant
 * means every tool on the server (AgentGrant.tools).
 */
export function grantedToolNames(grant: { tools: readonly string[] }, tools: readonly CapabilityTool[]): string[] {
  if (grant.tools.length === 0) return tools.map((tool) => tool.name)
  const allowed = new Set(grant.tools)
  return tools.filter((tool) => allowed.has(tool.name)).map((tool) => tool.name)
}

/**
 * Where the grant dialog starts: an existing grant's own tools, or the read-only default for a
 * connector the agent does not have yet.
 */
export function initialSelection(
  grant: { tools: readonly string[] } | null | undefined,
  tools: readonly CapabilityTool[],
): string[] {
  return grant ? grantedToolNames(grant, tools) : defaultSelection(tools)
}

// @find: grant tools, give agent access to connector tools
/**
 * The tool list to save, in the connector's own order. Always explicit, never the empty "every
 * tool" list: a tool the server adds later must be granted by a person, not inherited silently.
 */
export function grantTools(selected: readonly string[], tools: readonly CapabilityTool[]): string[] {
  const chosen = new Set(selected)
  return tools.filter((tool) => chosen.has(tool.name)).map((tool) => tool.name)
}

export const MIN_CALLS_PER_RUN = 1
export const MAX_CALLS_PER_RUN = 200

// @find: calls per run, call limit, max tool calls
/** The call limit as typed: null for empty (no limit), a number, or false when it is not a valid limit. */
export function parseCallLimit(value: string): number | null | false {
  const trimmed = value.trim()
  if (trimmed === '') return null
  if (!/^\d+$/.test(trimmed)) return false
  const limit = Number(trimmed)
  return limit >= MIN_CALLS_PER_RUN && limit <= MAX_CALLS_PER_RUN ? limit : false
}

/* ---- Connection state ----------------------------------------------------------------------- */

export type ConnectorState = 'connected' | 'sandbox' | 'attention' | 'builtin'

export type ConnectorLike = {
  server: string
  status: string
  sandbox: boolean
  reconnectRequired?: boolean | null
  lastError?: string | null
  category?: string | null
  authType?: string | null
}

const ATTENTION_STATUSES = new Set(['error', 'reconnect_required', 'revoked'])

// @find: connector state, connected, sandbox, needs attention, built in
/**
 * One of the three states the product promises: Sandbox (practice data, nothing leaves), Connected
 * (a live token is stored and passed its last check) or Needs attention (a stored token failed its
 * last check, or the service asked for it to be connected again). A connector built into the
 * platform (Voice notes) has no account at all, so it is Built in rather than practice data.
 */
export function connectorState(connector: ConnectorLike): ConnectorState {
  if (connector.authType === 'none') return 'builtin'
  if (connector.reconnectRequired) return 'attention'
  if (connector.sandbox) return 'sandbox'
  if (connector.lastError || ATTENTION_STATUSES.has(connector.status.toLowerCase())) return 'attention'
  return 'connected'
}

export type ConnectorSummary = {
  /** Connected to a live account and passing its checks. */
  live: number
  /** On practice data in the sandbox. */
  practice: number
  /** A live connection that failed its last check or must be connected again. */
  attention: number
  /** The short words for the toolbar pill: "1 live · 19 practice". */
  short: string
  /** The whole sentence behind the pill, on where the connectors stand together. */
  sentence: string
}

// @find: connector summary, connector card text
/**
 * Where the connectors stand together, once per page: a few words for the toolbar and the full
 * sentence about practice data behind them. Nothing done on practice data leaves the workspace.
 */
export function connectorSummary(
  connectors: ReadonlyArray<ConnectorLike & { liveAvailable?: boolean | null }>,
): ConnectorSummary {
  const states = connectors.map(connectorState)
  const live = states.filter((state) => state === 'connected').length
  const attention = states.filter((state) => state === 'attention').length
  const practice = states.filter((state) => state === 'sandbox').length
  const liveCapable = connectors.filter((connector) => connector.liveAvailable === true).length

  if (live + attention === 0) {
    return {
      live,
      practice,
      attention,
      short: 'All on practice data',
      sentence:
        'Every connector runs on practice data in a sandbox, so nothing an agent does through them leaves this workspace. ' +
        (liveCapable > 0
          ? `${formatCount(liveCapable)} of them can be connected to a live account with a token or by signing in.`
          : 'Live connections are not available in this version.'),
    }
  }
  const parts = [`${formatCount(live)} connected to a live account`]
  if (attention > 0) parts.push(`${formatCount(attention)} ${attention === 1 ? 'needs' : 'need'} attention`)
  return {
    live,
    practice,
    attention,
    short: `${formatCount(live)} live · ${formatCount(practice)} practice`,
    sentence: (
      `${parts.join(', ')}. ` +
      (practice > 0
        ? `The other ${formatCount(practice)} run on practice data in a sandbox, so nothing an agent does through them leaves this workspace.`
        : '')
    ).trim(),
  }
}

/**
 * Categories for the servers that existed before the catalog carried one, so the category filter
 * still works against an older integrations service.
 */
const KNOWN_CATEGORIES: Record<string, string> = {
  gmail: 'communication',
  slack: 'communication',
  outlook: 'communication',
  teams: 'communication',
  zoom: 'communication',
  calendar: 'productivity',
  notion: 'productivity',
  asana: 'productivity',
  confluence: 'productivity',
  github: 'engineering',
  jira: 'engineering',
  linear: 'engineering',
  hubspot: 'sales',
  salesforce: 'sales',
  zendesk: 'support',
  drive: 'files',
  sheets: 'files',
  stripe: 'finance',
  webhook: 'automation',
}

/** The connector's category: the catalog's, else a known one for the server, else 'other'. */
export function connectorCategory(connector: Pick<ConnectorLike, 'server' | 'category'>): string {
  const own = connector.category?.trim().toLowerCase()
  if (own) return own
  return KNOWN_CATEGORIES[connector.server.toLowerCase()] ?? 'other'
}

// @find: agents using a connector, who uses this connector
/** The agents granted this server, by name, from the agents list (Agent.tools holds server names). */
export function agentsUsing<A extends { tools?: readonly string[] | null; name: string }>(
  server: string,
  agents: readonly A[] | undefined,
): A[] {
  return (agents ?? [])
    .filter((agent) => (agent.tools ?? []).includes(server))
    .sort((a, b) => a.name.localeCompare(b.name))
}

/* ---- Signing in through a provider ----------------------------------------------------------- */

export type OAuthReturn = { connected: string | null; error: string | null }

/** The longest provider message shown; anything beyond it is cut so a crafted link cannot fill the page. */
const MAX_RETURN_ERROR = 300

// @find: oauth return, sign in with provider callback, connected or error
/**
 * What the provider's redirect left in the address: ?connected=<server> after a success, or
 * ?error=<message> after a failure. Both are plain text, never markup. A blank value counts as absent.
 */
export function parseOAuthReturn(search: URLSearchParams): OAuthReturn {
  const connected = search.get('connected')?.trim() || null
  const error = search.get('error')?.trim().slice(0, MAX_RETURN_ERROR) || null
  return { connected, error }
}

/** The query string without the redirect's parameters, keeping any others ('' when none remain). */
export function withoutOAuthReturn(search: URLSearchParams): string {
  const rest = new URLSearchParams(search)
  rest.delete('connected')
  rest.delete('error')
  const text = rest.toString()
  return text ? `?${text}` : ''
}

/** True only for an https: address: the one kind the browser is sent to for a provider's sign-in. */
export function isHttpsUrl(value: string | null | undefined): value is string {
  if (!value) return false
  try {
    return new URL(value).protocol === 'https:'
  } catch {
    return false
  }
}

/** Whether a connector signs in through a provider rather than taking a pasted secret. */
export function usesSignIn(connector: { authType?: string | null; oauth?: unknown }): boolean {
  return connector.authType === 'oauth' && Boolean(connector.oauth)
}

/** What a connector's secret is called in copy: a webhook stores an address, the rest a token. */
export function secretNoun(connector: { authType?: string | null }): 'address' | 'token' {
  return connector.authType === 'url' ? 'address' : 'token'
}

/** Only a web address can be offered as a link: a catalog value is never trusted to be one. */
export function safeDocsUrl(url?: string | null): string | null {
  if (!url) return null
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.toString() : null
  } catch {
    return null
  }
}

/* ---- Problems with what was typed ------------------------------------------------------------ */

/**
 * Why a webhook address cannot be used, before it is sent, or null when it may be. Mirrors the
 * first rules of the service's own check (WebhookAdapter.problem): a full http or https address
 * with a host. Whether plain http or a private address is allowed is left to the service.
 */
export function webAddressProblem(value: string): string | null {
  const trimmed = value.trim()
  if (!trimmed) return 'Enter the web address that should receive events.'
  let url: URL
  try {
    url = new URL(trimmed)
  } catch {
    return 'That is not a valid web address. Enter a full address that starts with https://.'
  }
  if ((url.protocol !== 'https:' && url.protocol !== 'http:') || !url.hostname) {
    return 'Enter a full web address that starts with https://.'
  }
  return null
}

/** The parts of a failed request that decide where its message is shown (ApiError, structurally). */
export type FieldFailure = { code: string; message: string; fields: Readonly<Record<string, string>> }

export type PlacedProblems = {
  /** A sentence per form field, keyed by the field's own key, to show under that field. */
  inline: Record<string, string>
  /** True when part of the failure belongs to no field on the form and is shown for the whole form. */
  unplaced: boolean
}

const withStop = (text: string) => (/[.!?]$/.test(text) ? text : `${text}.`)

// @find: field problems, connector form errors, validation messages
/**
 * Places a refused save next to the fields it is about.
 *
 * A validation failure names its fields (`aliases` maps a name the service uses, such as `token`
 * or `settings`, onto a field of the form). A failed check names no field; with one field on the
 * form it can only be about that one. Anything else is left for the form as a whole.
 */
export function placeFieldProblems(
  failure: FieldFailure,
  fields: ReadonlyArray<{ key: string; label: string }>,
  aliases: Readonly<Record<string, string>> = {},
): PlacedProblems {
  const inline: Record<string, string> = {}
  let unplaced = false
  if (failure.code === 'validation_failed' && Object.keys(failure.fields).length > 0) {
    for (const [name, problem] of Object.entries(failure.fields)) {
      const field = fields.find((item) => item.key === name) ?? fields.find((item) => item.key === aliases[name])
      if (!field) {
        unplaced = true
        continue
      }
      const text = problem.trim()
      // "must not be empty" reads after the label; a whole sentence from the service stands alone.
      inline[field.key] = /^[A-Z]/.test(text) ? withStop(text) : withStop(`${field.label} ${text}`)
    }
  } else if (failure.code === 'connector_check_failed' && fields.length === 1 && failure.message.trim()) {
    inline[fields[0]!.key] = withStop(failure.message.trim())
  } else {
    unplaced = true
  }
  return { inline, unplaced }
}
