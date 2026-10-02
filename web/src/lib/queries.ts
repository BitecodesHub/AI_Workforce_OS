import { useMemo } from 'react'
import {
  keepPreviousData,
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type InfiniteData,
} from '@tanstack/react-query'
import { api } from './api'
import type { AuditOutcome } from './labels'

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
  enabled: boolean
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
  runStatus: string | null
  stepCount: number | null
  cost: number | null
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
const passageRow = (passage: Passage): Passage => withNulls(passage, ['uri', 'pageNumber', 'heading'])

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
})

const scheduleRow = (schedule: Schedule): Schedule =>
  withNulls(schedule, ['cron', 'runAt', 'nextRunAt', 'lastRunAt', 'lastStatus', 'lastGoalId', 'pausedReason', 'createdBy'])

const uniqueRuns = uniquePages<Run>((run) => run.id)
const uniqueGoals = uniquePages<Goal>((goal) => goal.id)
const uniqueAuditEvents = uniquePages<AuditEvent>((event) => event.id)

/**
 * How often a conversation should poll: 3 s while a goal in it is actively running, 10 s while
 * something in it is only waiting on a person (a question or an approval), otherwise not at all.
 */
export function conversationPollMs(detail: ConversationDetail | undefined): number | false {
  if (!detail) return false
  const activeGoal = detail.goals.some(isGoalActive)
  const pendingQuestion = detail.questions.some((question) => question.status === 'pending')
  if (!activeGoal && !pendingQuestion) return false
  const parkedTask = detail.goals.some((goal) => goal.tasks.some((task) => PARKED_STATUSES.has(task.status.toLowerCase())))
  return pendingQuestion || parkedTask ? 10_000 : 3_000
}

/** 3 s while any goal is active or a question or approval is pending, else 15 s. */
export function boardPollMs(board: Board | undefined): number {
  if (!board) return 15_000
  const activeGoal = board.goals.some(isGoalActive)
  const pendingQuestion = board.questions.some((question) => question.status === 'pending')
  return activeGoal || pendingQuestion || board.approvals.length > 0 ? 3_000 : 15_000
}

/* ---- Reads ------------------------------------------------------------------------------------- */

export const useAgents = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['agents'],
    queryFn: async () => (await api<Agent[]>('/api/agents')).map(agentRow),
    enabled: options.enabled ?? true,
  })

export const useAgent = (id: string) =>
  useQuery({
    queryKey: ['agents', id],
    queryFn: async () => agentDetailRow(await api<AgentDetail>(`/api/agents/${id}`)),
  })

/** An agent's own routing policy. `configured` false means it follows the workspace policy. */
export const useAgentModelPolicy = (id: string, options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['agents', id, 'model-policy'],
    queryFn: () => api<ModelPolicy>(`/api/agents/${id}/model-policy`),
    enabled: Boolean(id) && (options.enabled ?? true),
  })

/** The 50 most recent runs, refreshed every 5 seconds while any is active and every 30 otherwise. */
export const useRuns = () =>
  useQuery({
    queryKey: ['runs'],
    queryFn: () => api<Run[]>(`/api/runs?size=${RUN_PAGE_SIZE}`),
    refetchInterval: (query) => (query.state.data?.some(isRunActive) ? 5_000 : 30_000),
  })

export type RunListFilter = { status?: string | null; agentId?: string | null }

/**
 * Every run, newest first, a page of 50 at a time. `status` and `agentId` filter on the server,
 * so they search every run, not only the loaded pages. `hasNextPage` stays true while the last
 * page was full.
 */
export function useRunList(filter: RunListFilter = {}) {
  const status = filter.status || null
  const agentId = filter.agentId || null
  return useInfiniteQuery({
    queryKey: ['runs', 'list', { status, agentId }],
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ page: String(pageParam), size: String(RUN_PAGE_SIZE) })
      if (status) params.set('status', status)
      if (agentId) params.set('agentId', agentId)
      return api<Run[]>(`/api/runs?${params.toString()}`)
    },
    initialPageParam: 0,
    getNextPageParam: (lastPage, _allPages, lastPageParam) =>
      lastPage.length >= RUN_PAGE_SIZE ? lastPageParam + 1 : undefined,
    select: uniqueRuns,
    // A change of status or agent keeps the previous rows on screen until the new ones arrive,
    // rather than emptying the list to a loading state and a count of "0 of 0".
    placeholderData: keepPreviousData,
    refetchInterval: (query) =>
      query.state.data?.pages.some((page) => page.some(isRunActive)) ? 5_000 : 30_000,
  })
}

