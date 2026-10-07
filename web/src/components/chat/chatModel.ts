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
  Passage,
  QuestionAnswerItem,
  RunQuestion,
  RunStep,
  Task,
} from '../../lib/queries'

/*
 * The pure rules behind the chat thread: which messages belong together, what a goal's chain of
 * tasks is doing, which agent replies are new since the last render, and (from the version 2
 * redesign) how the composer decides what it is answering, what a routing or progress card's
 * one-line summary says. Nothing here touches
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
  const passages = routingPassages(message)
  if (passages.length > 0) {
    const titles = [...new Set(passages.map((passage) => passage.documentTitle))].slice(0, 3).join(', ')
    return `${base}, using ${passages.length} ${passages.length === 1 ? 'passage' : 'passages'} from ${titles}`
  }
  // A message from before the passages were recorded says so only in its reason sentence.
  const sources = /^Found (\d+) passages? in (.+?)\. /.exec(message.detail.reason ?? '')
  if (!sources) return base
  const count = Number(sources[1])
  return `${base}, using ${count} ${count === 1 ? 'passage' : 'passages'} from ${sources[2]}`
}

/* ---- Sources: the passages an agent was given ---------------------------------------------------- */

/**
 * A passage as a routing message records it. `sourceId` names the knowledge source holding its
 * document, for a link to it; messages made before the knowledge service sent it do not have one.
 */
export type SourcePassage = Passage & { sourceId?: string | null }

/** The passages a routing message says its agent was given, in the order it read them; none when it records none. */
export function routingPassages(message: ChatMessage | undefined): SourcePassage[] {
  const passages: SourcePassage[] | undefined = message?.detail.passages
  return Array.isArray(passages) ? passages : []
}

/**
 * The sources to show under an answer: the passages its goal's first step was given. A later step
 * of a chain was handed the earlier work and the titles of those passages, not the passages, so
 * its answer is not shown them as though it had read them. `goal` says which step an answer is
 * when it is known; without it a routing message naming one agent means there is only one.
 */
export function sourcesForAnswer(
  answer: ChatMessage,
  messages: readonly ChatMessage[],
  goal?: Pick<BoardGoal, 'tasks'>,
): SourcePassage[] {
  if (answer.kind !== 'answer' || !answer.goalId) return []
  const routing = routingMessageForGoal(answer.goalId, messages)
  const passages = routingPassages(routing)
  if (passages.length === 0) return []
  const taskId = answer.detail.taskId
  if (goal && taskId) {
    const first = [...goal.tasks].sort((a, b) => a.position - b.position)[0]
    return first?.id === taskId ? passages : []
  }
  return (routing?.detail.agents?.length ?? 0) <= 1 ? passages : []
}

/**
 * Where a passage's link goes: its source in Knowledge when the person may open Knowledge, else
 * its own web address when it has one, else nowhere.
 */
