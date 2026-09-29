import type { BoardGoal, BoardTask, Member } from '../../lib/queries'

/*
 * Small pure helpers shared by the flow map, the board and the goal drawer. Geometry and column
 * grouping live in layout.ts; these read the board's own data rather than measuring anything.
 */

/** Who asked for a goal, in the words the board uses: 'Scheduled', 'You', a member's name, or not. */
export function requesterLabel(
  goal: { source: string; requestedBy: string | null },
  members: Record<string, Member>,
  currentUserId: string | null,
): string {
  if (goal.source === 'schedule') return 'Scheduled'
  if (!goal.requestedBy) return 'Unknown'
  if (currentUserId && goal.requestedBy === currentUserId) return 'You'
  return members[goal.requestedBy]?.displayName ?? 'Former member'
}

const CURRENT_TASK_STATUS = new Set(['running', 'waiting_approval', 'waiting_input'])

/** The task an agent is in the middle of right now, if any, with the goal it belongs to. */
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