/**
 * One run, refreshed every 3 seconds while it is running or held for an approval. When it
 * finishes, its trace is refetched once more so the last steps are not missed.
 */
export function useRun(id: string) {
  const client = useQueryClient()
  return useQuery({
    queryKey: ['runs', id],
    queryFn: async () => {
      const before = client.getQueryData<Run>(['runs', id])
      const run = await api<Run>(`/api/runs/${id}`)
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
    queryFn: () => api<RunStep[]>(`/api/runs/${id}/steps`),
    refetchInterval: () =>
      (options.active ?? isRunActive(client.getQueryData<Run>(['runs', id]))) ? 3_000 : false,
  })
}

export const useApprovals = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['approvals'],
    queryFn: async () => (await api<Approval[]>('/api/approvals')).map(approvalRow),
    // The badge in the navigation depends on this, so it is kept reasonably fresh.
    refetchInterval: 30_000,
    enabled: options.enabled ?? true,
  })

/** The 50 most recent goals with their tasks, refreshed every 5 seconds while any is still active. */
export const useGoals = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['goals'],
    queryFn: async () => (await api<Goal[]>('/api/goals')).map(goalRow),
    refetchInterval: (query) => (query.state.data?.some(isGoalActive) ? 5_000 : false),
    enabled: options.enabled ?? true,
  })

/**
 * Every goal, newest first, a page of 50 at a time. Stops offering another page once a page comes
 * back short, or adds no goal the earlier pages did not already hold.
 */
export function useGoalPages(options: QueryOptions = {}) {
  return useInfiniteQuery({
    queryKey: ['goals', 'pages'],
    queryFn: async ({ pageParam }) =>
      (await api<Goal[]>(`/api/goals?page=${pageParam}&size=${GOAL_PAGE_SIZE}`)).map(goalRow),
    initialPageParam: 0,
    getNextPageParam: (lastPage, allPages, lastPageParam) => {
      if (lastPage.length < GOAL_PAGE_SIZE) return undefined
      const earlier = new Set(allPages.slice(0, -1).flatMap((page) => page.map((goal) => goal.id)))
      return lastPage.some((goal) => !earlier.has(goal.id)) ? lastPageParam + 1 : undefined
    },
    select: uniqueGoals,
    refetchInterval: (query) =>
      query.state.data?.pages.some((page) => page.some(isGoalActive)) ? 5_000 : false,
    enabled: options.enabled ?? true,
  })
}

/** One goal and its tasks, for deep links and goals older than the loaded pages. Skipped without an id. */
export const useGoal = (id: string | null | undefined, options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['goals', id ?? ''],
    queryFn: async () => goalRow(await api<Goal>(`/api/goals/${id ?? ''}`)),
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
    queryFn: async () => {
      const params = new URLSearchParams({ status, mine: String(mine) })
      return (await api<RunQuestion[]>(`/api/orchestrator/questions?${params.toString()}`)).map(questionRow)
    },
    refetchInterval: 15_000,
    enabled: options.enabled ?? true,
  })
}

/** A run's own questions, refreshed every 3 seconds while `active` (default: the cached run's own state). */
export function useRunQuestions(runId: string, options: { active?: boolean } = {}) {
  return useQuery({
    queryKey: ['runs', runId, 'questions'],
    queryFn: async () => (await api<RunQuestion[]>(`/api/runs/${runId}/questions`)).map(questionRow),
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
      client.invalidateQueries({ queryKey: ['conversations'] })
      client.invalidateQueries({ queryKey: ['board'] })
      client.invalidateQueries({ queryKey: ['runs'] })
      client.invalidateQueries({ queryKey: ['goals'] })
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
    queryFn: async ({ pageParam }) => {
      const params = new URLSearchParams({ scope, page: String(pageParam), size: String(CONVERSATION_PAGE_SIZE) })
      if (q) params.set('q', q)
      return conversationPageRow(await api<ConversationPage>(`/api/conversations?${params.toString()}`))
    },
    initialPageParam: 0,
    getNextPageParam: (lastPage, _allPages, lastPageParam) => (lastPage.hasMore ? lastPageParam + 1 : undefined),
    refetchInterval: 15_000,
  })
}

