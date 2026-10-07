import { useMemo } from 'react'
import {
  keepPreviousData,
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type InfiniteData,
  type QueryClient,
} from '@tanstack/react-query'
import { ApiError, api } from './api'
import type { ProviderCatalogue } from './modelCatalogue'

/*
 * Every piece of platform data the interface reads, typed to match what the services return.
 *
 * Queries are keyed so that an action refreshes exactly what it changed: approving something
 * refreshes the approvals queue, the runs list and the badge in the navigation together, and a
 * screen never shows a count that disagrees with the list beneath it.
 *
 * The services serialise with non_null inclusion, so a null field is left out of the JSON rather
 * than sent as null. Two conventions follow from that:
 *
 * - A field typed `?: T | null` may be missing. Test it with `== null` or `!= null`, never `=== null`.
 * - A field typed `T | null` (no question mark) is always present: the hooks that return it put
 *   the null back (see withNulls), because current screens compare these with `=== null` or keep
 *   structural copies of these types that expect the field to be there.
 */

export type Agent = {
  id: string
  key: string
  name: string
  category: string
  status: string
  /** Null only for an agent with no saved configuration revision. */
  revision: number | null
  /**
   * The first sentence of the current revision's system prompt, at most 160 characters. It is an
   * excerpt of the agent's instructions (usually second person, "You handle..."), not a
   * description written about the agent, so present it as such.
   */
  summary?: string | null
  /** The distinct tool servers this agent is granted, by server name (show them with serverLabel). */
  tools?: string[] | null
  /** The ElevenLabs voice id this agent speaks with. Null (or absent) reads as the browser voice. */
  voiceId?: string | null
  /** True for the General Employee: the org-wide fallback that exists whatever a person asks. */
  fallback?: boolean
}

export type AgentGrant = {
  server: string
  tools: string[]
  scopes: string[]
  requireApproval: boolean
  maxCallsPerRun?: number | null
}

export type AgentDetail = Agent & {
  /** The next three are null only when the agent has no saved revision. */
  systemPrompt: string | null
  goals: string | null
  maxSteps: number | null
  sealed: boolean
  grants: AgentGrant[]
}

export type Run = {
  id: string
  agentId: string
  taskId?: string | null
  status: string
  trigger: string
  stepCount: number
  promptTokens: number
  completionTokens: number
  /** RunView.cost: the run's total cost in US dollars, from the model catalogue prices. */
  cost: number
  startedAt: string
  completedAt?: string | null
  failureReason?: string | null
  /** The goal this run's task belongs to, when it has one. */
  goalId?: string | null
  /** Who asked for this: the goal's requester, carried onto every run of its tasks. */
  requestedBy?: string | null
}

/** What starting a run returns (AgentController.RunStarted). The run's id is `runId`. */
export type RunStarted = {
  runId: string
  status: string
  answer?: string | null
}

export type RunStep = {
  id: string
  position: number
  kind: string
  detail: Record<string, unknown>
  provider?: string | null
  model?: string | null
  promptTokens: number
  completionTokens: number
  durationMs: number
  occurredAt: string
}

export type Approval = {
  id: string
  runId: string
  agentId: string
  tool: string | null
  actionClass: string
  summary: string
  /** The tool call's arguments, exactly as the model produced them, as a JSON string. */
  payload: string
  status: string
  requestedAt: string
  expiresAt: string
}

/** What deciding an approval returns. `runStatus` is where the run stands after the decision. */
export type DecisionResult = {
  approvalId: string
  status: string
  runStatus?: string | null
}

export type Task = {
  id: string
  /** Null when the agent the task was assigned to has since been deleted. */
  agentId: string | null
  title: string
  status: string
  /** Where the task sits in its goal's chain, from 0. Also the fallback run order when dependsOn is empty. */
  position: number
  /** The task ids this one waits on. Empty when it simply follows the previous position. */
  dependsOn: string[]
  attempt: number
  maxAttempts: number
  result: string | null
  failureReason: string | null
  startedAt: string | null
  completedAt: string | null
  /** The most recent run against this task. Null for a task that has not run yet. */
  runId: string | null
}

/** How a goal came to exist: typed in directly, routed from a chat conversation, or a schedule firing. */
export type GoalSource = 'manual' | 'chat' | 'schedule'

export type Goal = {
  id: string
  title: string
  description: string
  status: string
  createdAt: string
  completedAt: string | null
  source: GoalSource
  /** Who asked for this goal: the person in Tasks, the chat sender, or the schedule's owner. */
  requestedBy: string | null
  /** The conversation this goal answers, for a chat-sourced goal. */
  conversationId: string | null
  /** The schedule that created this goal, for a schedule-sourced goal. */
  scheduleId: string | null
  tasks: Task[]
}

export type Provider = {
  id: string
  displayName: string
  kind: string
  /** On for this workspace: offered by the installation and switched on here. */
  enabled: boolean
  /** Whether this installation offers the provider to workspaces at all. */
  platformEnabled?: boolean
  /** What to pass to PUT /api/credentials/{ref}. Absent for the sandbox, which needs no key. */
  credentialRef?: string | null
  credentialStatus: string
  credentialCheckedAt?: string | null
  circuitState: string
  regions: string[]
  modelCount: number
}

export type Model = {
  providerId: string
  modelId: string
  displayName: string
  contextWindow: number
  maxOutputTokens: number
  supportsTools: boolean
  supportsJsonMode: boolean
  supportsStreaming: boolean
  inputCostPerMillion: number
  outputCostPerMillion: number
  enabled: boolean
  /** Set while the router has taken this model out of rotation, with the reason. */
  unavailableUntil?: string | null
  unavailableReason?: string | null
}

export type ModelPolicyCandidate = {
  position: number
  providerId: string
  modelId: string
  temperature?: number | null
  maxOutputTokens?: number | null
}

export type ModelPolicy = {
  /** False when nothing has been set and the router falls through to the workspace default, or the built-in sandbox. */
  configured: boolean
  exhaustedBehaviour: string
  maxAttemptsPerCandidate: number
  overallDeadlineSeconds: number
  compactOnOverflow: boolean
  candidates: ModelPolicyCandidate[]
}

/** A field left out keeps its stored value; the candidate list is always replaced. */
export type ModelPolicyInput = {
  candidates: Array<{
    providerId: string
    modelId: string
    temperature?: number | null
    maxOutputTokens?: number | null
  }>
  exhaustedBehaviour?: 'FAIL_CLOSED' | 'DEGRADE_TO_SANDBOX'
  maxAttemptsPerCandidate?: number
  overallDeadlineSeconds?: number
  compactOnOverflow?: boolean
}

/** A stored credential, described without its value (CredentialService.CredentialView). */
export type CredentialView = {
  ref: string
  kind: string
  fingerprint?: string | null
  present: boolean
  expiresAt?: string | null
  lastUsedAt?: string | null
}

export type Source = {
  id: string
  name: string
  kind: string
  status: string
  documentCount: number
  chunkCount: number
  embeddingProvider: string
  embeddingModel: string
  embeddingDimension: number
  lastIngestedAt: string | null
  lastError: string | null
}

export type SourceDocument = {
  id: string
  title: string
  mediaType: string
  status: string
  skipReason: string | null
  chunkCount: number
  indexedAt: string | null
  removedAtSource: boolean
}

export type Passage = {
  chunkId: string
  documentId: string
  documentTitle: string
  uri: string | null
  pageNumber: number | null
  heading: string | null
  content: string
  score: number
}

/* ---- Clarifying questions ------------------------------------------------------------------- */

export type QuestionStatus = 'pending' | 'answered' | 'expired' | 'cancelled'
export type AnsweredVia = 'chat' | 'orchestrator' | 'run' | 'approvals'

export type QuestionOption = { label: string; description: string; recommended?: boolean }
export type QuestionItem = { id: string; header: string; question: string; multiSelect: boolean; options: QuestionOption[] }
export type QuestionAnswerItem = { questionId: string; selected: string[]; other?: string | null }
export type QuestionAnswer = { answers: QuestionAnswerItem[]; note?: string | null; skipped: boolean }

export type RunQuestion = {
  id: string
  runId: string
  taskId: string | null
  goalId: string | null
  conversationId: string | null
  agentId: string
  goalTitle: string | null
  status: QuestionStatus
  questions: QuestionItem[]
  answer: QuestionAnswer | null
  answeredBy: string | null
  answeredVia: AnsweredVia | null
  answeredAt: string | null
  requestedBy: string | null
  createdAt: string
  expiresAt: string
  closedReason: string | null
  canAnswer: boolean
  extendable: boolean
  runStatus: string
}

export type AnswerQuestionInput = {
  id: string
  answers: QuestionAnswerItem[]
  note?: string | null
  skipped?: boolean
  via: AnsweredVia
}
export type AnswerQuestionResult = { question: RunQuestion; runStatus: string }

/* ---- Chat --------------------------------------------------------------------------------------- */

export type ChatAuthorKind = 'user' | 'coordinator' | 'agent' | 'system'
export type ChatMessageKind =
  | 'text'
  | 'routing'
  | 'documents'
  | 'progress'
  | 'answer'
  | 'schedule_suggestion'
  | 'error'
  | 'question'
  | 'notice'

export type RoutingAgent = { id: string; name: string; instruction: string }
export type RoutingAlternative = { id: string; name: string; score: number }

/**
 * Every field any message kind's `detail` can carry. Which ones are set follows from `kind` (see
 * the EXACT API CONTRACTS list this was built from): a `routing` message has `mode`/`agents`/
 * `reason`/`matched`/`alternatives`/`needsChoice`; `documents` has `query`/`grounded`/`passages`;
 * `progress` has `goalId`; `answer` has `taskId`/`runId`/`agentId`/`code`/`sandbox`; `schedule_suggestion` has
 * `text`/`kind`/`cron`/`runAt`/`description`/`timezone`/`nextRuns`/`agentId`/`agentName`/
 * `instruction`/`name`; `error` has `reason`/`code`; `question` has `questionId`/`count`/`headers`/`expiresAt`;
 * `notice` has `event`/`goalId`/`fromTaskId`. Read only the fields your message kind defines.
 */
