// @find: chat model, group messages, message grouping, new message ids, answer announce, read aloud, goal chain, thread rules, pure functions
// @what: Pure rules behind the thread: grouping, new-message detection and a goal's chain.
// @flow: Used by MessageList, MessageItem, ProgressCard and the Chat page.
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

// @find: isGroupedWithPrevious, is grouped with previous, chat model, group messages, message grouping, new message ids
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

// @find: groupMessages, group messages, chat model, group messages, message grouping, new message ids
/** Every message paired with whether it groups with the one before it, in one pass. */
export function groupMessages(messages: readonly ChatMessage[]): Array<{ message: ChatMessage; grouped: boolean }> {
  return messages.map((message, index) => ({ message, grouped: isGroupedWithPrevious(message, messages[index - 1]) }))
}

/* ---- What is new since the last render ------------------------------------------------------ */

// @find: newMessageIds, new message ids, chat model, group messages, message grouping, new message ids
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

// @find: readyAnswers, ready answers, chat model, group messages, message grouping, new message ids
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

// @find: taskStepStatus, task step status, chat model, group messages, message grouping, new message ids
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

// @find: currentTaskIndex, current task index, chat model, group messages, message grouping, new message ids
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

// @find: goalChainActive, goal chain active, chat model, group messages, message grouping, new message ids
/** Whether a goal's own chain still has any moving part: at least one task not at an end state. */
export function goalChainActive(goal: Pick<Goal, 'tasks'>): boolean {
  return goal.tasks.some((task) => ACTIVE_STEP_STATUSES.has(taskStepStatus(task)))
}

/* ---- Reading a routing receipt ---------------------------------------------------------------- */

// @find: routingModeLabel, routing mode label, chat model, group messages, message grouping, new message ids
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

// @find: routingSummary, routing summary, chat model, group messages, message grouping, new message ids
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

// @find: routingPassages, routing passages, chat model, group messages, message grouping, new message ids
/** The passages a routing message says its agent was given, in the order it read them; none when it records none. */
export function routingPassages(message: ChatMessage | undefined): SourcePassage[] {
  const passages: SourcePassage[] | undefined = message?.detail.passages
  return Array.isArray(passages) ? passages : []
}

// @find: sourcesForAnswer, sources for answer, chat model, group messages, message grouping, new message ids
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

// @find: passageLink, passage link, chat model, group messages, message grouping, new message ids
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

// @find: progressSummary, progress summary, chat model, group messages, message grouping, new message ids
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

// @find: choiceMadeFor, choice made for, chat model, group messages, message grouping, new message ids
/** The name of the agent a later routing message rerouted `messageId` to, or null if none has. */
export function choiceMadeFor(messageId: string, messages: readonly ChatMessage[]): string | null {
  const later = messages.find((message) => message.detail.rerouteOf === messageId)
  const agent = later?.detail.agents?.[0]
  return agent?.name ?? null
}

// @find: becauseText, because text, chat model, group messages, message grouping, new message ids
/** The reason a routing message gives, as the end of "because ...": lower-cased start, no final full stop. */
export function becauseText(reason: string): string {
  const clean = reason.replace(/\s+/g, ' ').trim().replace(/[.\s]+$/, '')
  if (clean.length > 1 && /[A-Z]/.test(clean[0]!) && !/[A-Z]/.test(clean[1]!)) return clean[0]!.toLowerCase() + clean.slice(1)
  return clean
}

// @find: routingMessageForGoal, routing message for goal, chat model, group messages, message grouping, new message ids
/** The routing message that started `goalId`, for "Ask again" and the Orchestrator's deep link. */
export function routingMessageForGoal(goalId: string, messages: readonly ChatMessage[]): ChatMessage | undefined {
  return messages.find((message) => message.kind === 'routing' && message.goalId === goalId)
}

// @find: progressMessageForGoal, progress message for goal, chat model, group messages, message grouping, new message ids
/** The progress message that tracks `goalId` in the thread, the anchor its "Review" link scrolls to. */
export function progressMessageForGoal(goalId: string, messages: readonly ChatMessage[]): ChatMessage | undefined {
  return messages.find((message) => message.kind === 'progress' && message.goalId === goalId)
}

// @find: goalTarget, goal target, chat model, group messages, message grouping, new message ids
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

// @find: goalHasAnswer, goal has answer, chat model, group messages, message grouping, new message ids
/** Whether a goal already has an answer message in the thread. */
export function goalHasAnswer(goalId: string, messages: readonly ChatMessage[]): boolean {
  return messages.some((message) => message.kind === 'answer' && message.goalId === goalId)
}

// @find: lastAnswer, last answer, chat model, group messages, message grouping, new message ids
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

// @find: dayLabel, day label, chat model, group messages, message grouping, new message ids
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

// @find: withDayDividers, with day dividers, chat model, group messages, message grouping, new message ids
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

// @find: orphanQuestions, orphan questions, chat model, group messages, message grouping, new message ids
/** Pending questions with no `question` message of their own yet, rendered at the end of the thread. */
export function orphanQuestions(questions: readonly RunQuestion[], messages: readonly ChatMessage[]): RunQuestion[] {
  const withMessage = new Set(messages.map((message) => message.detail.questionId).filter((id): id is string => Boolean(id)))
  return questions.filter((question) => question.status === 'pending' && !withMessage.has(question.id))
}

