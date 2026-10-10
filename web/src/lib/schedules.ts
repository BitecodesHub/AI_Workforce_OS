// @find: schedules, schedule state, active paused done, who can manage a schedule, transfer schedule, schedule owner, canManageSchedule, canTransferSchedule, editTakesOwnership, transferCandidates, Schedules page, schedule card
// @what: Client-side rules for who may change a schedule and which state it is in.
// @flow: Used by routes/Schedules.tsx, ScheduleCard and ScheduleDialog; the server enforces the same rule in ScheduleService.requireCanManage.
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

// @find: schedule done, schedule finished, one-time schedule completed
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

// @find: schedule state, active, paused, done, schedule status badge; used by: Schedules page card
/** The schedule's state, in the three words every screen uses. */
export function scheduleState(schedule: ScheduleLike): ScheduleState {
  if (isScheduleDone(schedule)) return 'done'
  return schedule.enabled ? 'active' : 'paused'
}

// @find: can manage schedule, edit pause delete schedule permission, Schedules page buttons
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

// @find: can transfer schedule, change schedule owner permission
/** Whether `me` may hand a schedule to somebody else: task:cancel, whoever owns it now. */
export function canTransferSchedule(can: (permission: string) => boolean): boolean {
  return can('task:cancel')
}

// @find: edit takes ownership, editing a schedule makes you the owner; used by: schedule dialog
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

// @find: schedule owner label, who the schedule runs as; used by: Schedules page
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

/** The permission a schedule's owner needs: it fires as them, starting work in their name. */
export const SCHEDULE_OWNER_PERMISSION = 'task:create'

// @find: roles that can own schedules, task:create holders, new owner choices
/**
 * Which roles may own a schedule, from the workspace's roles: those granting task:create. A viewer
 * cannot start work, so a schedule handed to one would fire in the name of someone not allowed to
 * run it. Null while the roles are unknown, when nobody is filtered out on role.
 */
export function rolesThatCanOwnSchedules(
  roles: readonly { name: string; permissions: readonly string[] }[] | undefined,
): Set<string> | null {
  if (!roles) return null
  return new Set(
    roles.filter((role) => role.permissions.includes(SCHEDULE_OWNER_PERMISSION)).map((role) => role.name.toLowerCase()),
  )
}

// @find: transfer candidates, members who can take over a schedule; used by: Schedules page transfer dialog
/**
 * The people a schedule can be handed to: active members other than its current owner whose role
 * can start work (when the roles are known), by name.
 */
export function transferCandidates<M extends Pick<Member, 'userId' | 'displayName' | 'status'> & { role?: string }>(
  members: readonly M[],
  currentOwner: string | null,
  ownerRoles: ReadonlySet<string> | null = null,
): M[] {
  return members
    .filter((member) => member.status.toLowerCase() === 'active' && member.userId !== currentOwner)
    .filter((member) => ownerRoles == null || ownerRoles.has((member.role ?? '').toLowerCase()))
    .sort((a, b) => a.displayName.localeCompare(b.displayName))
}