export type ChatMessageDetail = {
  mode?: 'mention' | 'model' | 'rules' | 'manual' | 'fallback'
  agents?: RoutingAgent[]
  reason?: string
  matched?: string[]
  alternatives?: RoutingAlternative[]
  needsChoice?: boolean
  query?: string
  grounded?: boolean
  passages?: Passage[]
  goalId?: string
  taskId?: string
  runId?: string
  agentId?: string
  text?: string
  kind?: 'recurring' | 'once'
  cron?: string | null
  runAt?: string | null
  description?: string
  timezone?: string
  nextRuns?: string[]
  agentName?: string
  instruction?: string
  name?: string
  requestText?: string
  rerouteOf?: string
  /** On an error message: the partial answer a run left behind when it hit its step or output limit. */
  incompleteAnswer?: string
  fromDocumentsMessageId?: string
  questionId?: string
  count?: number
  headers?: string[]
  expiresAt?: string
  event?: 'cancelled' | 'retried'
  fromTaskId?: string
  code?: string | null
  sandbox?: boolean
}

export type ChatMessage = {
  id: string
  position: number
  authorKind: ChatAuthorKind
  authorId: string | null
  agentId: string | null
  kind: ChatMessageKind
  content: string
  detail: ChatMessageDetail
  goalId: string | null
  createdAt: string
}

export type ConversationActivity =
  | 'needs_answer'
  | 'waiting_answer'
  | 'needs_approval'
  | 'waiting_approval'
  | 'working'
  | 'idle'

export type SearchMatch = { messageId: string; snippet: string }

export type Conversation = {
  id: string
  title: string
  createdBy: string | null
  createdAt: string
  updatedAt: string
  lastMessagePreview: string
  messageCount: number
  pinned: boolean
  archived: boolean
  activity: ConversationActivity
  canManage: boolean
  unread: boolean
  match: SearchMatch | null
  /** Who may read it: only its creator and the people added, or everyone with Chat access. */
  visibility?: 'private' | 'workspace'
  /** Whether this person may change who can read it. */
  canShare?: boolean
}

export type ConversationPage = {
  pinned: Conversation[]
  needsYou: Conversation[]
  conversations: Conversation[]
  hasMore: boolean
}

export type ConversationScope = 'all' | 'mine' | 'archived'

export type MessagesPage = { messages: ChatMessage[]; hasEarlier: boolean }

export type GoalActionResult = { goalId: string; status: string; fromTaskId?: string | null }

/**
 * A conversation's thread, its board-style goals (see BoardGoal below, which is what the board
 * shows and what the detail endpoint now returns too) and the questions asked in it.
 */
export type ConversationDetail = {
  conversation: Conversation
  messages: ChatMessage[]
  goals: BoardGoal[]
  questions: RunQuestion[]
  hasEarlier: boolean
  /**
   * The server's clock when this was read: what to ask "what changed since" about next time. Left
   * out by a server that predates incremental reads, which then sends the whole window each time.
   */
  generatedAt?: string
}

/* ---- Orchestrator board --------------------------------------------------------------------------- */

export type BoardStats = {
  running: number
  waitingApproval: number
  waitingInput: number
  queued: number
  held: number
  completedToday: number
  failedToday: number
  spendToday: number
  goalsCompletedToday: number
  goalsFailedToday: number
  directRuns: number
}

export type BoardAgent = {
  id: string
  name: string
  category: string
  status: string
  fallback: boolean
  runningRunIds: string[]
  waitingRunIds: string[]
  askingRunIds: string[]
  queued: number
}

/** A goal's task, as the board shows it: everything Task has, plus its latest run's own facts. */
export type BoardTask = Task & {
  /** The latest run's status, and (stepCount) its steps: the attempt a person is looking at. */
  runStatus: string | null
  stepCount: number | null
  /** What every run of the task cost, not only the latest, so a retried task shows its whole spend. */
  cost: number | null
  /** How many runs the task has had. Left out by a server that predates it. */
  attempts?: number
}

export type BoardGoal = Omit<Goal, 'tasks'> & { tasks: BoardTask[] }

export type QueueReason = 'ready' | 'waiting_on_earlier_task' | 'agent_paused'

export type BoardQueueEntry = {
  goalId: string
  goalTitle: string
  taskId: string
  agentId: string | null
  position: number
  reason: QueueReason
  requestedBy: string | null
  source: GoalSource
  createdAt: string
}

export type BoardTimelineEntry = {
  runId: string
  agentId: string
  goalId: string | null
  status: string
  startedAt: string
  completedAt: string | null
}

/** requestedBy: the goal's requester, or a direct run's starter. canDecide: the caller holds the approval's requiredPermission. */
export type BoardApproval = {
  id: string
  runId: string
  taskId: string | null
  goalId: string | null
  agentId: string
  tool: string | null
  actionClass: string
  summary: string
  requestedAt: string
  expiresAt: string
  requestedBy: string | null
  canDecide: boolean
}

export type BoardWindow = 'PT1H' | 'PT2H' | 'PT6H' | 'PT24H' | 'TODAY'

export type Board = {
  generatedAt: string
  timezone: string
  window: BoardWindow
  windowMinutes: number
  stats: BoardStats
  agents: BoardAgent[]
  goals: BoardGoal[]
  queue: BoardQueueEntry[]
  timeline: BoardTimelineEntry[]
  questions: RunQuestion[]
  approvals: BoardApproval[]
  /** Goals that failed since local midnight, newest first, at most 50, whatever the window. */
  failedToday: BoardGoal[]
}

export type StopAllResult = {
  runsCancelled: number
  tasksCancelled: number
  approvalsWithdrawn: number
  questionsWithdrawn: number
  schedulesPaused: number
  goalsSkipped: number
  runsSkipped: number
}

export type AgentStatusAction = 'pause' | 'resume'

/* ---- Schedules ------------------------------------------------------------------------------------ */

export type ScheduleKind = 'recurring' | 'once'
export type OverlapPolicy = 'skip' | 'queue'

export type Schedule = {
  id: string
  name: string
  agentId: string
  agentName: string
  instruction: string
  kind: ScheduleKind
  cron: string | null
  runAt: string | null
  timezone: string
  description: string
  enabled: boolean
  overlapPolicy: OverlapPolicy
  nextRunAt: string | null
  lastRunAt: string | null
  lastStatus: string | null
  /** The goal the schedule most recently fired, for a "see it on the board" link. */
  lastGoalId: string | null
  consecutiveFailures: number
  pausedReason: string | null
  createdBy: string | null
  createdAt: string
  /** 'done' once a one-off has fired and has nothing left to run. */
  state?: 'active' | 'paused' | 'done'
  /** True once a one-off has fired. */
  completed?: boolean
}

/** What POST /api/schedules/preview returns for a readable phrase: an echo, not a saved schedule. */
export type SchedulePreview = {
  kind: ScheduleKind
  cron: string | null
  runAt: string | null
  description: string
  timezone: string
  /** Up to five ISO instants: the schedule's next runs, in its own timezone. */
  nextRuns: string[]
}

/* ---- Voice ------------------------------------------------------------------------------------------ */

export type VoiceStatus = {
  provider: 'elevenlabs' | 'browser'
  keyStored: boolean
  tier?: string | null
  charactersUsed?: number | null
  characterLimit?: number | null
}

export type Voice = {
  voiceId: string
  name: string
  category: string
  description?: string | null
  previewUrl?: string | null
}

export type IngestResult = {
  documentId: string
  status: 'indexed' | 'skipped' | 'unchanged' | 'failed'
  chunkCount: number
  detail?: string | null
  searchable: boolean
}

/** What a reindex returns. `vectorised` false means meaning-based search is still missing; `detail` says why. */
export type ReindexResult = {
  sourceId: string
  status: string
  documentsQueued: number
  vectorised: boolean
  detail?: string | null
}

export type Tool = {
  name: string
  qualifiedName: string
  description: string
  sideEffect: 'READ' | 'WRITE' | 'OUTBOUND' | 'DESTRUCTIVE'
  requiredScopes: string[]
  alwaysRequiresApproval: boolean
}

/** How a connector is connected (mcp-core ConnectorCatalog). Only 'token' and 'url' can be pasted in today. */
export type ConnectorAuthType = 'token' | 'oauth' | 'url' | 'none'

export type Integration = {
  server: string
  displayName: string
  status: string
  sandbox: boolean
  reconnectRequired: boolean
  accountLabel?: string | null
  grantedScopes: string[]
  missingScopes: string[]
  connectedAt?: string | null
  tokenExpiresAt?: string | null
  tools: Tool[]
  /*
   * The catalog fields below arrived with the connectors catalog (3 Oct 2026). All optional, so
   * the Connectors page still renders against an integrations service that predates them.
   */
  /** One of communication, productivity, engineering, sales, support, files, finance, automation, voice. */
  category?: string | null
  /** One plain sentence saying what the service is for. */
  description?: string | null
  authType?: ConnectorAuthType | string | null
  /** False (or absent) when only the sandbox exists for this connector: no Connect is offered. */
  liveAvailable?: boolean | null
  /** What the pasted secret is called, such as "Personal access token". */
  tokenLabel?: string | null
  /** Plain sentences telling an admin where to get the token, in order. */
  setupSteps?: string[] | null
  docsUrl?: string | null
  /** Why the stored token failed its last check, in words; absent when it passed. */
  lastError?: string | null
  lastCheckedAt?: string | null
  /** One entry per box in the connect form; empty means the single secret box labelled by tokenLabel. */
  credentialFields?: CredentialField[] | null
  /** How to sign in with the provider, for an 'oauth' connector; null otherwise. */
  oauth?: OAuthSetup | null
  /** True once an app has been saved for this workspace and provider. */
  oauthAppConfigured?: boolean | null
}