/**
 * One conversation, its thread, its board-style goals and its questions. Polls through
 * conversationPollMs while something in it is still moving.
 */
export function useConversation(id: string | null | undefined, options: QueryOptions = {}) {
  return useQuery({
    queryKey: ['conversations', id ?? ''],
    queryFn: async () => conversationDetailRow(await api<ConversationDetail>(`/api/conversations/${id ?? ''}?limit=200`)),
    enabled: Boolean(id) && (options.enabled ?? true),
    refetchInterval: (query) => conversationPollMs(query.state.data),
  })
}

/** Earlier pages of a conversation's thread, loaded backwards from `before`. */
export function useEarlierMessages(id: string, before: number, options: { enabled?: boolean } = {}) {
  return useInfiniteQuery({
    queryKey: ['conversations', id, 'earlier'],
    queryFn: async ({ pageParam }) => {
      const page = await api<MessagesPage>(`/api/conversations/${id}/messages?before=${pageParam}&limit=100`)
      return { ...page, messages: page.messages.map(chatMessageRow) }
    },
    initialPageParam: before,
    getNextPageParam: (lastPage) => (lastPage.hasEarlier ? lastPage.messages[0]?.position : undefined),
    enabled: options.enabled ?? true,
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
    mutationFn: async (input: { text: string; agentIds?: string[] }) => {
      const result = await api<{ messages: ChatMessage[] }>(`/api/conversations/${conversationId}/messages`, {
        method: 'POST',
        body: input,
      })
      return result.messages.map(chatMessageRow)
    },
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['conversations', conversationId] })
      client.invalidateQueries({ queryKey: ['conversations'] })
      client.invalidateQueries({ queryKey: ['board'] })
    },
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
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['conversations', conversationId] })
      client.invalidateQueries({ queryKey: ['conversations'] })
      client.invalidateQueries({ queryKey: ['board'] })
    },
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
      client.invalidateQueries({ queryKey: ['conversations', conversationId] })
      client.invalidateQueries({ queryKey: ['board'] })
      client.invalidateQueries({ queryKey: ['goals'] })
      client.invalidateQueries({ queryKey: ['runs'] })
      client.invalidateQueries({ queryKey: ['approvals'] })
      client.invalidateQueries({ queryKey: ['questions'] })
    },
  })
}

/** Retries a chat-started goal from the thread. */
export function useRetryChatGoal(conversationId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { goalId: string }) =>
      api<GoalActionResult>(`/api/conversations/${conversationId}/goals/${input.goalId}/retry`, { method: 'POST' }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['conversations', conversationId] })
      client.invalidateQueries({ queryKey: ['board'] })
      client.invalidateQueries({ queryKey: ['goals'] })
      client.invalidateQueries({ queryKey: ['runs'] })
    },
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
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['conversations', conversationId] })
      client.invalidateQueries({ queryKey: ['board'] })
    },
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
    queryFn: async () => {
      const page = conversationPageRow(await api<ConversationPage>('/api/conversations?scope=all'))
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
    queryFn: async () => boardRow(await api<Board>(`/api/orchestrator/board?window=${boardWindow}`)),
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
      client.invalidateQueries({ queryKey: ['board'] })
      client.invalidateQueries({ queryKey: ['runs'] })
      client.invalidateQueries({ queryKey: ['goals'] })
      client.invalidateQueries({ queryKey: ['approvals'] })
      client.invalidateQueries({ queryKey: ['questions'] })
      client.invalidateQueries({ queryKey: ['conversations'] })
      client.invalidateQueries({ queryKey: ['schedules'] })
    },
  })
}

/** Pauses an agent (agent:update): its queued and future tasks stay pending, held rather than failed. */
export function usePauseAgent(id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async () => agentRow(await api<Agent>(`/api/agents/${id}/pause`, { method: 'POST' })),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['agents'] })
      client.invalidateQueries({ queryKey: ['board'] })
    },
  })
}

export function useResumeAgent(id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async () => agentRow(await api<Agent>(`/api/agents/${id}/resume`, { method: 'POST' })),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['agents'] })
      client.invalidateQueries({ queryKey: ['board'] })
    },
  })
}

/** Pauses or resumes one agent, the same endpoints usePauseAgent/useResumeAgent call, chosen by `action`. */
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
    queryFn: async () => (await api<Schedule[]>('/api/schedules')).map(scheduleRow),
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
    onSuccess: () => client.invalidateQueries({ queryKey: ['schedules'] }),
  })
}