// @find: autoAnswerTarget, auto answer target, chat model, group messages, message grouping, new message ids
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

// @find: sendsAsNewRequest, sends as new request, chat model, group messages, message grouping, new message ids
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

// @find: composerAnswer, composer answer, chat model, group messages, message grouping, new message ids
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

// @find: newestPosition, newest position, chat model, group messages, message grouping, new message ids
/** The newest position in the thread, or -1 for an empty one: what a send waits to see exceeded. */
export function newestPosition(messages: readonly ChatMessage[]): number {
  return messages.reduce((highest, message) => Math.max(highest, message.position), -1)
}

// @find: pendingEchoed, pending echoed, chat model, group messages, message grouping, new message ids
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

// @find: resendText, resend text, chat model, group messages, message grouping, new message ids
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

// @find: draftKey, draft key, chat model, group messages, message grouping, new message ids
/**
 * Where the composer keeps an unsent draft: per person as well as per conversation, so somebody
 * signing in after someone else on the same browser never sees that person's half-written text.
 */
export function draftKey(userId: string, conversationId: string | null): string {
  return `chat.draft.${userId}.${conversationId ?? 'new'}`
}

// @find: isLegacyDraftKey, is legacy draft key, chat model, group messages, message grouping, new message ids
/** The draft keys written before drafts were kept per person: `chat.draft.<conversation or new>`. */
export function isLegacyDraftKey(key: string): boolean {
  return key.startsWith('chat.draft.') && !key.slice('chat.draft.'.length).includes('.')
}

// @find: removeLegacyDrafts, remove legacy drafts, chat model, group messages, message grouping, new message ids
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

// @find: activeGoals, active goals, chat model, group messages, message grouping, new message ids
/** The goals whose chain can still change: planning, running or waiting. */
export function activeGoals(goals: readonly BoardGoal[]): BoardGoal[] {
  return goals.filter(isGoalActive)
}

export { canRetryGoal, canStopGoal }

/* ---- The work strip ------------------------------------------------------------------------------- */

// @find: workStripSummary, work strip summary, chat model, group messages, message grouping, new message ids
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

// @find: liveStepText, live step text, chat model, group messages, message grouping, new message ids
/**
 * One line for the step a run is on right now, for the work strip and the Work panel.
 *
 * A model call is written only once the model has replied, so while a run waits on its first reply
 * its last step is the instruction note (or the notes and documents read before it). That is the
 * longest wait of most runs, and it used to read "Starting" for all of it, as though the work had
 * stalled. Only a run with no step at all is still starting.
 */
export function liveStepText(step: RunStep | undefined): string {
  if (!step) return 'Starting'
  switch (step.kind) {
    case 'model_call':
    case 'note':
    case 'error':
      return 'Working on a reply'
    case 'memory_read':
      return 'Last step: read its notes'
    case 'knowledge_query':
      return 'Last step: searched the documents'
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
      return 'Working on a reply'
  }
}

/* ---- Error help ---------------------------------------------------------------------------------- */

const NO_MODEL_CODES = new Set(['no_model_available', 'provider_credential_invalid', 'provider_not_configured'])

// @find: errorHelp, error help, chat model, group messages, message grouping, new message ids
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

// @find: messageAuthor, message author, chat model, group messages, message grouping, new message ids
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

// @find: conversationText, conversation text, chat model, group messages, message grouping, new message ids
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


// What an agent does, in one line: kept in lib so dialogs outside Chat use the same words.
export { agentDescription, describeAgent } from '../../lib/agentDescription'

// @find: isRoutingLastMessage, is routing last message, chat model, group messages, message grouping, new message ids
/**
 * Whether the newest message is one the server is still routing: the conversation is busy, no goal
 * has started yet, and nothing has answered the person's last message. The thread then says so,
 * as it did before a reload, instead of showing a message that seems to have been ignored.
 */
export function isRoutingLastMessage(
  messages: readonly Pick<ChatMessage, 'authorKind' | 'kind'>[],
  goals: readonly BoardGoal[],
  busy: string,
): boolean {
  if (busy !== 'working' || goals.some(isGoalActive)) return false
  const last = messages[messages.length - 1]
  return last !== undefined && last.authorKind === 'user' && last.kind === 'text'
}

// @find: titleFromFirstMessage, title from first message, chat model, group messages, message grouping, new message ids
/**
 * The title a new conversation takes from its first message, cut at a word to 60 characters the
 * way the server cuts it. Given when the conversation is created, so the list shows it at once
 * rather than "Untitled conversation" for as long as the first message is being routed.
 */
export function titleFromFirstMessage(text: string): string {
  const clean = text.replace(/\s+/g, ' ').trim()
  if (clean.length <= 60) return clean
  const cut = clean.lastIndexOf(' ', 59)
  return (cut > 0 ? clean.slice(0, cut) : clean.slice(0, 60)).trim()
}

/** Under an answer whose tools ran on practice data, so "the email has been sent" is not taken as fact. */
export const PRACTICE_DATA_NOTE = 'Practice data: nothing was sent or changed outside this workspace.'

// @find: retryLeftInBox, retry left in box, chat model, group messages, message grouping, new message ids
/** Whether the message box still holds the words "Try again" has just sent, untouched since. */
export function retryLeftInBox(boxText: string | undefined, sentText: string): boolean {
  return boxText !== undefined && boxText.trim() !== '' && boxText.trim() === sentText.trim()
}