/** One box in a connect or app-settings form. */
export type CredentialField = {
  key: string
  label: string
  secret: boolean
  placeholder?: string | null
  help?: string | null
}

/** What a connector needs to sign in through a provider (Google, Microsoft, Salesforce). */
export type OAuthSetup = {
  provider: 'google' | 'microsoft' | 'salesforce' | string
  providerLabel: string
  /** Extra app settings beyond the client ID and secret, such as a Microsoft tenant. */
  appFields: CredentialField[]
  /** The permissions the connector asks the provider for, as the provider names them. */
  scopes: string[]
  /** Plain steps for registering the app at the provider, in order. */
  appSteps: string[]
  appDocsUrl?: string | null
  /** The exact address to register at the provider. */
  redirectUri: string
}

/** The saved app for a provider; the client secret itself is never returned. */
export type OAuthAppView = {
  configured: boolean
  provider: string
  clientId: string | null
  clientSecretStored: boolean
  settings: Record<string, string>
  redirectUri: string
}

/** What POST /api/integrations/{server}/test returns. */
export type ConnectionTestResult = {
  ok: boolean
  message: string
  checkedAt?: string | null
}

/** The body of PUT /api/agents/{id}/grants/{server}. An empty `tools` means every tool on the server. */
export type GrantInput = {
  tools: string[]
  requireApproval: boolean
  maxCallsPerRun: number | null
}

export type Member = {
  userId: string
  displayName: string
  email: string
  role: string
  status: string
  joinedAt?: string | null
  lastSignInAt?: string | null
}

export type Role = {
  id: string
  name: string
  description: string
  system: boolean
  permissionVersion: number
  permissions: string[]
  holders: number
}

export type PermissionInfo = {
  code: string
  resource: string
  action: string
  description: string
  administrative: boolean
}

export type Invitation = {
  invitationId: string
  email: string
  roleName: string
  status: string
  expiresAt: string
  acceptedAt?: string | null
  /** Present only on the response right after creation; never returned by the list. */
  token?: string | null
  acceptUrl?: string | null
}

/** For hooks a role may not be allowed to call: pass `enabled: can('<code>')` to skip the request. */
export type QueryOptions = { enabled?: boolean }

/* ---- Freshness --------------------------------------------------------------------------------- */

/*
 * Lists poll only while something in them can still change on its own. Polling pauses while the
 * tab is hidden (refetchIntervalInBackground stays false) and resumes on focus.
 */

const ACTIVE_RUN_STATUSES = new Set(['running', 'waiting_approval', 'waiting_input'])
const ACTIVE_GOAL_STATUSES = new Set(['planning', 'running', 'waiting'])
/** A run parked for a person: an approval or a question, neither of which the agent can move past alone. */
const PARKED_STATUSES = new Set(['waiting_approval', 'waiting_input'])

/** Whether a run can still change: running, waiting for approval, or waiting for an answer. */
export function isRunActive(run?: { status: string } | null): boolean {
  return run != null && ACTIVE_RUN_STATUSES.has(run.status.toLowerCase())
}

/** Whether a goal can still change: planning, running or waiting. */
export function isGoalActive(goal?: { status: string } | null): boolean {
  return goal != null && ACTIVE_GOAL_STATUSES.has(goal.status.toLowerCase())
}

/** Whether a run is parked waiting on a person, rather than doing work itself. */
export function isRunParked(run?: { status: string } | null): boolean {
  return run != null && PARKED_STATUSES.has(run.status.toLowerCase())
}

/** Rows per page for the paged lists. A full last page means there may be more. */
export const RUN_PAGE_SIZE = 50
export const GOAL_PAGE_SIZE = 50
export const AUDIT_PAGE_SIZE = 100
export const CONVERSATION_PAGE_SIZE = 50

/*
 * Paged lists are offset-based, so a row created while someone reads can push an older row onto
 * the next page, where it would appear twice. Each page is filtered of rows an earlier page
 * already holds. Declared once so the selected data keeps its identity between renders.
 */
function uniquePages<T>(idOf: (row: T) => string) {
  return (data: InfiniteData<T[], number>): InfiniteData<T[], number> => {
    const seen = new Set<string>()
    return {
      ...data,
      pages: data.pages.map((page) =>
        page.filter((row) => {
          const id = idOf(row)
          if (seen.has(id)) return false
          seen.add(id)
          return true
        }),
      ),
    }
  }
}

/**
 * Puts back the nulls non_null inclusion leaves out, for the fields typed `T | null` without a
 * question mark. Screens can then rely on `=== null` for them.
 */
function withNulls<T extends Record<string, unknown>>(row: T, keys: ReadonlyArray<keyof T & string>): T {
  const filled: Record<string, unknown> = { ...row }
  for (const key of keys) {
    if (filled[key] === undefined) filled[key] = null
  }
  return filled as T
}

const agentRow = (agent: Agent): Agent => withNulls(agent, ['revision'])
const agentDetailRow = (agent: AgentDetail): AgentDetail =>
  withNulls(agent, ['revision', 'systemPrompt', 'goals', 'maxSteps'])
const taskRow = (task: Task): Task =>
  withNulls(task, ['agentId', 'result', 'failureReason', 'startedAt', 'completedAt', 'runId'])
const goalRow = (goal: Goal): Goal => ({
  ...withNulls(goal, ['completedAt', 'requestedBy', 'conversationId', 'scheduleId']),
  tasks: goal.tasks.map(taskRow),
})
const approvalRow = (approval: Approval): Approval => withNulls(approval, ['tool'])
const sourceRow = (source: Source): Source => withNulls(source, ['lastIngestedAt', 'lastError'])
const documentRow = (document: SourceDocument): SourceDocument => withNulls(document, ['skipReason', 'indexedAt'])

const questionRow = (question: RunQuestion): RunQuestion => ({
  ...withNulls(question, [
    'taskId',
    'goalId',
    'conversationId',
    'goalTitle',
    'answer',
    'answeredBy',
    'answeredVia',
    'answeredAt',
    'requestedBy',
    'closedReason',
  ]),
  runStatus: question.runStatus ?? 'unknown',
  questions: question.questions.map((item) => ({
    ...item,
    multiSelect: item.multiSelect ?? false,
    options: item.options.map((option) => ({ ...option, recommended: option.recommended ?? false })),
  })),
})

const approvalSummaryRow = (approval: BoardApproval): BoardApproval => ({
  ...withNulls(approval, ['taskId', 'goalId', 'tool', 'requestedBy']),
  canDecide: approval.canDecide ?? false,
})

const boardTaskRow = (task: BoardTask): BoardTask =>
  withNulls(task, [
    'agentId',
    'result',
    'failureReason',
    'startedAt',
    'completedAt',
    'runId',
    'runStatus',
    'stepCount',
    'cost',
  ])
const boardGoalRow = (goal: BoardGoal): BoardGoal => ({
  ...withNulls(goal, ['completedAt', 'requestedBy', 'conversationId', 'scheduleId']),
  tasks: goal.tasks.map(boardTaskRow),
})
const boardQueueRow = (entry: BoardQueueEntry): BoardQueueEntry => withNulls(entry, ['agentId', 'requestedBy'])
const boardTimelineRow = (entry: BoardTimelineEntry): BoardTimelineEntry => withNulls(entry, ['goalId', 'completedAt'])
const boardRow = (board: Board): Board => ({
  ...board,
  agents: board.agents.map((agent) => ({ ...agent, fallback: agent.fallback ?? false, askingRunIds: agent.askingRunIds ?? [] })),
  goals: board.goals.map(boardGoalRow),
  queue: board.queue.map(boardQueueRow),
  timeline: board.timeline.map(boardTimelineRow),
  questions: (board.questions ?? []).map(questionRow),
  approvals: (board.approvals ?? []).map(approvalSummaryRow),
  failedToday: (board.failedToday ?? []).map(boardGoalRow),
  window: board.window ?? 'PT2H',
  windowMinutes: board.windowMinutes ?? 120,
  stats: {
    ...board.stats,
    waitingInput: board.stats.waitingInput ?? 0,
    goalsCompletedToday: board.stats.goalsCompletedToday ?? 0,
    goalsFailedToday: board.stats.goalsFailedToday ?? 0,
    directRuns: board.stats.directRuns ?? 0,
  },
})

const conversationRow = (conversation: Conversation): Conversation => ({
  ...withNulls(conversation, ['createdBy']),
  messageCount: conversation.messageCount ?? 0,
  pinned: conversation.pinned ?? false,
  archived: conversation.archived ?? false,
  activity: conversation.activity ?? 'idle',
  canManage: conversation.canManage ?? false,
  unread: conversation.unread ?? false,
  match: conversation.match ?? null,
  visibility: conversation.visibility ?? 'workspace',
  canShare: conversation.canShare ?? false,
})
const conversationPageRow = (page: ConversationPage): ConversationPage => ({
  pinned: (page.pinned ?? []).map(conversationRow),
  needsYou: (page.needsYou ?? []).map(conversationRow),
  conversations: (page.conversations ?? []).map(conversationRow),
  hasMore: page.hasMore ?? false,
})
const chatMessageRow = (message: ChatMessage): ChatMessage => withNulls(message, ['authorId', 'agentId', 'goalId'])
const conversationDetailRow = (detail: ConversationDetail): ConversationDetail => ({
  conversation: conversationRow(detail.conversation),
  messages: detail.messages.map(chatMessageRow),
  goals: (detail.goals ?? []).map(boardGoalRow),
  questions: (detail.questions ?? []).map(questionRow),
  hasEarlier: detail.hasEarlier ?? false,
  ...(detail.generatedAt ? { generatedAt: detail.generatedAt } : {}),
})

const scheduleRow = (schedule: Schedule): Schedule =>
  withNulls(schedule, ['cron', 'runAt', 'nextRunAt', 'lastRunAt', 'lastStatus', 'lastGoalId', 'pausedReason', 'createdBy'])

