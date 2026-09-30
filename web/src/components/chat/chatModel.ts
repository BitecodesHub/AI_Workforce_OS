import { toolLabel } from '../../lib/labels'
import { formatElapsed, formatMoney, nameList } from '../../lib/format'
import { canRetryGoal, canStopGoal } from '../../lib/goals'
import { isGoalActive } from '../../lib/queries'
import type {
  BoardGoal,
  ChatMessage,
  ChatMessageDetail,
  Goal,
  Member,
  QuestionAnswerItem,
  RunQuestion,
  RunStep,
  Task,
} from '../../lib/queries'

/*
 * The pure rules behind the chat thread: which messages belong together, what a goal's chain of
 * tasks is doing, which agent replies are new since the last render, and (from the version 2
 * redesign) how the composer decides what it is answering, what a routing or progress card's
 * one-line summary says, and how the welcome screen's suggestions are built. Nothing here touches
 * the DOM or React state, so Chat.tsx and the cards in this folder can stay thin, and these rules
 * can be tested without a signed-in screen harness (see lib/voice.test.ts's own note on why the
 * project prefers that).
 */

/* ---- Grouping messages into a thread ------------------------------------------------------- */

/**
 * Whether `message` should sit close to the one before it rather than with its own full gap: the
 * same author, one bubble kind (a person's text or an agent's answer), immediately following.
 * Anything else - a routing receipt, a card, a different author - always opens its own block.
 */
export function isGroupedWithPrevious(message: ChatMessage, previous: ChatMessage | undefined): boolean {
  if (!previous) return false
  if (message.kind !== previous.kind) return false
  if (message.kind !== 'text' && message.kind !== 'answer') return false
  return message.authorKind === previous.authorKind && message.agentId === previous.agentId
}

/** Every message paired with whether it groups with the one before it, in one pass. */
export function groupMessages(messages: readonly ChatMessage[]): Array<{ message: ChatMessage; grouped: boolean }> {
  return messages.map((message, index) => ({ message, grouped: isGroupedWithPrevious(message, messages[index - 1]) }))
}

/* ---- What is new since the last render ------------------------------------------------------ */

/**
 * The ids in `current` that were not in `previous`, optionally narrowed to one message kind.
 * Used both to announce new agent replies to a screen reader and to read them aloud once.
 */
export function newMessageIds(
  previous: readonly ChatMessage[] | undefined,
  current: readonly ChatMessage[],
  kind?: ChatMessage['kind'],
): string[] {
  const seen = new Set((previous ?? []).map((message) => message.id))
  return current.filter((message) => !seen.has(message.id) && (!kind || message.kind === kind)).map((message) => message.id)
}

/** How long a new answer waits for its agent's name before the announcement gives up and uses a generic one. */
export const ANSWER_NAME_TIMEOUT_MS = 8_000

/**
 * Splits freshly-arrived answers into those ready to announce and those still waiting on their
 * agent's name.
 *
 * An answer names its agent only by id; the name itself comes from a separate query
 * (useAgentNames) that can still be loading when the answer polls in. Announcing "an agent" in
 * that instant, and never revisiting it, would leave an assistive-technology live region
 * permanently wrong once the name does arrive a moment later - so a caller keeps re-offering the
 * same still-new id here on every render until its name resolves.
 *
 * `unresolvedSince` is the caller's own memory of when each id was first seen unresolved (a ref
 * held across renders): this function adds to it the first time an id is unresolved, and removes
 * an id as soon as it is returned as ready, whether that is because the name arrived or because
 * `giveUpAfterMs` passed - a name that never arrives (the agent was genuinely removed) must not
 * leave that reply permanently unannounced.
 */
export function readyAnswers(
  fresh: readonly ChatMessage[],
  hasAgentName: (agentId: string) => boolean,
  unresolvedSince: Map<string, number>,
  now: number,
  giveUpAfterMs = ANSWER_NAME_TIMEOUT_MS,
): { ready: ChatMessage[]; pending: boolean } {
  const stillUnresolved = fresh.filter((message) => {
    if (!message.agentId || hasAgentName(message.agentId)) return false
    const firstSeen = unresolvedSince.get(message.id) ?? now
    unresolvedSince.set(message.id, firstSeen)
    return now - firstSeen < giveUpAfterMs
  })
  if (stillUnresolved.length > 0) return { ready: [], pending: true }
  for (const message of fresh) unresolvedSince.delete(message.id)
  return { ready: [...fresh], pending: false }
}

