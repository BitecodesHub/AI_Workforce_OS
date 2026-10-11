// @find: needs you, inbox, questions waiting, approvals to decide, failed goals today, held work paused agent, buildNeedsYou, NeedsYouItem, for me, everyone, Orchestrator
// @what: Builds the list of items that need a person: open questions, approvals, failed work and work held on paused agents.
// @flow: Called by NeedsYouInbox.tsx with the live board
import { readableSummary } from '../../lib/approvals'
import type { Board, BoardGoal, BoardTask } from '../../lib/queries'

/*
 * What "Needs you" holds (D-19): the questions you asked, the approvals you can decide, your own
 * failed work from today, and your own work held on a paused agent. Pure and read from the live
 * board (B2.2), so the inbox never still shows an item that was just answered or decided while
 * updates elsewhere on the page are paused.
 */

/**
 * One thing waiting on a person: a question, an approval, a failed goal or a goal held on a paused agent. Each
 * variant carries the ids needed to open it, the requester, the creation time and a mine flag (see buildNeedsYou); questions and approvals also carry their deadline.
 */
export type NeedsYouItem =
  | {
      kind: 'question'
      id: string
      goalId: string | null
      runId: string
      agentId: string
      title: string
      header: string
      text: string
      requestedBy: string | null
      createdAt: string
      expiresAt: string
      canAnswer: boolean
      mine: boolean
    }
  | {
      kind: 'approval'
      id: string
      goalId: string | null
      runId: string
      agentId: string
      title: string
      summary: string
      requestedBy: string | null
      createdAt: string
      expiresAt: string
      canDecide: boolean
      mine: boolean
    }
  | {
      kind: 'failed'
      id: string
      goalId: string
      agentId: string | null
      title: string
      requestedBy: string | null
      createdAt: string
      reason: string | null
      mine: boolean
    }
  | {
      kind: 'held'
      id: string
      goalId: string
      agentId: string | null
      title: string
      requestedBy: string | null
      createdAt: string
      mine: boolean
    }

/** The task that explains how a goal ended: the first failed or cancelled task, else the last completed, else the last task. */
function endingTask(goal: Pick<BoardGoal, 'tasks'>): BoardTask | null {
  const tasks = [...goal.tasks].sort((a, b) => a.position - b.position)
  const stoppedEarly = tasks.find((task) => task.status.toLowerCase() === 'failed' || task.status.toLowerCase() === 'cancelled')
  if (stoppedEarly) return stoppedEarly
  const lastCompleted = [...tasks].reverse().find((task) => task.status.toLowerCase() === 'completed')
  return lastCompleted ?? tasks[tasks.length - 1] ?? null
}

/** The goal title behind a goal id, falling back to whatever the caller already knows. */
/** Goal title for a goal id, looking in live goals and in today's failed goals, with a fallback. */
function titleFor(goalId: string | null, board: Pick<Board, 'goals' | 'failedToday'>, fallback: string): string {
  if (!goalId) return fallback
  const goal = board.goals.find((candidate) => candidate.id === goalId) ?? board.failedToday.find((candidate) => candidate.id === goalId)
  return goal?.title ?? fallback
}

/** Sort rank: questions and approvals first (0), then failed goals (1), then held goals (2). */
function rankOf(item: NeedsYouItem): number {
  if (item.kind === 'question' || item.kind === 'approval') return 0
  if (item.kind === 'failed') return 1
  return 2
}

/** Questions and approvals by deadline, then failed goals newest first, then held goals. */
function sortNeedsYou(items: NeedsYouItem[]): NeedsYouItem[] {
  return [...items].sort((a, b) => {
    const rankA = rankOf(a)
    const rankB = rankOf(b)
    if (rankA !== rankB) return rankA - rankB
    if (rankA === 0) {
      const expiresA = Date.parse((a as { expiresAt: string }).expiresAt)
      const expiresB = Date.parse((b as { expiresAt: string }).expiresAt)
      if (expiresA !== expiresB) return expiresA - expiresB
    }
    return Date.parse(b.createdAt) - Date.parse(a.createdAt)
  })
}

// @find: build needs you list, what needs my attention, pending approvals and questions
/**
 * Builds the Needs you list from the board. Pending questions and all approvals become items (an approval is
 * "mine" when the viewer can decide it, since it waits on its approver, not its requester); each of
 * board.failedToday becomes a failed item with the failure reason of its ending task; each goal in
 * board.queue held because its agent is paused (once per goal) becomes a held item. Questions and failed or
 * held goals are mine when the viewer requested them. scope forMe keeps only items marked mine. The result is
 * sorted by sortNeedsYou. Pure, so the inbox never shows an item that was just answered.
 */
export function buildNeedsYou(board: Board, opts: { me: string | null; scope: 'forMe' | 'everyone' }): NeedsYouItem[] {
  const items: NeedsYouItem[] = []

  for (const question of board.questions) {
    if (question.status !== 'pending') continue
    const first = question.questions[0]
    items.push({
      kind: 'question',
      id: question.id,
      goalId: question.goalId,
      runId: question.runId,
      agentId: question.agentId,
      title: titleFor(question.goalId, board, question.goalTitle ?? 'Direct run'),
      header: first?.header ?? '',
      text: first?.question ?? '',
      requestedBy: question.requestedBy,
      createdAt: question.createdAt,
      expiresAt: question.expiresAt,
      canAnswer: question.canAnswer,
      mine: opts.me != null && question.requestedBy === opts.me,
    })
  }

  for (const approval of board.approvals) {
    items.push({
      kind: 'approval',
      id: approval.id,
      goalId: approval.goalId,
      runId: approval.runId,
      agentId: approval.agentId,
      title: titleFor(approval.goalId, board, 'Direct run'),
      // In words, once, here: the inbox, the live announcement and anything else reading an item
      // never shows a tool id such as gmail.send_message.
      summary: readableSummary({ tool: approval.tool, summary: approval.summary }),
      requestedBy: approval.requestedBy,
      createdAt: approval.requestedAt,
      expiresAt: approval.expiresAt,
      canDecide: approval.canDecide,
      // An approval waits on its approver, not its requester (D-19): "mine" for it means it is
      // this viewer's to decide, not that this viewer asked for the work behind it.
      mine: approval.canDecide,
    })
  }

  for (const goal of board.failedToday) {
    const task = endingTask(goal)
    items.push({
      kind: 'failed',
      id: goal.id,
      goalId: goal.id,
      agentId: task?.agentId ?? null,
      title: goal.title,
      requestedBy: goal.requestedBy,
      createdAt: goal.completedAt ?? goal.createdAt,
      reason: task?.failureReason ?? null,
      mine: opts.me != null && goal.requestedBy === opts.me,
    })
  }

  const heldGoals = new Set<string>()
  for (const entry of board.queue) {
    if (entry.reason !== 'agent_paused') continue
    if (heldGoals.has(entry.goalId)) continue
    heldGoals.add(entry.goalId)
    items.push({
      kind: 'held',
      id: entry.goalId,
      goalId: entry.goalId,
      agentId: entry.agentId,
      title: entry.goalTitle,
      requestedBy: entry.requestedBy,
      createdAt: entry.createdAt,
      mine: opts.me != null && entry.requestedBy === opts.me,
    })
  }

  const scoped = opts.scope === 'forMe' ? items.filter((item) => item.mine) : items
  return sortNeedsYou(scoped)
}