const uniqueRuns = uniquePages<Run>((run) => run.id)
const uniqueGoals = uniquePages<Goal>((goal) => goal.id)
const uniqueAuditEvents = uniquePages<AuditEvent>((event) => event.id)

/** How often something that is moving by itself is read. */
const BUSY_POLL_MS = 3_000
/** How often a thread is read while everything in it waits on a person. */
const PARKED_THREAD_POLL_MS = 10_000
/** How often the board is read when nothing on it is moving by itself, whether or not someone is needed. */
const QUIET_BOARD_POLL_MS = 15_000

/** Statuses after which a task will not run again. */
const FINISHED_TASK_STATUSES = new Set(['completed', 'failed', 'cancelled', 'skipped'])

/**
 * Whether a task is moving by itself: running, or about to start because the tasks it follows
 * have all finished (and its agent is not held). A task parked for an approval or an answer, one
 * waiting behind a parked task, and one held behind a paused agent are all waiting on a person,
 * and polling faster does not move them.
 */
function taskIsMoving(task: Task, siblings: readonly Task[], heldAgents: ReadonlySet<string> = new Set()): boolean {
  const status = task.status.toLowerCase()
  if (status === 'running') return true
  if (status !== 'pending' && status !== 'ready') return false
  if (task.agentId && heldAgents.has(task.agentId)) return false
  const follows = task.dependsOn.length > 0
  const waitsOn = follows
    ? siblings.filter((sibling) => task.dependsOn.includes(sibling.id))
    : siblings.filter((sibling) => sibling.position < task.position)
  return waitsOn.every((sibling) => FINISHED_TASK_STATUSES.has(sibling.status.toLowerCase()))
}

/**
 * Whether an open goal has work going on that polling will see change: a task that is moving, or,
 * for a goal still being planned, tasks about to appear.
 */
function goalIsMoving(goal: BoardGoal, heldAgents?: ReadonlySet<string>): boolean {
  if (!isGoalActive(goal)) return false
  if (goal.tasks.length === 0) return goal.status.toLowerCase() === 'planning'
  return goal.tasks.some((task) => taskIsMoving(task, goal.tasks, heldAgents))
}

/**
 * How often a conversation should poll: 3 s while work in it is moving by itself (a task running
 * or about to start, or a goal still being planned), 10 s while everything left in it is only
 * waiting on a person (a question or an approval), otherwise not at all.
 */
export function conversationPollMs(detail: ConversationDetail | undefined): number | false {
  if (!detail) return false
  const activeGoal = detail.goals.some(isGoalActive)
  const pendingQuestion = detail.questions.some((question) => question.status === 'pending')
  if (!activeGoal && !pendingQuestion) return false
  return detail.goals.some((goal) => goalIsMoving(goal)) ? BUSY_POLL_MS : PARKED_THREAD_POLL_MS
}

/**
 * How often the board should poll. 3 s only while something on it is moving by itself: a task
 * running or about to start, a run running (including one started directly on an agent), or a goal
 * still being planned that has no tasks yet. When everything left is parked on a person - an
 * approval, a question, a paused agent - or nothing is open at all, 15 s: a decision made here
 * refreshes the board at once (the mutations invalidate it), and one made elsewhere shows within
 * the quarter minute.
 */
export function boardPollMs(board: Board | undefined): number {
  if (!board) return QUIET_BOARD_POLL_MS
  if (board.stats.running > 0 || board.agents.some((agent) => agent.runningRunIds.length > 0)) return BUSY_POLL_MS
  const held = new Set(board.agents.filter((agent) => agent.status.toLowerCase() !== 'active').map((agent) => agent.id))
  const moving = board.goals.some((goal) => goalIsMoving(goal, held))
  return moving ? BUSY_POLL_MS : QUIET_BOARD_POLL_MS
}

/** Where an infinite list stands: its pages, each a plain array of rows. */
type Pages<T> = InfiniteData<T[], number>

/**
 * A freshly read first page, merged into the pages already loaded, or null when the first page
 * has gained or lost a row and the whole list should be read again instead.
 *
 * Polling reads only the first page, where the rows still changing are. Rows already there are
 * replaced with their newer copies in place. A new row, or one that moved away, shifts every later
 * page, which a first page alone cannot repair, so that is the one case that reads them all.
 */
export function mergeFirstPage<T>(data: Pages<T>, first: T[], idOf: (row: T) => string): Pages<T> | null {
  const loaded = data.pages[0]
  if (!loaded) return null
  if (loaded.length !== first.length || loaded.some((row, index) => idOf(row) !== idOf(first[index]!))) return null
  return { ...data, pages: [first, ...data.pages.slice(1)] }
}

/**
 * Keeps an infinite list's first page fresh without reading every page the person has loaded.
 *
 * The list query itself never refetches on a timer (focus and invalidation still read it whole).
 * This runs a companion query over page 0 only: it replaces that page in the list's cache, and
 * invalidates the list when the first page gained or lost a row. `intervalMs` receives the first
 * page that is in the cache and says how often to look again, or false to stop. The companion
 * starts with a value and nothing to read, so the first timer tick is the first request: the list
 * has only just read that page itself.
 */
function useFirstPagePoll<T>(options: {
  listKey: readonly unknown[]
  enabled: boolean
  idOf: (row: T) => string
  read: (signal: AbortSignal) => Promise<T[]>
  intervalMs: (firstPage: T[] | undefined) => number | false
}) {
  const client = useQueryClient()
  const { listKey, idOf, read, intervalMs } = options
  return useQuery({
    queryKey: [...listKey, 'first-page'],
    queryFn: async ({ signal }) => {
      const first = await read(signal)
      const data = client.getQueryData<Pages<T>>(listKey)
      if (data) {
        const merged = mergeFirstPage(data, first, idOf)
        if (merged) client.setQueryData(listKey, merged)
        else void client.invalidateQueries({ queryKey: listKey, exact: true })
      }
      return null
    },
    enabled: options.enabled,
    initialData: null,
    staleTime: Number.POSITIVE_INFINITY,
    gcTime: 0,
    refetchInterval: () => intervalMs(client.getQueryData<Pages<T>>(listKey)?.pages[0]),
  })
}

/* ---- Invalidation ------------------------------------------------------------------------------- */

/**
 * Refreshes everything a piece of work shows on: the goals and tasks lists, the runs lists, the
 * orchestrator board, the approvals queue and the conversations it came from. Starting, stopping,
 * retrying, deciding or scheduling work changes all of them at once, and a screen must not keep
 * saying what was true before. Questions are separate: refresh ['questions'] as well when the
 * change can open or close one. Does not wait for the refetches, so a mutation settles as soon as
 * the server has answered.
 */
export function invalidateWork(client: QueryClient): void {
  for (const key of ['goals', 'runs', 'board', 'approvals', 'conversations']) {
    void client.invalidateQueries({ queryKey: [key] })
  }
}

/* ---- Reads ------------------------------------------------------------------------------------- */

export const useAgents = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['agents'],
    queryFn: async ({ signal }) => (await api<Agent[]>('/api/agents', { signal })).map(agentRow),
    enabled: options.enabled ?? true,
  })

export const useAgent = (id: string) =>
  useQuery({
    queryKey: ['agents', id],
    queryFn: async ({ signal }) => agentDetailRow(await api<AgentDetail>(`/api/agents/${id}`, { signal })),
  })

/** An agent's own routing policy. `configured` false means it follows the workspace policy. */
export const useAgentModelPolicy = (id: string, options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['agents', id, 'model-policy'],
    queryFn: ({ signal }) => api<ModelPolicy>(`/api/agents/${id}/model-policy`, { signal }),
    enabled: Boolean(id) && (options.enabled ?? true),
  })

/** The 50 most recent runs, refreshed every 5 seconds while any is active and every 30 otherwise. */
export const useRuns = () =>
  useQuery({
    queryKey: ['runs'],
    queryFn: ({ signal }) => api<Run[]>(`/api/runs?size=${RUN_PAGE_SIZE}`, { signal }),
    refetchInterval: (query) => (query.state.data?.some(isRunActive) ? 5_000 : 30_000),
  })

export type RunListFilter = { status?: string | null; agentId?: string | null }

/**
 * Every run, newest first, a page of 50 at a time. `status` and `agentId` filter on the server,
 * so they search every run, not only the loaded pages. `hasNextPage` stays true while the last
 * page was full. Only the first page is read again on a timer (every 5 seconds while it holds a
 * run that is still going, every 30 otherwise), and merged into the pages already loaded.
 */
export function useRunList(filter: RunListFilter = {}) {
  const status = filter.status || null
  const agentId = filter.agentId || null
  const listKey = ['runs', 'list', { status, agentId }] as const
  const read = (page: number, signal: AbortSignal) => {
    const params = new URLSearchParams({ page: String(page), size: String(RUN_PAGE_SIZE) })
    if (status) params.set('status', status)
    if (agentId) params.set('agentId', agentId)
    return api<Run[]>(`/api/runs?${params.toString()}`, { signal })
  }
  const list = useInfiniteQuery({
    queryKey: listKey,
    queryFn: ({ pageParam, signal }) => read(pageParam, signal),
    initialPageParam: 0,
    getNextPageParam: (lastPage, _allPages, lastPageParam) =>
      lastPage.length >= RUN_PAGE_SIZE ? lastPageParam + 1 : undefined,
    select: uniqueRuns,
    // A change of status or agent keeps the previous rows on screen until the new ones arrive,
    // rather than emptying the list to a loading state and a count of "0 of 0".
    placeholderData: keepPreviousData,
  })
  useFirstPagePoll<Run>({
    listKey,
    enabled: list.isSuccess,
    idOf: (run) => run.id,
    read: (signal) => read(0, signal),
    intervalMs: (firstPage) => (firstPage?.some(isRunActive) ? 5_000 : 30_000),
  })
  return list
}