/* ---- Reading a goal's chain ------------------------------------------------------------------ */

export type TaskStepStatus =
  | 'queued'
  | 'working'
  | 'waiting_approval'
  | 'waiting_input'
  | 'done'
  | 'failed'
  | 'cancelled'
  | 'skipped'

/** A task's place in the chain, in the words the progress card groups its tags by. */
export function taskStepStatus(task: Task): TaskStepStatus {
  switch (task.status) {
    case 'pending':
    case 'ready':
      return 'queued'
    case 'running':
      return 'working'
    case 'waiting_approval':
      return 'waiting_approval'
    case 'waiting_input':
      return 'waiting_input'
    case 'completed':
      return 'done'
    case 'failed':
      return 'failed'
    case 'cancelled':
      return 'cancelled'
    case 'skipped':
      return 'skipped'
    default:
      return 'queued'
  }
}

const ACTIVE_STEP_STATUSES = new Set<TaskStepStatus>(['queued', 'working', 'waiting_approval', 'waiting_input'])

/**
 * The task the chain is on right now: the first one running or parked for a person (an approval
 * or a question), else the first still queued, else null once every task has reached an end
 * state. Assumes `tasks` is already in the goal's own order (position), as every goal the
 * platform returns is.
 */
export function currentTaskIndex(tasks: readonly Task[]): number {
  const active = tasks.findIndex(
    (task) => task.status === 'running' || task.status === 'waiting_approval' || task.status === 'waiting_input',
  )
  if (active !== -1) return active
  return tasks.findIndex((task) => task.status === 'pending' || task.status === 'ready')
}

/** Whether a goal's own chain still has any moving part: at least one task not at an end state. */
export function goalChainActive(goal: Pick<Goal, 'tasks'>): boolean {
  return goal.tasks.some((task) => ACTIVE_STEP_STATUSES.has(taskStepStatus(task)))
}

/* ---- Reading a routing receipt ---------------------------------------------------------------- */

/** How a routing message explains itself, in the words the receipt card opens with. */
export function routingModeLabel(mode: ChatMessageDetail['mode']): string {
  switch (mode) {
    case 'mention':
      return 'You mentioned'
    case 'model':
      return 'Chosen by the model'
    case 'rules':
      return 'Matched by keywords'
    case 'manual':
      return 'You chose'
    case 'fallback':
      return 'No specialist matched'
    default:
      return 'Routed'
  }
}

/** The collapsed routing card's one line, naming who is on it and how they were chosen. */
export function routingSummary(message: ChatMessage, agentNames: Record<string, { name: string }>): string {
  const base = routingLine(message, agentNames)
  // When the answer draws on the workspace's documents, say which, on the line itself.
  const sources = /^Found (\d+) passages? in (.+?)\. /.exec(message.detail.reason ?? '')
  if (!sources) return base
  const count = Number(sources[1])
  return `${base}, using ${count} ${count === 1 ? 'passage' : 'passages'} from ${sources[2]}`
}

function routingLine(message: ChatMessage, agentNames: Record<string, { name: string }>): string {
  const detail = message.detail
  const names = (detail.agents ?? []).map((agent) => agentNames[agent.id]?.name ?? agent.name)
  switch (detail.mode) {
    case 'mention':
      return names.length > 0 ? `Sent to ${names.map((name) => `@${name}`).join(' and ')}, as you asked` : 'Sent to the agent you mentioned'
    case 'manual':
      return names.length > 0 ? `You chose ${nameList(names)}` : 'You chose an agent'
    case 'fallback':
      return `Sent to ${names[0] ?? 'General Employee'}, as no specialist matched`
    default:
      return names.length > 0 ? `Sent to ${nameList(names)}` : 'Sent to the workforce'
  }
}

