import type { Member, Schedule } from './queries'

/*
 * Who may change a schedule, and what state one is in, shared by the Schedules screen and the
 * chat card so both apply one rule. The server enforces the same rule again on every request
 * (ScheduleService.requireCanManage); these are the client's read of it, for showing or hiding a
 * control before that round trip, never the decision itself.
 */

/** A schedule as these rules need it. `completed` and `state` come from the server when it sends them. */
export type ScheduleLike = Pick<Schedule, 'kind' | 'enabled' | 'nextRunAt' | 'createdBy'> & {
  completed?: boolean | null
  state?: string | null
}

/** Active, paused, or done: a one-off that has already run, which nobody stopped. */
export type ScheduleState = 'active' | 'paused' | 'done'

/**
 * Whether a schedule is a one-off that has already run.
 *
 * The server says so itself (`completed`, or `state` 'done'). Without either - an older server, a
 * row cached before it learned to - the same rule it uses is applied here: only a fired one-off is
 * left disabled with no next run, since a pause keeps the next run it had.
 */
export function isScheduleDone(schedule: ScheduleLike): boolean {
  if (typeof schedule.completed === 'boolean') return schedule.completed
  if (schedule.state) return schedule.state === 'done'
  return schedule.kind === 'once' && !schedule.enabled && schedule.nextRunAt == null
}

/** The schedule's state, in the three words every screen uses. */
export function scheduleState(schedule: ScheduleLike): ScheduleState {
  if (isScheduleDone(schedule)) return 'done'
  return schedule.enabled ? 'active' : 'paused'
}

/**
 * Whether `me` may pause, resume, run, edit or delete this schedule: its owner always, anyone else
 * only with task:cancel. A schedule with no owner on record needs task:cancel.
 */
export function canManageSchedule(
  schedule: Pick<ScheduleLike, 'createdBy'>,
  me: string | null,
  can: (permission: string) => boolean,
): boolean {
  return (me != null && schedule.createdBy != null && schedule.createdBy === me) || can('task:cancel')
}

/** Whether `me` may hand a schedule to somebody else: task:cancel, whoever owns it now. */
export function canTransferSchedule(can: (permission: string) => boolean): boolean {
  return can('task:cancel')
}

/**
 * Whether changing a schedule this way would make `me` its owner, as the server will: a new agent
 * or instruction, or bringing a one-off that already ran back with a new time (`reactivates`). A
 * rename or a new timetable alone leaves the owner as they were.
 */
export function editTakesOwnership(
  schedule: Pick<Schedule, 'createdBy' | 'agentId' | 'instruction'>,
  me: string | null,
  next: { agentId: string; instruction: string; reactivates?: boolean },
): boolean {
  if (me == null || schedule.createdBy === me) return false
  return next.reactivates === true || next.agentId !== schedule.agentId || next.instruction !== schedule.instruction
}

/**
 * Who a schedule runs as, in plain words: 'You', a member's name, 'Former member' only once the
 * member list has loaded and genuinely lacks them, 'Not recorded' for a schedule from before
 * owners were kept, and otherwise 'Someone'.
 */
export function scheduleOwnerLabel(
  createdBy: string | null,
  directory: { members: Readonly<Record<string, Pick<Member, 'displayName'>>>; loaded: boolean },
  me: string | null,
): string {
  if (!createdBy) return 'Not recorded'
  if (me && createdBy === me) return 'You'
  const member = directory.members[createdBy]
  if (member) return member.displayName
  return directory.loaded ? 'Former member' : 'Someone'
}

/** The people a schedule can be handed to: active members other than its current owner, by name. */
export function transferCandidates<M extends Pick<Member, 'userId' | 'displayName' | 'status'>>(
  members: readonly M[],
  currentOwner: string | null,
): M[] {
  return members
    .filter((member) => member.status.toLowerCase() === 'active' && member.userId !== currentOwner)
    .sort((a, b) => a.displayName.localeCompare(b.displayName))
}