/**
 * One run, refreshed every 3 seconds while it is running or held for an approval. When it
 * finishes, its trace is refetched once more so the last steps are not missed.
 */
export function useRun(id: string) {
  const client = useQueryClient()
  return useQuery({
    queryKey: ['runs', id],
    queryFn: async ({ signal }) => {
      const before = client.getQueryData<Run>(['runs', id])
      const run = await api<Run>(`/api/runs/${id}`, { signal })
      if (isRunActive(before) && !isRunActive(run)) {
        void client.invalidateQueries({ queryKey: ['runs', id, 'steps'] })
      }
      return run
    },
    refetchInterval: (query) => (isRunActive(query.state.data) ? 3_000 : false),
  })
}

/**
 * A run's trace, refreshed every 3 seconds while the run is active. Pass `active` from the run
 * the screen already holds; without it, the cached run from useRun decides.
 */
export function useRunSteps(id: string, options: { active?: boolean } = {}) {
  const client = useQueryClient()
  return useQuery({
    queryKey: ['runs', id, 'steps'],
    queryFn: ({ signal }) => api<RunStep[]>(`/api/runs/${id}/steps`, { signal }),
    enabled: Boolean(id),
    refetchInterval: () =>
      (options.active ?? isRunActive(client.getQueryData<Run>(['runs', id]))) ? 3_000 : false,
  })
}

export const useApprovals = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['approvals'],
    queryFn: async ({ signal }) => (await api<Approval[]>('/api/approvals', { signal })).map(approvalRow),
    // The badge in the navigation depends on this, so it is kept reasonably fresh.
    refetchInterval: 30_000,
    enabled: options.enabled ?? true,
  })

/** The 50 most recent goals with their tasks, refreshed every 5 seconds while any is still active. */
export const useGoals = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['goals'],
    queryFn: async ({ signal }) => (await api<Goal[]>('/api/goals', { signal })).map(goalRow),
    refetchInterval: (query) => (query.state.data?.some(isGoalActive) ? 5_000 : false),
    enabled: options.enabled ?? true,
  })

/** What the goals list can be narrowed to on the server: one status, one source, or one schedule's goals. */
export type GoalListFilter = { status?: string | null; source?: GoalSource | null; scheduleId?: string | null }

/**
 * Every goal, newest first, a page of 50 at a time. `status`, `source` and `scheduleId` filter on
 * the server, so they find every matching goal, not only the ones in the loaded pages. Stops
 * offering another page once a page comes back short, or adds no goal the earlier pages did not
 * already hold. Only the first page is read again on a timer (every 5 seconds while it holds a
 * goal that is still active), and merged into the pages already loaded.
 */
export function useGoalPages(options: QueryOptions & GoalListFilter = {}) {
  const status = options.status || null
  const source = options.source || null
  const scheduleId = options.scheduleId || null
  const listKey = ['goals', 'pages', { status, source, scheduleId }] as const
  const read = async (page: number, signal: AbortSignal) => {
    const params = new URLSearchParams({ page: String(page), size: String(GOAL_PAGE_SIZE) })
    if (status) params.set('status', status)
    if (source) params.set('source', source)
    if (scheduleId) params.set('scheduleId', scheduleId)
    return (await api<Goal[]>(`/api/goals?${params.toString()}`, { signal })).map(goalRow)
  }
  const list = useInfiniteQuery({
    queryKey: listKey,
    queryFn: ({ pageParam, signal }) => read(pageParam, signal),
    initialPageParam: 0,
    getNextPageParam: (lastPage, allPages, lastPageParam) => {
      if (lastPage.length < GOAL_PAGE_SIZE) return undefined
      const earlier = new Set(allPages.slice(0, -1).flatMap((page) => page.map((goal) => goal.id)))
      return lastPage.some((goal) => !earlier.has(goal.id)) ? lastPageParam + 1 : undefined
    },
    select: uniqueGoals,
    // A change of filter keeps the previous rows on screen until the new ones arrive.
    placeholderData: keepPreviousData,
    enabled: options.enabled ?? true,
  })
  useFirstPagePoll<Goal>({
    listKey,
    enabled: list.isSuccess,
    idOf: (goal) => goal.id,
    read: (signal) => read(0, signal),
    intervalMs: (firstPage) => (firstPage?.some(isGoalActive) ? 5_000 : false),
  })
  return list
}

/** One goal and its tasks, for deep links and goals older than the loaded pages. Skipped without an id. */
export const useGoal = (id: string | null | undefined, options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['goals', id ?? ''],
    queryFn: async ({ signal }) => goalRow(await api<Goal>(`/api/goals/${id ?? ''}`, { signal })),
    refetchInterval: (query) => (isGoalActive(query.state.data) ? 5_000 : false),
    enabled: Boolean(id) && (options.enabled ?? true),
  })

/* ---- Clarifying questions ------------------------------------------------------------------- */

/** Every question in the workspace: pending ones ordered by deadline, or every one, newest first. */
export function useQuestions(
  filter: { status?: 'pending' | 'all'; mine?: boolean } = {},
  options: QueryOptions = {},
) {
  const status = filter.status ?? 'pending'
  const mine = filter.mine ?? false
  return useQuery({
    queryKey: ['questions', { status, mine }],
    queryFn: async ({ signal }) => {
      const params = new URLSearchParams({ status, mine: String(mine) })
      return (await api<RunQuestion[]>(`/api/orchestrator/questions?${params.toString()}`, { signal })).map(questionRow)
    },
    refetchInterval: 15_000,
    enabled: options.enabled ?? true,
  })
}

/** A run's own questions, refreshed every 3 seconds while `active` (default: the cached run's own state). */
export function useRunQuestions(runId: string, options: { active?: boolean } = {}) {
  return useQuery({
    queryKey: ['runs', runId, 'questions'],
    queryFn: async ({ signal }) =>
      (await api<RunQuestion[]>(`/api/runs/${runId}/questions`, { signal })).map(questionRow),
    refetchInterval: () => (options.active ? 3_000 : false),
    enabled: Boolean(runId),
  })
}

/** Answers a question. On settle (success or failure) refetches everywhere the question could show. */
export function useAnswerQuestion() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: AnswerQuestionInput) => {
      const result = await api<AnswerQuestionResult>(`/api/orchestrator/questions/${input.id}/answer`, {
        method: 'POST',
        body: { answers: input.answers, note: input.note ?? null, skipped: input.skipped ?? false, via: input.via },
      })
      return { ...result, question: questionRow(result.question) }
    },
    onSettled: () => {
      client.invalidateQueries({ queryKey: ['questions'] })
      invalidateWork(client)
    },
  })
}

/** Keeps a question open another day. */
export function useExtendQuestion() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (id: string) =>
      questionRow(await api<RunQuestion>(`/api/orchestrator/questions/${id}/extend`, { method: 'POST' })),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['questions'] })
      client.invalidateQueries({ queryKey: ['conversations'] })
      client.invalidateQueries({ queryKey: ['board'] })
    },
  })
}

/* ---- Chat -------------------------------------------------------------------------------------- */

/**
 * The workspace's conversations, in three groups (pinned, needing the caller, and the rest),
 * searched and paged. `hasMore` on the last page means there may be another one.
 */
export function useConversationList(filter: { q?: string; scope?: ConversationScope } = {}) {
  const q = filter.q?.trim() || ''
  const scope = filter.scope ?? 'all'
  return useInfiniteQuery({
    queryKey: ['conversations', 'list', { q, scope }],
    queryFn: async ({ pageParam, signal }) => {
      const params = new URLSearchParams({ scope, page: String(pageParam), size: String(CONVERSATION_PAGE_SIZE) })
      if (q) params.set('q', q)
      return conversationPageRow(await api<ConversationPage>(`/api/conversations?${params.toString()}`, { signal }))
    },
    initialPageParam: 0,
    getNextPageParam: (lastPage, _allPages, lastPageParam) => (lastPage.hasMore ? lastPageParam + 1 : undefined),
    refetchInterval: 15_000,
  })
}

/** The newest message position in a thread, or -1 for none. */
function newestMessagePosition(messages: readonly ChatMessage[]): number {
  return messages.reduce((newest, message) => Math.max(newest, message.position), -1)
}

/** Rows merged by id: a later copy replaces an earlier one where it stands, and a new row goes on the end. */
function mergeById<T>(held: readonly T[], received: readonly T[], idOf: (row: T) => string): T[] {
  const receivedById = new Map(received.map((row) => [idOf(row), row]))
  const merged = held.map((row) => receivedById.get(idOf(row)) ?? row)
  const heldIds = new Set(held.map(idOf))
  for (const row of received) {
    if (!heldIds.has(idOf(row))) merged.push(row)
  }
  return merged
}

/**
 * What a conversation reads as once a reply is merged into the copy already held.
 *
 * A reply to "what changed since" holds only the new messages and the goals and questions that
 * moved; a reply from a server that ignores the question holds the whole recent window. Both
 * merge the same way, by id, which is why this works whether the server understood or not: each
 * message, goal and question is kept once, the received copy winning, and the thread stays in
 * position order.
 *
 * The one reply that replaces the copy instead is a window that starts well after the newest
 * message held while claiming there is more before it: more arrived than the window holds, so
 * merging would leave a gap in the thread.
 */
export function mergeConversationDetail(held: ConversationDetail, received: ConversationDetail): ConversationDetail {
  if (received.hasEarlier && received.messages.length > 0) {
    const oldestReceived = received.messages.reduce((oldest, message) => Math.min(oldest, message.position), Infinity)
    if (oldestReceived > newestMessagePosition(held.messages) + 1) return received
  }

  return {
    conversation: received.conversation,
    messages: mergeById(held.messages, received.messages, (message) => message.id).sort(
      (a, b) => a.position - b.position,
    ),
    goals: mergeById(held.goals, received.goals, (goal) => goal.id).sort(
      (a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt) || a.id.localeCompare(b.id),
    ),
    questions: mergeById(held.questions, received.questions, (question) => question.id),
    // A reply that only holds changes says nothing about what lies before the thread held.
    hasEarlier: held.hasEarlier,
    ...(received.generatedAt ? { generatedAt: received.generatedAt } : {}),
  }
}