/* ---- Reading a goal's progress ------------------------------------------------------------------ */

/** The progress card's collapsed summary, once a goal has left the running state. */
export function progressSummary(goal: BoardGoal): string {
  const steps = goal.tasks.length
  const cost = goal.tasks.reduce((total, task) => total + (task.cost ?? 0), 0)
  const stepWord = steps === 1 ? 'step' : 'steps'
  if (goal.status === 'completed') {
    return `Done in ${formatElapsed(goal.createdAt, goal.completedAt)} · ${steps} ${stepWord} · ${formatMoney(cost)}`
  }
  if (goal.status === 'failed') {
    const failedIndex = goal.tasks.findIndex((task) => task.status === 'failed')
    const position = failedIndex === -1 ? steps : failedIndex + 1
    return `Failed at step ${position} of ${steps} · ${formatElapsed(goal.createdAt, goal.completedAt)}`
  }
  if (goal.status === 'cancelled') {
    return `Stopped · ${formatElapsed(goal.createdAt, goal.completedAt)}`
  }
  return `Running ${formatElapsed(goal.createdAt, null)}`
}

/** The name of the agent a later routing message rerouted `messageId` to, or null if none has. */
export function choiceMadeFor(messageId: string, messages: readonly ChatMessage[]): string | null {
  const later = messages.find((message) => message.detail.rerouteOf === messageId)
  const agent = later?.detail.agents?.[0]
  return agent?.name ?? null
}

/** The routing message that started `goalId`, for "Ask again" and the Orchestrator's deep link. */
export function routingMessageForGoal(goalId: string, messages: readonly ChatMessage[]): ChatMessage | undefined {
  return messages.find((message) => message.kind === 'routing' && message.goalId === goalId)
}

/** Whether a goal already has an answer message in the thread. */
export function goalHasAnswer(goalId: string, messages: readonly ChatMessage[]): boolean {
  return messages.some((message) => message.kind === 'answer' && message.goalId === goalId)
}

/** The most recent answer message in the thread, if any. */
export function lastAnswer(messages: readonly ChatMessage[]): ChatMessage | undefined {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index]!
    if (message.kind === 'answer') return message
  }
  return undefined
}

/* ---- Day dividers ------------------------------------------------------------------------------- */

export type ThreadEntry =
  | { type: 'day'; key: string; label: string }
  | { type: 'message'; message: ChatMessage; grouped: boolean }

/** 'Today', 'Yesterday', 'Monday 22 September' (this year), or '22 September 2025' (another year). */
export function dayLabel(date: Date, now: Date): string {
  const oneDay = 24 * 60 * 60 * 1000
  const dateOnly = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
  const nowOnly = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const diffDays = Math.round((nowOnly - dateOnly) / oneDay)
  if (diffDays === 0) return 'Today'
  if (diffDays === 1) return 'Yesterday'
  const day = date.getDate()
  const month = date.toLocaleDateString('en-AU', { month: 'long' })
  if (date.getFullYear() === now.getFullYear()) {
    const weekday = date.toLocaleDateString('en-AU', { weekday: 'long' })
    return `${weekday} ${day} ${month}`
  }
  return `${day} ${month} ${date.getFullYear()}`
}

/** The thread with a day divider inserted before the first message of every calendar day. */
export function withDayDividers(messages: readonly ChatMessage[], now: Date): ThreadEntry[] {
  const entries: ThreadEntry[] = []
  let previous: ChatMessage | undefined
  let lastDayKey: string | null = null
  for (const message of messages) {
    const date = new Date(message.createdAt)
    const dayKey = date.toDateString()
    if (dayKey !== lastDayKey) {
      entries.push({ type: 'day', key: dayKey, label: dayLabel(date, now) })
      lastDayKey = dayKey
    }
    entries.push({ type: 'message', message, grouped: isGroupedWithPrevious(message, previous) })
    previous = message
  }
  return entries
}

/* ---- Questions --------------------------------------------------------------------------------- */