export function useUpdateSchedule(id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { name?: string; agentId?: string; instruction?: string; text?: string }) =>
      scheduleRow(await api<Schedule>(`/api/schedules/${id}`, { method: 'PUT', body: input })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['schedules'] }),
  })
}

/** Shared by pause/resume/run-now: each just posts to its own path and refreshes the same queries. */
function useScheduleAction(id: string, action: 'pause' | 'resume' | 'run-now') {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async () => scheduleRow(await api<Schedule>(`/api/schedules/${id}/${action}`, { method: 'POST' })),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['schedules'] })
      client.invalidateQueries({ queryKey: ['board'] })
      client.invalidateQueries({ queryKey: ['goals'] })
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
    onSuccess: () => client.invalidateQueries({ queryKey: ['schedules'] }),
  })
}

/** A schedule's own run history: the goals it created, newest first (task:read). */
export const useScheduleRuns = (id: string, options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['schedules', id, 'runs'],
    queryFn: async () => (await api<Goal[]>(`/api/schedules/${id}/runs`)).map(goalRow),
    enabled: Boolean(id) && (options.enabled ?? true),
  })

/* ---- Voice --------------------------------------------------------------------------------------- */

/** Whether ElevenLabs is configured for this workspace, and today's quota (chat:use). */
export const useVoiceStatus = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['voice', 'status'],
    queryFn: () => api<VoiceStatus>('/api/voice/status'),
    enabled: options.enabled ?? true,
  })

/** The voices ElevenLabs offers. Empty without a stored key (chat:use). */
export const useVoices = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['voice', 'voices'],
    queryFn: () => api<Voice[]>('/api/voice/voices'),
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
    queryFn: () => api<Provider[]>('/api/providers'),
    enabled: options.enabled ?? true,
  })

export const useModels = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['providers', 'models'],
    queryFn: () => api<Model[]>('/api/providers/models'),
    enabled: options.enabled ?? true,
  })

export const useModelPolicy = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['model-policy'],
    queryFn: () => api<ModelPolicy>('/api/model-policy'),
    enabled: options.enabled ?? true,
  })

/** Which credentials are stored, without their values (provider:read). */
export const useCredentials = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['credentials'],
    queryFn: () => api<CredentialView[]>('/api/credentials'),
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
    queryFn: async () => (await api<Source[]>('/api/sources')).map(sourceRow),
    enabled: options.enabled ?? true,
  })

export const useSource = (id: string) =>
  useQuery({ queryKey: ['sources', id], queryFn: async () => sourceRow(await api<Source>(`/api/sources/${id}`)) })

export const useSourceDocuments = (id: string) =>
  useQuery({
    queryKey: ['sources', id, 'documents'],
    queryFn: async () => (await api<SourceDocument[]>(`/api/sources/${id}/documents`)).map(documentRow),
  })

export const useIntegrations = () =>
  useQuery({ queryKey: ['integrations'], queryFn: () => api<Integration[]>('/api/integrations') })

export const useMembers = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['members'],
    queryFn: () => api<Member[]>('/api/users'),
    enabled: options.enabled ?? true,
  })

export const useInvitations = (orgId: string, options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['invitations', orgId],
    queryFn: () => api<Invitation[]>(`/api/orgs/${orgId}/invitations`),
    enabled: Boolean(orgId) && (options.enabled ?? true),
  })

/** Every role with its permissions and holder count (role:read). */
export const useRoles = (options: QueryOptions = {}) =>
  useQuery({ queryKey: ['roles'], queryFn: () => api<Role[]>('/api/roles'), enabled: options.enabled ?? true })

/** Every permission code with its description (workspace:read, which every role holds). */
export const usePermissionCatalogue = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['roles', 'permissions'],
    queryFn: () => api<PermissionInfo[]>('/api/roles/permissions'),
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
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['goals'] })
      client.invalidateQueries({ queryKey: ['runs'] })
      client.invalidateQueries({ queryKey: ['approvals'] })
    },
  })
}