/**
 * One conversation, its thread, its board-style goals and its questions. Polls through
 * conversationPollMs while something in it is still moving.
 *
 * The first read is the recent window. Every read after that asks only for what changed since the
 * last one (`after` the newest message held, `since` the server's own time of the last read), so a
 * poll moves a few rows instead of the whole window, and merges the answer into the cached thread.
 * A poll stops for good once the conversation is found to be gone.
 */
export function useConversation(id: string | null | undefined, options: QueryOptions = {}) {
  const client = useQueryClient()
  const key = ['conversations', id ?? '']
  return useQuery({
    queryKey: key,
    queryFn: async ({ signal }) => {
      const held = client.getQueryData<ConversationDetail>(key)
      const params = new URLSearchParams({ limit: '200' })
      if (held?.generatedAt) {
        params.set('after', String(newestMessagePosition(held.messages)))
        params.set('since', held.generatedAt)
      }
      const received = conversationDetailRow(
        await api<ConversationDetail>(`/api/conversations/${id ?? ''}?${params.toString()}`, { signal }),
      )
      return held ? mergeConversationDetail(held, received) : received
    },
    enabled: Boolean(id) && (options.enabled ?? true),
    refetchInterval: (query) =>
      query.state.error instanceof ApiError && query.state.error.isNotFound ? false : conversationPollMs(query.state.data),
  })
}

export function useCreateConversation() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { title?: string } = {}) =>
      conversationRow(await api<Conversation>('/api/conversations', { method: 'POST', body: input })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['conversations'] }),
  })
}

/** Sends a message in a conversation. Resolves to the messages the request created (the person's, then any coordinator replies). */
export function useSendMessage(conversationId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { text: string; agentIds?: string[]; attachmentIds?: string[] }) => {
      const result = await api<{ messages: ChatMessage[] }>(`/api/conversations/${conversationId}/messages`, {
        method: 'POST',
        body: input,
      })
      return result.messages.map(chatMessageRow)
    },
    onSuccess: () => invalidateWork(client),
  })
}

/** Sends one routed message to a different agent instead, cancelling the goal it started if that goal is still open. */
export function useReroute(conversationId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { messageId: string; agentId: string }) => {
      const result = await api<{ messages: ChatMessage[] }>(
        `/api/conversations/${conversationId}/messages/${input.messageId}/reroute`,
        { method: 'POST', body: { agentId: input.agentId } },
      )
      return result.messages.map(chatMessageRow)
    },
    onSuccess: () => invalidateWork(client),
  })
}

export function useRenameConversation() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, title }: { id: string; title: string }) =>
      conversationRow(await api<Conversation>(`/api/conversations/${id}`, { method: 'PATCH', body: { title } })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['conversations'] }),
  })
}

export function usePinConversation() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, pinned }: { id: string; pinned: boolean }) =>
      api<void>(`/api/conversations/${id}/pin`, { method: pinned ? 'PUT' : 'DELETE' }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['conversations'] }),
  })
}

export function useArchiveConversation() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, archived }: { id: string; archived: boolean }) =>
      api<void>(`/api/conversations/${id}/archive`, { method: archived ? 'PUT' : 'DELETE' }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['conversations'] }),
  })
}

/** Deletes a conversation. The service refuses this for anyone but the person who created it. */
export function useDeleteConversation() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<void>(`/api/conversations/${id}`, { method: 'DELETE' }),
    onSuccess: (_data, id) => {
      client.invalidateQueries({ queryKey: ['conversations'] })
      client.invalidateQueries({ queryKey: ['board'] })
      client.removeQueries({ queryKey: ['conversations', id] })
    },
  })
}

/**
 * Marks a conversation read up to `position`. Patches the cached list rows directly instead of
 * refetching, so the unread dot clears at once and there is no refetch storm.
 */
export function useMarkConversationRead(conversationId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { position: number }) =>
      api<void>(`/api/conversations/${conversationId}/read`, { method: 'PUT', body: input }),
    onSuccess: () => {
      const clearUnread = (row: Conversation): Conversation => (row.id === conversationId ? { ...row, unread: false } : row)
      const patch = (page: ConversationPage): ConversationPage => ({
        pinned: page.pinned.map(clearUnread),
        needsYou: page.needsYou.map(clearUnread),
        conversations: page.conversations.map(clearUnread),
        hasMore: page.hasMore,
      })
      client.setQueriesData<InfiniteData<ConversationPage, number>>({ queryKey: ['conversations', 'list'] }, (data) =>
        data ? { ...data, pages: data.pages.map(patch) } : data,
      )
    },
  })
}

/** Stops a chat-started goal from the thread. */
export function useStopChatGoal(conversationId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { goalId: string }) =>
      api<GoalActionResult>(`/api/conversations/${conversationId}/goals/${input.goalId}/stop`, { method: 'POST' }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['questions'] })
      invalidateWork(client)
    },
  })
}

/** Retries a chat-started goal from the thread. */
export function useRetryChatGoal(conversationId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { goalId: string }) =>
      api<GoalActionResult>(`/api/conversations/${conversationId}/goals/${input.goalId}/retry`, { method: 'POST' }),
    onSuccess: () => invalidateWork(client),
  })
}

/** Falls back to General Employee, or answers from the passages already found, for a documents message. */
export function useAnswerFromDocuments(conversationId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { messageId: string; agentId?: string }) => {
      const result = await api<{ messages: ChatMessage[] }>(
        `/api/conversations/${conversationId}/messages/${input.messageId}/answer-from-documents`,
        { method: 'POST', body: input.agentId ? { agentId: input.agentId } : {} },
      )
      return result.messages.map(chatMessageRow)
    },
    onSuccess: () => invalidateWork(client),
  })
}

/**
 * The old, flat conversation list, kept only so `routes/Chat.tsx` keeps compiling until it moves
 * to useConversationList. Reads the first page with scope=all (the server default) and flattens
 * pinned ahead of the rest, de-duplicated by id.
 * @deprecated Use useConversationList.
 */
export const useConversations = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['conversations'],
    queryFn: async ({ signal }) => {
      const page = conversationPageRow(await api<ConversationPage>('/api/conversations?scope=all', { signal }))
      const seen = new Set<string>()
      const combined: Conversation[] = []
      for (const conversation of [...page.pinned, ...page.conversations]) {
        if (seen.has(conversation.id)) continue
        seen.add(conversation.id)
        combined.push(conversation)
      }
      return combined
    },
    enabled: options.enabled ?? true,
  })

/* ---- Orchestrator board -------------------------------------------------------------------------- */

/** The whole live board: agents, active goals, the queue, questions, approvals and the timeline (run:read). */
export const useBoard = (options: { window?: BoardWindow; paused?: boolean } = {}) => {
  const boardWindow = options.window ?? 'PT2H'
  const paused = options.paused ?? false
  return useQuery({
    queryKey: ['board', boardWindow],
    queryFn: async ({ signal }) => boardRow(await api<Board>(`/api/orchestrator/board?window=${boardWindow}`, { signal })),
    refetchInterval: (query) => (paused ? 15_000 : boardPollMs(query.state.data)),
  })
}

/** Cancels every active run, pending approval and open task in the workspace (run:cancel). */
export function useStopAll() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { pauseSchedules?: boolean } | void) =>
      api<StopAllResult>('/api/orchestrator/stop-all', {
        method: 'POST',
        body: input && input.pauseSchedules !== undefined ? { pauseSchedules: input.pauseSchedules } : {},
      }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['questions'] })
      client.invalidateQueries({ queryKey: ['schedules'] })
      invalidateWork(client)
    },
  })
}

/** Pauses or resumes one agent (agent:update), chosen by `action`: its queued and future tasks stay pending, held rather than failed. */
export function useSetAgentStatus() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { id: string; action: AgentStatusAction }) =>
      agentRow(await api<Agent>(`/api/agents/${input.id}/${input.action}`, { method: 'POST' })),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['agents'] })
      client.invalidateQueries({ queryKey: ['board'] })
    },
  })
}

/* ---- Schedules ----------------------------------------------------------------------------------- */

/** Every schedule in the workspace (task:read), refreshed every 30 seconds. */
export const useSchedules = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['schedules'],
    queryFn: async ({ signal }) => (await api<Schedule[]>('/api/schedules', { signal })).map(scheduleRow),
    refetchInterval: 30_000,
    enabled: options.enabled ?? true,
  })

/** Reads a plain-English phrase back as a schedule, without saving anything (task:read). */
export function useSchedulePreview() {
  return useMutation({
    mutationFn: (input: { text: string; timezone?: string }) =>
      api<SchedulePreview>('/api/schedules/preview', { method: 'POST', body: input }),
  })
}

export function useCreateSchedule() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { name: string; agentId: string; instruction: string; text: string }) =>
      scheduleRow(await api<Schedule>('/api/schedules', { method: 'POST', body: input })),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['schedules'] })
      invalidateWork(client)
    },
  })
}

export function useUpdateSchedule(id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { name?: string; agentId?: string; instruction?: string; text?: string }) =>
      scheduleRow(await api<Schedule>(`/api/schedules/${id}`, { method: 'PUT', body: input })),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['schedules'] })
      invalidateWork(client)
    },
  })
}

/**
 * Shared by pause/resume/run-now: each just posts to its own path and refreshes the same queries.
 * Running now starts a goal, so the work lists refresh with the schedules.
 */
function useScheduleAction(id: string, action: 'pause' | 'resume' | 'run-now') {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async () => scheduleRow(await api<Schedule>(`/api/schedules/${id}/${action}`, { method: 'POST' })),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['schedules'] })
      invalidateWork(client)
    },
  })
}