/** Pending questions with no `question` message of their own yet, rendered at the end of the thread. */
export function orphanQuestions(questions: readonly RunQuestion[], messages: readonly ChatMessage[]): RunQuestion[] {
  const withMessage = new Set(messages.map((message) => message.detail.questionId).filter((id): id is string => Boolean(id)))
  return questions.filter((question) => question.status === 'pending' && !withMessage.has(question.id))
}

/** The newest question the composer should answer automatically, or null (B1.7, D-14). */
export function autoAnswerTarget(
  questions: readonly RunQuestion[],
  messages: readonly ChatMessage[],
  _me: string | null,
  dismissedIds: ReadonlySet<string>,
): RunQuestion | null {
  const relevant = messages.filter((message) => message.kind !== 'notice')
  const lastRelevant = relevant[relevant.length - 1]
  const candidates = [...questions]
    .filter((question) => question.status === 'pending' && question.canAnswer && !dismissedIds.has(question.id))
    .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))

  for (const question of candidates) {
    const ownMessage = relevant.find((message) => message.detail.questionId === question.id)
    if (ownMessage) {
      if (ownMessage.id === lastRelevant?.id) return question
      continue
    }
    // Orphan: nothing relevant has happened in the thread since it was asked.
    const somethingAfter = relevant.some((message) => Date.parse(message.createdAt) > Date.parse(question.createdAt))
    if (!somethingAfter) return question
  }
  return null
}

/** Maps composer text to an answer, per D-14's rules for one question versus several. */
export function composerAnswer(q: RunQuestion, text: string): { answers: QuestionAnswerItem[]; note?: string } {
  const trimmed = text.trim()
  if (q.questions.length !== 1) return { answers: [], note: trimmed }

  const item = q.questions[0]!
  const lower = trimmed.toLowerCase()
  const byLabel = (label: string) => item.options.find((option) => option.label.toLowerCase() === label)

  if (item.multiSelect) {
    const parts = trimmed
      .split(',')
      .map((part) => part.trim())
      .filter(Boolean)
    const resolved = parts.map((part) => {
      const asNumber = Number(part)
      if (Number.isInteger(asNumber) && asNumber >= 1 && asNumber <= item.options.length) {
        return item.options[asNumber - 1]!.label
      }
      return byLabel(part.toLowerCase())?.label ?? null
    })
    if (resolved.every((value): value is string => value !== null) && resolved.length > 0) {
      return { answers: [{ questionId: item.id, selected: resolved, other: null }] }
    }
    return { answers: [{ questionId: item.id, selected: [], other: trimmed }] }
  }

  const exact = byLabel(lower)
  if (exact) return { answers: [{ questionId: item.id, selected: [exact.label], other: null }] }

  const asNumber = Number(trimmed)
  if (Number.isInteger(asNumber) && asNumber >= 1 && asNumber <= item.options.length) {
    return { answers: [{ questionId: item.id, selected: [item.options[asNumber - 1]!.label], other: null }] }
  }

  return { answers: [{ questionId: item.id, selected: [], other: trimmed }] }
}

/* ---- Goals -------------------------------------------------------------------------------------- */

/** The goals whose chain can still change: planning, running or waiting. */
export function activeGoals(goals: readonly BoardGoal[]): BoardGoal[] {
  return goals.filter(isGoalActive)
}

export { canRetryGoal, canStopGoal }

/* ---- Live step text ------------------------------------------------------------------------------ */

/** One line for the step a run is on right now, for the work strip and the Work panel. */
export function liveStepText(step: RunStep | undefined): string {
  if (!step) return 'Starting'
  switch (step.kind) {
    case 'model_call':
      return 'Working on a reply'
    case 'tool_call': {
      const tool = typeof step.detail.tool === 'string' ? step.detail.tool : undefined
      return `Last step: ${toolLabel(tool)}`
    }
    case 'question':
      return 'Waiting for an answer'
    case 'approval':
      return 'Waiting for approval'
    case 'handoff': {
      const from = typeof step.detail.fromAgentName === 'string' ? step.detail.fromAgentName : 'an earlier step'
      return `Picking up from ${from}`
    }
    default:
      return 'Starting'
  }
}

/* ---- Error help ---------------------------------------------------------------------------------- */

