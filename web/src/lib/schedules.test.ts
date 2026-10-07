import { describe, expect, it } from 'vitest'
import { auditActionLabel, scheduleStateLabel } from './labels'
import {
  canManageSchedule,
  canTransferSchedule,
  editTakesOwnership,
  isScheduleDone,
  scheduleOwnerLabel,
  scheduleState,
  transferCandidates,
} from './schedules'

const holds =
  (...permissions: string[]) =>
  (permission: string) =>
    permissions.includes(permission)

const recurring = { kind: 'recurring' as const, enabled: true, nextRunAt: '2026-10-05T00:00:00Z', createdBy: 'u-owner' }

describe('canManageSchedule', () => {
  it('lets the owner manage their own schedule with nothing more than task:create', () => {
    expect(canManageSchedule(recurring, 'u-owner', holds('task:create'))).toBe(true)
  })

  it('refuses someone else on the employee role, so they see no Edit or Delete', () => {
    expect(canManageSchedule(recurring, 'u-employee', holds('task:read', 'task:create'))).toBe(false)
  })

  it('lets anyone holding task:cancel manage any schedule', () => {
    expect(canManageSchedule(recurring, 'u-manager', holds('task:create', 'task:cancel'))).toBe(true)
  })

  it('needs task:cancel for a schedule with no owner on record, and for a viewer with no id', () => {
    expect(canManageSchedule({ createdBy: null }, 'u-employee', holds('task:create'))).toBe(false)
    expect(canManageSchedule({ createdBy: null }, 'u-manager', holds('task:cancel'))).toBe(true)
    expect(canManageSchedule({ createdBy: null }, null, holds('task:create'))).toBe(false)
  })
})

describe('canTransferSchedule', () => {
  it('needs task:cancel', () => {
    expect(canTransferSchedule(holds('task:create'))).toBe(false)
    expect(canTransferSchedule(holds('task:cancel'))).toBe(true)
  })
})

describe('isScheduleDone and scheduleState', () => {
  const firedOnce = { kind: 'once' as const, enabled: false, nextRunAt: null, createdBy: 'u-owner' }

  it('takes the server’s own word when it sends one', () => {
    expect(isScheduleDone({ ...recurring, completed: true })).toBe(true)
    expect(isScheduleDone({ ...firedOnce, completed: false })).toBe(false)
    expect(isScheduleDone({ ...recurring, state: 'done' })).toBe(true)
    expect(isScheduleDone({ ...firedOnce, state: 'paused' })).toBe(false)
  })

  it('otherwise reads a disabled one-off with no next run as done', () => {
    expect(isScheduleDone(firedOnce)).toBe(true)
  })

  it('never reads a paused one-off that still has its time as done', () => {
    expect(isScheduleDone({ ...firedOnce, nextRunAt: '2026-12-01T00:00:00Z' })).toBe(false)
  })

  it('never reads a recurring schedule as done, however it was stopped', () => {
    expect(isScheduleDone({ ...recurring, enabled: false, nextRunAt: null })).toBe(false)
  })

  it('names the three states', () => {
    expect(scheduleState(recurring)).toBe('active')
    expect(scheduleState({ ...recurring, enabled: false })).toBe('paused')
    expect(scheduleState(firedOnce)).toBe('done')
  })
})

describe('scheduleStateLabel', () => {
  it('keeps Active and Paused, and says Done for a finished one-off', () => {
    expect(scheduleStateLabel(true)).toEqual({ tone: 'success', label: 'Active' })
    expect(scheduleStateLabel(false)).toEqual({ tone: 'neutral', label: 'Paused' })
    expect(scheduleStateLabel(false, true)).toEqual({ tone: 'neutral', label: 'Done' })
  })
})

describe('editTakesOwnership', () => {
  const schedule = { createdBy: 'u-owner', agentId: 'a1', instruction: 'Summarise the week' }

  it('is true when someone else changes the agent or the instruction', () => {
    expect(editTakesOwnership(schedule, 'u-manager', { agentId: 'a2', instruction: 'Summarise the week' })).toBe(true)
    expect(editTakesOwnership(schedule, 'u-manager', { agentId: 'a1', instruction: 'Summarise the month' })).toBe(true)
  })

  it('is true when someone else brings a finished one-off back with a new time', () => {
    expect(
      editTakesOwnership(schedule, 'u-manager', { agentId: 'a1', instruction: 'Summarise the week', reactivates: true }),
    ).toBe(true)
  })

  it('is false for the owner, and for a rename or new timetable alone', () => {
    expect(editTakesOwnership(schedule, 'u-owner', { agentId: 'a2', instruction: 'Other' })).toBe(false)
    expect(editTakesOwnership(schedule, 'u-manager', { agentId: 'a1', instruction: 'Summarise the week' })).toBe(false)
    expect(editTakesOwnership(schedule, null, { agentId: 'a2', instruction: 'Other' })).toBe(false)
  })
})

describe('scheduleOwnerLabel', () => {
  const loaded = { members: { 'u-priya': { displayName: 'Priya Shah' } }, loaded: true }
  const loading = { members: {}, loaded: false }

  it('names the viewer as You and a member by name', () => {
    expect(scheduleOwnerLabel('u-me', loaded, 'u-me')).toBe('You')
    expect(scheduleOwnerLabel('u-priya', loaded, 'u-me')).toBe('Priya Shah')
  })

  it('says Former member only once the list has loaded and lacks them', () => {
    expect(scheduleOwnerLabel('u-gone', loaded, 'u-me')).toBe('Former member')
    expect(scheduleOwnerLabel('u-gone', loading, 'u-me')).toBe('Someone')
  })

  it('says Not recorded for a schedule from before owners were kept', () => {
    expect(scheduleOwnerLabel(null, loaded, 'u-me')).toBe('Not recorded')
  })
})

describe('transferCandidates', () => {
  it('lists active members other than the current owner, by name', () => {
    const members = [
      { userId: 'u-owner', displayName: 'Ola', status: 'active' },
      { userId: 'u-zed', displayName: 'Zed', status: 'active' },
      { userId: 'u-amy', displayName: 'Amy', status: 'ACTIVE' },
      { userId: 'u-sam', displayName: 'Sam', status: 'suspended' },
    ]
    expect(transferCandidates(members, 'u-owner').map((member) => member.userId)).toEqual(['u-amy', 'u-zed'])
  })
})

describe('auditActionLabel for schedules', () => {
  it('reads every schedule action in plain words', () => {
    expect(auditActionLabel('schedule.create')).toBe('Created a schedule')
    expect(auditActionLabel('schedule.update')).toBe('Changed a schedule')
    expect(auditActionLabel('schedule.pause')).toBe('Paused a schedule')
    expect(auditActionLabel('schedule.resume')).toBe('Resumed a schedule')
    expect(auditActionLabel('schedule.run_now')).toBe('Ran a schedule now')
    expect(auditActionLabel('schedule.delete')).toBe('Deleted a schedule')
    expect(auditActionLabel('schedule.owner_change')).toBe('Handed a schedule to someone else')
    expect(auditActionLabel('orchestrator.stop_all')).toBe('Stopped all agent work')
  })
})