export const usePauseSchedule = (id: string) => useScheduleAction(id, 'pause')
export const useResumeSchedule = (id: string) => useScheduleAction(id, 'resume')
/** Fires the schedule now, outside its own timetable. The returned ScheduleView carries the new goal in `lastGoalId`. */
export const useRunScheduleNow = (id: string) => useScheduleAction(id, 'run-now')

export function useDeleteSchedule() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<void>(`/api/schedules/${id}`, { method: 'DELETE' }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['schedules'] })
      invalidateWork(client)
    },
  })
}

/** A schedule's own run history: the goals it created, newest first (task:read). */
/* ---- Voice --------------------------------------------------------------------------------------- */

/** Whether ElevenLabs is configured for this workspace, and today's quota (chat:use). */
export const useVoiceStatus = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['voice', 'status'],
    queryFn: ({ signal }) => api<VoiceStatus>('/api/voice/status', { signal }),
    enabled: options.enabled ?? true,
  })

/** The voices ElevenLabs offers. Empty without a stored key (chat:use). */
export const useVoices = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['voice', 'voices'],
    queryFn: ({ signal }) => api<Voice[]>('/api/voice/voices', { signal }),
    enabled: options.enabled ?? true,
  })

/** Sets, or clears with null, the voice an agent speaks with (agent:update). */
export function useSetAgentVoice(agentId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (voiceId: string | null) =>
      agentRow(await api<Agent>(`/api/agents/${agentId}/voice`, { method: 'PUT', body: { voiceId } })),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['agents'] })
      client.invalidateQueries({ queryKey: ['agents', agentId] })
    },
  })
}

export const useProviders = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['providers'],
    queryFn: ({ signal }) => api<Provider[]>('/api/providers', { signal }),
    enabled: options.enabled ?? true,
  })

export const useModels = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['providers', 'models'],
    queryFn: ({ signal }) => api<Model[]>('/api/providers/models', { signal }),
    enabled: options.enabled ?? true,
  })

/**
 * Every tool-capable model a provider itself lists, for the model picker. The service caches the
 * list for six hours per workspace; `refresh` asks the provider again and replaces what is shown.
 */
export function useProviderCatalogue(providerId: string, options: QueryOptions = {}) {
  const client = useQueryClient()
  const queryKey = ['providers', providerId, 'catalogue'] as const
  const query = useQuery({
    queryKey,
    queryFn: ({ signal }) => api<ProviderCatalogue>(`/api/providers/${encodeURIComponent(providerId)}/models`, { signal }),
    enabled: (options.enabled ?? true) && providerId !== '',
    staleTime: 30 * 60 * 1000,
  })
  const refresh = useMutation({
    mutationFn: () => api<ProviderCatalogue>(`/api/providers/${encodeURIComponent(providerId)}/models?refresh=true`),
    onSuccess: (data) => client.setQueryData(queryKey, data),
  })
  return { ...query, refresh }
}

export const useModelPolicy = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['model-policy'],
    queryFn: ({ signal }) => api<ModelPolicy>('/api/model-policy', { signal }),
    enabled: options.enabled ?? true,
  })

/** Which credentials are stored, without their values (provider:read). */
export const useCredentials = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['credentials'],
    queryFn: ({ signal }) => api<CredentialView[]>('/api/credentials', { signal }),
    enabled: options.enabled ?? true,
  })

export function useSetModelPolicy() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: ModelPolicyInput) =>
      api<ModelPolicy>('/api/model-policy', { method: 'PUT', body: input }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['model-policy'] }),
  })
}

export function useStoreCredential() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { ref: string; kind: string; value: string; expiresAt?: string | null }) =>
      api<CredentialView>(`/api/credentials/${input.ref}`, {
        method: 'PUT',
        body: { kind: input.kind, value: input.value, ...(input.expiresAt ? { expiresAt: input.expiresAt } : {}) },
      }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['credentials'] })
      client.invalidateQueries({ queryKey: ['providers'] })
    },
  })
}

export const useSources = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['sources'],
    queryFn: async ({ signal }) => (await api<Source[]>('/api/sources', { signal })).map(sourceRow),
    enabled: options.enabled ?? true,
  })

export const useSource = (id: string) =>
  useQuery({
    queryKey: ['sources', id],
    queryFn: async ({ signal }) => sourceRow(await api<Source>(`/api/sources/${id}`, { signal })),
  })

export const useSourceDocuments = (id: string) =>
  useQuery({
    queryKey: ['sources', id, 'documents'],
    queryFn: async ({ signal }) =>
      (await api<SourceDocument[]>(`/api/sources/${id}/documents`, { signal })).map(documentRow),
  })

/** Every connector, with its tools, catalog details and connection state (integration:read). */
export const useIntegrations = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['integrations'],
    queryFn: ({ signal }) => api<Integration[]>('/api/integrations', { signal }),
    enabled: options.enabled ?? true,
  })

export const useMembers = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['members'],
    queryFn: ({ signal }) => api<Member[]>('/api/users', { signal }),
    enabled: options.enabled ?? true,
  })

export const useInvitations = (orgId: string, options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['invitations', orgId],
    queryFn: ({ signal }) => api<Invitation[]>(`/api/orgs/${orgId}/invitations`, { signal }),
    enabled: Boolean(orgId) && (options.enabled ?? true),
  })

/** Every role with its permissions and holder count (role:read). */
export const useRoles = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['roles'],
    queryFn: ({ signal }) => api<Role[]>('/api/roles', { signal }),
    enabled: options.enabled ?? true,
  })

/** Every permission code with its description (workspace:read, which every role holds). */
export const usePermissionCatalogue = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['roles', 'permissions'],
    queryFn: ({ signal }) => api<PermissionInfo[]>('/api/roles/permissions', { signal }),
    enabled: options.enabled ?? true,
  })

/* ---- Writes ------------------------------------------------------------------------------------ */

export function useCreateGoal() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { title: string; agentId: string; instruction: string }) =>
      goalRow(
        await api<Goal>('/api/goals', {
          method: 'POST',
          body: {
            title: input.title,
            tasks: [{ agentId: input.agentId, title: input.title, instruction: input.instruction }],
          },
        }),
      ),
    onSuccess: () => invalidateWork(client),
  })
}

export function useDecideApproval() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { id: string; approved: boolean; note?: string; mode?: 'approve' | 'reject' | 'request_changes' }) =>
      api<DecisionResult>(`/api/approvals/${input.id}/decision`, {
        method: 'POST',
        body: {
          approved: input.approved,
          mode: input.mode ?? (input.approved ? 'approve' : 'reject'),
          note: input.note ?? null,
        },
      }),
    // Settled rather than success: a decision that failed because someone else already decided,
    // or the approval expired, must still clear the stale card.
    onSettled: () => invalidateWork(client),
  })
}

export function useCreateAgent() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { key: string; name: string; category: string; systemPrompt: string }) =>
      agentRow(await api<Agent>('/api/agents', { method: 'POST', body: input })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['agents'] }),
  })
}

export function useUpdateAgent(id: string) {
  const client = useQueryClient()
  return useMutation({
    /** `maxSteps` null or left out saves the default of 12. */
    mutationFn: async (input: { systemPrompt: string; goals: string; maxSteps?: number | null }) =>
      agentRow(await api<Agent>(`/api/agents/${id}/configuration`, { method: 'PUT', body: input })),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['agents'] })
    },
  })
}

export function useCancelRun() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<Run>(`/api/runs/${id}/cancel`, { method: 'POST' }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['questions'] })
      invalidateWork(client)
    },
  })
}

export function useCreateSource() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { name: string; kind: string }) =>
      sourceRow(await api<Source>('/api/sources', { method: 'POST', body: input })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['sources'] }),
  })
}

export function useToggleProvider() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { id: string; enable: boolean }) =>
      api<Provider>(`/api/providers/${input.id}/${input.enable ? 'enable' : 'disable'}`, { method: 'POST' }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['providers'] }),
  })
}

/* ---- Connectors ---------------------------------------------------------------------------------- */

const serverPath = (server: string) => `/api/integrations/${encodeURIComponent(server)}`

/**
 * Stores a live token for a connector (integration:connect). The service checks it with the
 * connector's own "who am I" call first and refuses one that fails (422 connector_check_failed),
 * so a token that is saved is a token that worked.
 */
export function useConnectIntegration() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: {
      server: string
      token?: string
      /** Values for a connector with two or more credential fields, keyed by CredentialField.key. */
      fields?: Record<string, string>
      accountLabel?: string | null
    }) =>
      api<Integration>(`${serverPath(input.server)}/connection`, {
        method: 'PUT',
        body: {
          ...(input.token !== undefined ? { token: input.token } : {}),
          ...(input.fields ? { fields: input.fields } : {}),
          ...(input.accountLabel ? { accountLabel: input.accountLabel } : {}),
        },
      }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['integrations'] }),
  })
}

/** The app saved for a sign-in connector's provider (integration:connect). */
export const useOAuthApp = (server: string, options: QueryOptions = {}) =>
  useQuery({
    ...options,
    queryKey: ['integrations', server, 'oauth-app'],
    queryFn: ({ signal }) => api<OAuthAppView>(`${serverPath(server)}/oauth/app`, { signal }),
  })

/** Saves the app (client ID, secret and settings). Omit clientSecret to keep the stored one. */
export function useSaveOAuthApp() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { server: string; clientId: string; clientSecret?: string; settings: Record<string, string> }) =>
      api<OAuthAppView>(`${serverPath(input.server)}/oauth/app`, {
        method: 'PUT',
        body: {
          clientId: input.clientId,
          ...(input.clientSecret ? { clientSecret: input.clientSecret } : {}),
          settings: input.settings,
        },
      }),
    onSuccess: (view, input) => {
      client.setQueryData(['integrations', input.server, 'oauth-app'], view)
      return client.invalidateQueries({ queryKey: ['integrations'], exact: true })
    },
  })
}