const NO_MODEL_CODES = new Set(['no_model_available', 'provider_credential_invalid', 'provider_not_configured'])

/** A help sentence and, where one exists, a link, for an error card's failure reason code. */
export function errorHelp(code?: string | null): { text: string; href: string | null } | null {
  if (!code) return null
  if (NO_MODEL_CODES.has(code)) {
    return { text: 'Check the model routing and provider credentials in Model routing.', href: '/routing' }
  }
  if (code === 'budget_exceeded') {
    return { text: 'The workspace budget for model spend is used up. An administrator can raise it.', href: null }
  }
  return null
}

/* ---- Authorship and suggestions ------------------------------------------------------------------- */

/** Who a message reads as coming from: "You" for the viewer, else the member's own name. */
export function messageAuthor(
  message: ChatMessage,
  me: string | null,
  memberNames: Record<string, Member>,
): { isMe: boolean; name: string } {
  const isMe = me != null && message.authorId === me
  if (isMe) return { isMe: true, name: 'You' }
  const name = (message.authorId && memberNames[message.authorId]?.displayName) || 'Someone in the workspace'
  return { isMe: false, name }
}

type ChipAgent = { id: string; key: string; name: string; category: string; status: string; fallback?: boolean }

const CHIP_BY_KEY: Record<string, string> = {
  hr: 'Draft a welcome email for a new starter',
  'engineering-manager': 'Summarise open pull requests for the standup note',
  research: 'Compare our top three competitors in a one-page note',
  support: 'Draft a reply to the newest support ticket',
}

const CHIP_BY_CATEGORY: Record<string, string> = {
  operations: 'Plan next week’s team roster',
  engineering: 'Summarise what changed in the code this week',
  growth: 'Outline a short market update for the team',
  support: 'Draft a holding reply to a customer',
}

const GENERAL_CHIP = 'Plan a 30-minute team meeting about next month’s rosters'
const SCHEDULE_CHIP = 'Every weekday at 9am, summarise new support tickets'

const DOCUMENT_ONLY_CHIPS: readonly string[] = [
  'What does our leave policy say about carers’ leave?',
  'Where is the checklist for a new starter’s first week?',
  'Which documents cover incident reporting?',
]

/** The welcome screen's suggestions: built from the active agents and what the viewer may do (D-11). */
export function suggestionChips(agents: readonly ChipAgent[], can: (code: string) => boolean): string[] {
  if (!can('task:create')) return [...DOCUMENT_ONLY_CHIPS]

  const active = agents.filter((agent) => agent.status === 'active')
  const chips: string[] = []
  const seen = new Set<string>()
  const add = (chip: string | undefined) => {
    if (chip && !seen.has(chip)) {
      seen.add(chip)
      chips.push(chip)
    }
  }

  for (const agent of active) {
    if (agent.fallback) continue
    add(CHIP_BY_KEY[agent.key] ?? CHIP_BY_CATEGORY[agent.category])
    if (chips.length >= 4) break
  }
  if (chips.length < 4 && active.some((agent) => agent.fallback)) add(GENERAL_CHIP)
  if (chips.length < 4 && active.some((agent) => !agent.fallback && agent.category === 'support')) add(SCHEDULE_CHIP)

  return chips.slice(0, 4)
}

/** "Copy conversation" as plain text: "You: …", each card reduced to one line. */
export function conversationText(messages: readonly ChatMessage[], agentNames: Record<string, { name: string }>): string {
  const lines: string[] = []
  for (const message of messages) {
    if (message.kind === 'text') {
      const who = message.authorKind === 'user' ? 'You' : 'Someone in the workspace'
      lines.push(`${who}: ${message.content}`)
    } else if (message.kind === 'answer') {
      const name = message.agentId ? (agentNames[message.agentId]?.name ?? 'An agent') : 'An agent'
      lines.push(`${name}: ${message.content}`)
    } else if (message.kind === 'routing') {
      lines.push(routingSummary(message, agentNames))
    } else if (message.kind === 'error') {
      lines.push(`Error: ${message.content}`)
    } else if (message.kind === 'notice') {
      lines.push(message.content)
    }
  }
  return lines.join('\n\n')
}