export function passageLink(
  passage: SourcePassage,
  mayReadKnowledge: boolean,
): { href: string; label: string; external: boolean } | null {
  if (passage.sourceId && mayReadKnowledge) {
    return { href: `/knowledge/${encodeURIComponent(passage.sourceId)}`, label: 'Open in Knowledge', external: false }
  }
  if (passage.uri && /^https?:\/\//i.test(passage.uri)) return { href: passage.uri, label: 'Open source', external: true }
  return null
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

/**
 * The progress card's collapsed summary: how a finished goal ended, or, while it is still open,
 * whether it is parked on a person (an approval or an answer) or simply running. Folding a card
 * that is waiting on someone must not hide that it is.
 */
export function progressSummary(goal: BoardGoal, showCost = true): string {
  const steps = goal.tasks.length
  const cost = goal.tasks.reduce((total, task) => total + (task.cost ?? 0), 0)
  const stepWord = steps === 1 ? 'step' : 'steps'
  if (goal.status === 'completed') {
    const base = `Done in ${formatElapsed(goal.createdAt, goal.completedAt)} · ${steps} ${stepWord}`
    return showCost ? `${base} · ${formatMoney(cost)}` : base
  }
  if (goal.status === 'failed') {
    const failedIndex = goal.tasks.findIndex((task) => task.status === 'failed')
    const position = failedIndex === -1 ? steps : failedIndex + 1
    return `Failed at step ${position} of ${steps} · ${formatElapsed(goal.createdAt, goal.completedAt)}`
  }
  if (goal.status === 'cancelled') {
    return `Stopped · ${formatElapsed(goal.createdAt, goal.completedAt)}`
  }
  if (goal.tasks.some((task) => task.status === 'waiting_approval')) return 'Waiting for your approval'
  if (goal.tasks.some((task) => task.status === 'waiting_input')) return 'Waiting for an answer'
  return `Running ${formatElapsed(goal.createdAt, null)}`
}

/** The name of the agent a later routing message rerouted `messageId` to, or null if none has. */
export function choiceMadeFor(messageId: string, messages: readonly ChatMessage[]): string | null {
  const later = messages.find((message) => message.detail.rerouteOf === messageId)
  const agent = later?.detail.agents?.[0]
  return agent?.name ?? null
}

/** The reason a routing message gives, as the end of "because ...": lower-cased start, no final full stop. */
export function becauseText(reason: string): string {
  const clean = reason.replace(/\s+/g, ' ').trim().replace(/[.\s]+$/, '')
  if (clean.length > 1 && /[A-Z]/.test(clean[0]!) && !/[A-Z]/.test(clean[1]!)) return clean[0]!.toLowerCase() + clean.slice(1)
  return clean
}

/** The routing message that started `goalId`, for "Ask again" and the Orchestrator's deep link. */
export function routingMessageForGoal(goalId: string, messages: readonly ChatMessage[]): ChatMessage | undefined {
  return messages.find((message) => message.kind === 'routing' && message.goalId === goalId)
}

/** The progress message that tracks `goalId` in the thread, the anchor its "Review" link scrolls to. */
export function progressMessageForGoal(goalId: string, messages: readonly ChatMessage[]): ChatMessage | undefined {
  return messages.find((message) => message.kind === 'progress' && message.goalId === goalId)
}

/**
 * Where a link to a goal should take the person: its progress card in the thread when that
 * message is loaded (`elementId`, the message's own `m-` anchor), otherwise the screen that shows
 * the same decision - the approvals queue for an approval, the Orchestrator for anything else -
 * so the link never does nothing.
 */
export function goalTarget(
  goalId: string,
  messages: readonly ChatMessage[],
  reason: 'approval' | 'progress',
): { elementId: string } | { href: string } {
  const progress = progressMessageForGoal(goalId, messages)
  if (progress) return { elementId: `m-${progress.id}` }
  return { href: reason === 'approval' ? '/approvals' : `/orchestrator?goal=${goalId}` }
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

/**
 * Whether a message written while a question is targeted goes out as a new request instead of the
 * answer. Only an automatic target gives way, and only to a mention of some other agent: "@Sales
 * draft the Q3 quote" typed under Research's question is plainly meant for Sales. Mentioning the
 * agent that asked still answers it, and so does anything sent after the person chose "Reply in
 * own words" (`auto: false`).
 */
export function sendsAsNewRequest(
  replyTo: { auto: boolean; agentId: string | null } | null,
  agentIds: readonly string[],
): boolean {
  if (!replyTo?.auto) return false
  return agentIds.some((id) => id !== replyTo.agentId)
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

/* ---- The message being sent ---------------------------------------------------------------------- */

/** The newest position in the thread, or -1 for an empty one: what a send waits to see exceeded. */
export function newestPosition(messages: readonly ChatMessage[]): number {
  return messages.reduce((highest, message) => Math.max(highest, message.position), -1)
}

/**
 * Whether the server has already stored the message that is still being sent, so the thread
 * shows it and the pending copy of the bubble would be a duplicate. The coordinator saves the
 * person's message before it spends up to half a minute deciding who takes it, and any poll in
 * that time brings the saved one back. Matched by author, by text and by a position after the
 * newest one when the send started, never by clock time.
 */
export function pendingEchoed(
  messages: readonly ChatMessage[],
  pending: { text: string; afterPosition: number },
  me: string | null,
): boolean {
  const text = pending.text.trim()
  return messages.some(
    (message) =>
      message.kind === 'text' &&
      message.authorKind === 'user' &&
      message.authorId === me &&
      message.position > pending.afterPosition &&
      message.content.trim() === text,
  )
}

/* ---- Trying a failed request again --------------------------------------------------------------- */

/**
 * The person's own request behind an error message, for "Try again" to put back in the box: the
 * text the error itself recorded, else the request the goal's routing message recorded, else the
 * nearest earlier message the person wrote. Never the error's own sentence, which would be sent
 * to an agent as a new request; null when no request can be found, and the button is hidden.
 */
export function resendText(error: ChatMessage, messages: readonly ChatMessage[]): string | null {
  let nearest: ChatMessage | undefined
  for (const message of messages) {
    if (message.position >= error.position) continue
    if (message.kind !== 'text' || message.authorKind !== 'user' || !message.content.trim()) continue
    if (!nearest || message.position > nearest.position) nearest = message
  }
  const candidates = [
    error.detail.requestText,
    error.goalId ? routingMessageForGoal(error.goalId, messages)?.detail.requestText : undefined,
    nearest?.content,
  ]
  const errorSentence = error.content.trim()
  for (const candidate of candidates) {
    const text = candidate?.trim()
    if (text && text !== errorSentence) return text
  }
  return null
}

/* ---- Drafts ------------------------------------------------------------------------------------ */

/**
 * Where the composer keeps an unsent draft: per person as well as per conversation, so somebody
 * signing in after someone else on the same browser never sees that person's half-written text.
 */
export function draftKey(userId: string, conversationId: string | null): string {
  return `chat.draft.${userId}.${conversationId ?? 'new'}`
}

/** The draft keys written before drafts were kept per person: `chat.draft.<conversation or new>`. */
export function isLegacyDraftKey(key: string): boolean {
  return key.startsWith('chat.draft.') && !key.slice('chat.draft.'.length).includes('.')
}

/** Removes every draft kept before drafts were per person, which anyone on the browser could see. */
export function removeLegacyDrafts(storage: Pick<Storage, 'length' | 'key' | 'removeItem'>): void {
  const stale: string[] = []
  for (let index = 0; index < storage.length; index += 1) {
    const key = storage.key(index)
    if (key && isLegacyDraftKey(key)) stale.push(key)
  }
  for (const key of stale) storage.removeItem(key)
}

/* ---- Goals -------------------------------------------------------------------------------------- */

/** The goals whose chain can still change: planning, running or waiting. */
export function activeGoals(goals: readonly BoardGoal[]): BoardGoal[] {
  return goals.filter(isGoalActive)
}

export { canRetryGoal, canStopGoal }

/* ---- The work strip ------------------------------------------------------------------------------- */

/**
 * The narrow work strip's one line: what waits on a person first, since that is what blocks the
 * work, else how many pieces are running. `approvals` and `answers` count goals whose current task
 * is parked that way.
 */
export function workStripSummary(goals: readonly BoardGoal[]): { text: string; approvals: number; answers: number } {
  let approvals = 0
  let answers = 0
  for (const goal of goals) {
    const index = currentTaskIndex(goal.tasks)
    const status = index === -1 ? undefined : goal.tasks[index]!.status
    if (status === 'waiting_approval') approvals += 1
    else if (status === 'waiting_input') answers += 1
  }
  const need = (count: number) => (count === 1 ? 'needs' : 'need')
  const text =
    approvals > 0
      ? `${approvals} ${need(approvals)} your approval`
      : answers > 0
        ? `${answers} ${need(answers)} your answer`
        : `${goals.length} running`
  return { text, approvals, answers }
}

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
    return {
      text: 'The workspace budget for model spend is used up. An administrator can raise it.',
      href: '/analytics#budget',
    }
  }
  if (code === 'dependency_unavailable') {
    return { text: 'A platform service was briefly unreachable. Try again in a minute.', href: null }
  }
  return null
}

/* ---- Authorship --------------------------------------------------------------------------------- */

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


/*
 * What the welcome screen says an agent does. An agent's summary is often its own instructions,
 * written to it in the second person ("You triage support tickets..."), which reads oddly to the
 * person choosing it. A leading "You <verb>" becomes "<Verbs>"; "You are the X: you <verb>..."
 * keeps the part after the colon the same way. Anything still addressed to "you", or empty, falls
 * back to `fallback` (the agent's category).
 */
const IRREGULAR_VERBS: Record<string, string> = { have: 'has', do: 'does', go: 'goes', be: 'is' }

function thirdPerson(verb: string): string {
  const lower = verb.toLowerCase()
  const irregular = IRREGULAR_VERBS[lower]
  if (irregular) return irregular
  if (/(s|x|z|ch|sh|o)$/.test(lower)) return `${lower}es`
  if (/[^aeiou]y$/.test(lower)) return `${lower.slice(0, -1)}ies`
  return `${lower}s`
}

/* Verbs an agent's instructions commonly list after the first ("You triage tickets and draft
   replies"): one of these straight after "and" or a comma is conjugated too. */
const LISTED_VERBS = new Set(
  (
    'analyse analyze answer build check collect compile create draft escalate explain find flag gather handle keep manage ' +
    'monitor organise organize prepare propose reply research review route schedule send suggest summarise summarize take ' +
    'track triage update write'
  ).split(' '),
)

function conjugateListedVerbs(text: string): string {
  return text.replace(/(,\s*(?:and\s+)?|\s+and\s+)([a-z]+)\b/g, (match, joiner: string, word: string) =>
    LISTED_VERBS.has(word) ? `${joiner}${thirdPerson(word)}` : match,
  )
}

function capitalise(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1)
}

export function describeAgent(summary: string | null | undefined, fallback: string): string {
  const clean = (summary ?? '').replace(/\s+/g, ' ').trim()
  if (!clean) return fallback
  let rest = clean
  if (/^you are\b/i.test(rest)) {
    const after = /:\s*you\s+(.+)$/i.exec(rest)
    if (!after) return fallback
    rest = `you ${after[1]}`
  }
  const lead = /^you\s+([a-z]+)\b(.*)$/i.exec(rest)
  if (lead) {
    const verb = lead[1]!
    if (/^(are|were|will|can|should|must|may|might|would|could)$/i.test(verb)) return fallback
    rest = `${thirdPerson(verb)}${conjugateListedVerbs(lead[2] ?? '')}`
  } else if (/^you\b/i.test(rest)) {
    return fallback
  }
  // The first sentence is enough for a tooltip.
  const sentence = /^(.+?[.!?])(\s|$)/.exec(rest)
  return capitalise((sentence ? sentence[1]! : rest).trim())
}
