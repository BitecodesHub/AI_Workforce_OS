// @find: requester label, who requested, member directory, current task for goal, unknown requester, Someone, You, Orchestrator helpers
// @what: Small pure helpers that name requesters and find a goal's current task.
// @flow: Used by FlowMap, BoardList, Board and GoalSheet
import type { BoardGoal, BoardTask, Member } from '../../lib/queries'

/*
 * Small pure helpers shared by the flow map, the board and the goal drawer. Geometry and column
 * grouping live in layout.ts; these read the board's own data rather than measuring anything.
 */

/**
 * The workspace's members by user id, and whether that list actually loaded. A miss only means
 * somebody has left once `loaded` is true: while the list is still loading, after it failed, or
 * when the viewer may not read members at all, a miss says nothing about the person.
 */
export type MemberDirectory = {
  members: Record<string, Member>
  loaded: boolean
}

/** Shown when the board cannot say who asked, rather than guessing. */
export const UNKNOWN_REQUESTER = 'Someone'

/**
 * Who asked for a goal, in the words the board uses: 'Scheduled', 'You', a member's name,
 * 'Former member' only when the loaded member list genuinely lacks them, and otherwise 'Someone'.
 */
// @find: requester label, who asked for this goal, requested by
export function requesterLabel(
  goal: { source: string; requestedBy: string | null },
  directory: MemberDirectory,
  currentUserId: string | null,
): string {
  if (goal.source === 'schedule') return 'Scheduled'
  if (!goal.requestedBy) return UNKNOWN_REQUESTER
  if (currentUserId && goal.requestedBy === currentUserId) return 'You'
  const member = directory.members[goal.requestedBy]
  if (member) return member.displayName
  return directory.loaded ? 'Former member' : UNKNOWN_REQUESTER
}

const CURRENT_TASK_STATUS = new Set(['running', 'waiting_approval', 'waiting_input'])

/** The task an agent is in the middle of right now, if any, with the goal it belongs to. */
// @find: current task of a goal, running task
export function currentTaskFor(
  board: { goals: readonly BoardGoal[] },
  agentId: string,
): { goal: BoardGoal; task: BoardTask } | null {
  for (const goal of board.goals) {
    const task = goal.tasks.find(
      (candidate) => candidate.agentId === agentId && CURRENT_TASK_STATUS.has(candidate.status.toLowerCase()),
    )
    if (task) return { goal, task }
  }
  return null
}
