import { isGoalActive } from './queries'

/*
 * Who can stop or retry a goal (D-7), shared by Tasks, Chat and the Orchestrator so the three
 * screens apply one rule rather than three slightly different ones. The server enforces the same
 * rule again on the request itself; these are the client's read of it, for showing or hiding the
 * button before that round trip.
 */

export type GoalLike = { status: string; requestedBy: string | null }

/** Whether `me` requested this goal, or holds task:cancel, and the goal is still active. */
export function canStopGoal(goal: GoalLike, me: string | null, can: (permission: string) => boolean): boolean {
  if (!isGoalActive(goal)) return false
  return (me != null && goal.requestedBy === me) || can('task:cancel')
}

/** Whether `me` holds task:create, and either requested this finished goal or holds task:cancel. */
export function canRetryGoal(goal: GoalLike, me: string | null, can: (permission: string) => boolean): boolean {
  if (isGoalActive(goal)) return false
  if (!can('task:create')) return false
  return (me != null && goal.requestedBy === me) || can('task:cancel')
}
