import type { ChatMessage, ChatMessageDetail, Goal, Task } from '../../lib/queries'

/*
 * The pure rules behind the chat thread: which messages belong together, what a goal's chain of
 * tasks is doing, and which agent replies are new since the last render. Nothing here touches the
 * DOM or React state, so Chat.tsx and the cards in this folder can stay thin, and these rules can
 * be tested without a signed-in screen harness (see lib/voice.test.ts's own note on why the
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

/* ---- Reading a goal's chain ------------------------------------------------------------------ */

export type TaskStepStatus = 'queued' | 'working' | 'waiting_approval' | 'done' | 'failed' | 'cancelled' | 'skipped'

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

/**
 * The task the chain is on right now: the first one running or waiting on an approval, else the
 * first still queued, else null once every task has reached an end state. Assumes `tasks` is
 * already in the goal's own order (position), as every goal the platform returns is.
 */
export function currentTaskIndex(tasks: readonly Task[]): number {
  const active = tasks.findIndex((task) => task.status === 'running' || task.status === 'waiting_approval')
  if (active !== -1) return active
  return tasks.findIndex((task) => task.status === 'pending' || task.status === 'ready')
}

/** Whether a goal's own chain still has any moving part: at least one task not at an end state. */
export function goalChainActive(goal: Pick<Goal, 'tasks'>): boolean {
  return goal.tasks.some((task) => taskStepStatus(task) === 'queued' || taskStepStatus(task) === 'working' || taskStepStatus(task) === 'waiting_approval')
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
    default:
      return 'Routed'
  }
}

/* ---- The welcome screen ------------------------------------------------------------------------ */

/** What an empty thread offers to try, verbatim so every reader sees the same four. */
export const SUGGESTION_CHIPS: readonly string[] = [
  'Draft a welcome email for our new starter Priya',
  'Summarise the open pull requests',
  'Research our top three competitors, then draft a short note for the team',
  'Every weekday at 9am, summarise new support tickets',
]