/** Asks for the provider's sign-in address; the page then sends the browser there. */
export function useStartOAuth() {
  return useMutation({
    mutationFn: (server: string) => api<{ authorizeUrl: string }>(`${serverPath(server)}/oauth/start`),
  })
}

/** Runs the connector's check again against the stored token (integration:read). Records lastError. */
export function useTestIntegration() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (server: string) => api<ConnectionTestResult>(`${serverPath(server)}/test`, { method: 'POST' }),
    // Settled, not success: a failed check is recorded on the connector too.
    onSettled: () => client.invalidateQueries({ queryKey: ['integrations'] }),
  })
}

/** Deletes the stored token and puts the connector back on the sandbox (integration:disconnect). */
export function useDisconnectIntegration() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (server: string) => api<void>(`${serverPath(server)}/connection`, { method: 'DELETE' }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['integrations'] }),
  })
}

/** After a grant changes: the agent's page from the response, and every agent list and card. */
function afterGrantChange(client: ReturnType<typeof useQueryClient>, agentId: string, detail: AgentDetail | undefined) {
  // Only a full detail replaces the cached one; an empty reply (a 204) leaves it to the refetch.
  if (detail && Array.isArray(detail.grants)) client.setQueryData(['agents', agentId], agentDetailRow(detail))
  // ['agents'] is a prefix of ['agents', agentId], so this also refetches the detail just set.
  client.invalidateQueries({ queryKey: ['agents'] })
}

/** Adds a connector to an agent, or changes what it may do there (agent:update). Upsert. */
export function useSaveAgentGrant(agentId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: GrantInput & { server: string }) =>
      api<AgentDetail>(`/api/agents/${agentId}/grants/${encodeURIComponent(input.server)}`, {
        method: 'PUT',
        body: { tools: input.tools, requireApproval: input.requireApproval, maxCallsPerRun: input.maxCallsPerRun },
      }),
    onSuccess: (detail) => afterGrantChange(client, agentId, detail),
  })
}

/** Takes a connector away from an agent (agent:update). */
export function useRemoveAgentGrant(agentId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (server: string) =>
      api<AgentDetail>(`/api/agents/${agentId}/grants/${encodeURIComponent(server)}`, { method: 'DELETE' }),
    onSuccess: (detail) => afterGrantChange(client, agentId, detail),
  })
}

/** Agent names by id, for screens that show runs and approvals. */
export function useAgentNames(options: QueryOptions = {}): Record<string, Agent> {
  const { data } = useAgents(options)
  return useMemo(() => Object.fromEntries((data ?? []).map((agent) => [agent.id, agent])), [data])
}

/**
 * Members by user id, for naming the people behind audit entries, cancellations and decisions.
 * member:read is held by every built-in role. A miss means a former member: show a short id.
 */
export function useMemberNames(options: QueryOptions = {}): Record<string, Member> {
  const { data } = useMembers(options)
  return useMemo(() => Object.fromEntries((data ?? []).map((member) => [member.userId, member])), [data])
}

/**
 * Each task with its goal, by task id, from the 50 most recent goals (useGoals). A miss means the
 * task belongs to an older goal: fall back to neutral wording, or load it with useGoal.
 */
export function useTaskIndex(options: QueryOptions = {}): Record<string, { task: Task; goal: Goal }> {
  const { data } = useGoals(options)
  return useMemo(() => {
    const index: Record<string, { task: Task; goal: Goal }> = {}
    for (const goal of data ?? []) {
      for (const task of goal.tasks) index[task.id] = { task, goal }
    }
    return index
  }, [data])
}

/**
 * Starts a run from a direct instruction. Resolves to RunStarted: navigate with `runId`.
 * `id` repeats `runId` only so callers written against the old Run typing keep working.
 */
export function useCreateRun(agentId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { instruction: string }): Promise<RunStarted & { /** @deprecated Use runId. */ id: string }> => {
      const started = await api<RunStarted>(`/api/agents/${agentId}/runs`, {
        method: 'POST',
        body: { instruction: input.instruction },
      })
      return { ...started, id: started.runId }
    },
    onSuccess: () => invalidateWork(client),
  })
}

export function useCancelGoal(goalId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async () => goalRow(await api<Goal>(`/api/goals/${goalId}/cancel`, { method: 'POST' })),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['questions'] })
      invalidateWork(client)
    },
  })
}

/** Tries a failed or stopped goal again, from the step that did not finish (D-7: requester or task:cancel). */
export function useRetryGoal() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (goalId: string) => goalRow(await api<Goal>(`/api/goals/${goalId}/retry`, { method: 'POST' })),
    onSuccess: () => invalidateWork(client),
  })
}

/** Starts a failed, abandoned or cancelled direct run again with its original instruction; returns the new run. */
export function useRetryRun() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (runId: string) =>
      api<{ runId: string; retryOf: string; status: string }>(`/api/runs/${runId}/retry`, { method: 'POST' }),
    onSuccess: () => invalidateWork(client),
  })
}

export function useReindexSource(sourceId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api<ReindexResult>(`/api/sources/${sourceId}/reindex`, { method: 'POST' }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['sources'] })
      client.invalidateQueries({ queryKey: ['sources', sourceId] })
      client.invalidateQueries({ queryKey: ['sources', sourceId, 'documents'] })
    },
  })
}

export function useCreateRole() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { name: string; description: string; permissions: string[] }) =>
      api<Role>('/api/roles', { method: 'POST', body: input }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['roles'] }),
  })
}

export function useDeleteRoleMutation() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<void>(`/api/roles/${id}`, { method: 'DELETE' }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['roles'] }),
  })
}

export function useUpdateRoleMutation() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { id: string; name: string; description: string; permissions: string[] }) =>
      api<Role>(`/api/roles/${input.id}`, { method: 'PUT', body: input }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['roles'] }),
  })
}

export function useInviteMember(orgId: string, email: string, roleName: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () =>
      api<Invitation>(`/api/orgs/${orgId}/invitations`, {
        method: 'POST',
        body: { email, roleName },
      }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['members'] })
      client.invalidateQueries({ queryKey: ['invitations', orgId] })
    },
  })
}

export function useUpdateMemberRole() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { userId: string; roleName: string }) =>
      api<Member>(`/api/users/${input.userId}/role`, { method: 'PUT', body: { roleName: input.roleName } }),
    // Roles too: each role's holder count changes with the member.
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['members'] })
      client.invalidateQueries({ queryKey: ['roles'] })
    },
  })
}

export function useRemoveMember() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (userId: string) => api<void>(`/api/users/${userId}`, { method: 'DELETE' }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['members'] })
      client.invalidateQueries({ queryKey: ['roles'] })
    },
  })
}

export function useAcceptInvitation() {
  return useMutation({
    mutationFn: (input: { token: string; displayName: string; password: string }) =>
      api<{ userId: string; orgId: string; email: string; roleName: string }>('/api/invitations/accept', {
        method: 'POST',
        body: input,
      }),
  })
}

export type AuditEvent = {
  id: string
  sequence: number
  actorId: string
  actorKind: string
  onBehalfOf?: string | null
  action: string
  resourceType: string
  resourceId?: string | null
  outcome: 'succeeded' | 'failed' | 'denied' | 'locked'
  detail: Record<string, unknown>
  occurredAt: string
  previousHash?: string | null
  entryHash?: string | null
}

export type ActionCount = { action: string; count: number }
export type OutcomeCount = { outcome: string; count: number }

export type AnalyticsSummary = {
  windowStart: string
  windowEnd: string
  totalEvents: number
  byAction: ActionCount[]
  byOutcome: OutcomeCount[]
}

export function useAnalytics() {
  return useQuery({
    queryKey: ['analytics'],
    queryFn: ({ signal }) => api<AnalyticsSummary>('/api/analytics', { signal }),
  })
}

/** What an auditor can narrow the log by; everything is optional and sent to the server. */
export type AuditFilters = {
  action?: string | undefined
  resourceType?: string | undefined
  outcome?: string | undefined
  actorId?: string | undefined
  /** Calendar days, yyyy-mm-dd, inclusive. */
  from?: string | undefined
  to?: string | undefined
}

/** The query string for a set of filters. A calendar day becomes the start or end of that day in UTC. */
export function auditFilterParams(filters: AuditFilters): URLSearchParams {
  const params = new URLSearchParams()
  if (filters.action?.trim()) params.set('action', filters.action.trim())
  if (filters.resourceType?.trim()) params.set('resourceType', filters.resourceType.trim())
  if (filters.outcome) params.set('outcome', filters.outcome)
  if (filters.actorId?.trim()) params.set('actorId', filters.actorId.trim())
  if (filters.from) params.set('from', `${filters.from}T00:00:00Z`)
  if (filters.to) params.set('to', `${filters.to}T23:59:59.999999Z`)
  return params
}

/** The audit log, newest first, a page of 100 at a time, narrowed by the server. */
export function useAuditPages(filters: AuditFilters = {}) {
  const extra = auditFilterParams(filters).toString()
  return useInfiniteQuery({
    queryKey: ['audit', 'pages', extra],
    queryFn: ({ pageParam, signal }) =>
      api<AuditEvent[]>(`/api/audit?page=${pageParam}&size=${AUDIT_PAGE_SIZE}${extra ? `&${extra}` : ''}`, {
        signal,
      }),
    initialPageParam: 0,
    getNextPageParam: (lastPage, _allPages, lastPageParam) =>
      lastPage.length >= AUDIT_PAGE_SIZE ? lastPageParam + 1 : undefined,
    select: uniqueAuditEvents,
  })
}

/** GET /api/audit/verify */
export type AuditVerification = {
  verified: boolean
  checked: number
  lastSequence: number
  firstBrokenSequence?: number
  reason?: string
}

export function useVerifyAudit() {
  return useMutation({ mutationFn: () => api<AuditVerification>('/api/audit/verify') })
}