export function useDecideApproval() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { id: string; approved: boolean; note?: string }) =>
      api<DecisionResult>(`/api/approvals/${input.id}/decision`, {
        method: 'POST',
        body: { approved: input.approved, note: input.note ?? null },
      }),
    // Settled rather than success: a decision that failed because someone else already decided,
    // or the approval expired, must still clear the stale card.
    onSettled: () => {
      client.invalidateQueries({ queryKey: ['approvals'] })
      client.invalidateQueries({ queryKey: ['runs'] })
      client.invalidateQueries({ queryKey: ['goals'] })
      client.invalidateQueries({ queryKey: ['board'] })
      client.invalidateQueries({ queryKey: ['conversations'] })
    },
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
      client.invalidateQueries({ queryKey: ['runs'] })
      client.invalidateQueries({ queryKey: ['approvals'] })
      client.invalidateQueries({ queryKey: ['goals'] })
      client.invalidateQueries({ queryKey: ['board'] })
      client.invalidateQueries({ queryKey: ['conversations'] })
      client.invalidateQueries({ queryKey: ['questions'] })
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

export function useUploadDocument(sourceId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return api<IngestResult>(`/api/sources/${sourceId}/documents`, { method: 'POST', form })
    },
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['sources'] })
    },
  })
}

export function useSearch() {
  return useMutation({
    mutationFn: async (query: string) => {
      const result = await api<{ passages: Passage[]; grounded: boolean }>('/api/knowledge/search', {
        method: 'POST',
        body: { query, limit: 4 },
      })
      return { ...result, passages: result.passages.map(passageRow) }
    },
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
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['runs'] })
      client.invalidateQueries({ queryKey: ['approvals'] })
      client.invalidateQueries({ queryKey: ['goals'] })
    },
  })
}

export function useCancelGoal(goalId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async () => goalRow(await api<Goal>(`/api/goals/${goalId}/cancel`, { method: 'POST' })),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['goals'] })
      client.invalidateQueries({ queryKey: ['runs'] })
      client.invalidateQueries({ queryKey: ['approvals'] })
      client.invalidateQueries({ queryKey: ['board'] })
      client.invalidateQueries({ queryKey: ['conversations'] })
      client.invalidateQueries({ queryKey: ['questions'] })
    },
  })
}

/** Tries a failed or stopped goal again, from the step that did not finish (D-7: requester or task:cancel). */
export function useRetryGoal() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (goalId: string) => goalRow(await api<Goal>(`/api/goals/${goalId}/retry`, { method: 'POST' })),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['board'] })
      client.invalidateQueries({ queryKey: ['goals'] })
      client.invalidateQueries({ queryKey: ['runs'] })
      client.invalidateQueries({ queryKey: ['conversations'] })
    },
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

export function useUpdateRole(id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { name: string; description: string; permissions: string[] }) =>
      api<Role>(`/api/roles/${id}`, { method: 'PUT', body: input }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['roles'] }),
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

export function useDeleteRole(id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api<void>(`/api/roles/${id}`, { method: 'DELETE' }),
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
  outcome: 'succeeded' | 'failed' | 'denied'
  detail: Record<string, unknown>
  occurredAt: string
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
    queryFn: () => api<AnalyticsSummary>('/api/analytics'),
  })
}

export function useAudit() {
  return useQuery({
    queryKey: ['audit'],
    queryFn: () => api<AuditEvent[]>('/api/audit?size=50'),
  })
}

/** The whole audit log, newest first, a page of 100 at a time. */
export function useAuditPages() {
  return useInfiniteQuery({
    queryKey: ['audit', 'pages'],
    queryFn: ({ pageParam }) => api<AuditEvent[]>(`/api/audit?page=${pageParam}&size=${AUDIT_PAGE_SIZE}`),
    initialPageParam: 0,
    getNextPageParam: (lastPage, _allPages, lastPageParam) =>
      lastPage.length >= AUDIT_PAGE_SIZE ? lastPageParam + 1 : undefined,
    select: uniqueAuditEvents,
  })
}


export function useAuditPagesServer(filter: {
  search?: string;
  outcome?: AuditOutcome[];
  page: number;
  pageSize: number;
}) {
  const params = new URLSearchParams();
  if (filter.search) params.set('search', filter.search);
  if (filter.outcome?.length) params.set('outcome', filter.outcome.join(','));
  params.set('page', String(filter.page));
  params.set('pageSize', String(filter.pageSize));
  return useQuery({
    queryKey: ['audit', 'server', filter],
    queryFn: () => api<AuditEvent[]>(`/api/audit?${params.toString()}`),
    staleTime: 30_000,
  });
}

