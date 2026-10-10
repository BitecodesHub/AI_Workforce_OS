// @find: tests for goals, canStopGoal, canRetryGoal, stop, retry, permissions
// @what: Unit tests for goal stop and retry permissions.
import { describe, expect, it } from 'vitest'
import { canRetryGoal, canStopGoal } from './goals'

const can = (granted: string[]) => (permission: string) => granted.includes(permission)

describe('canStopGoal', () => {
  it('lets the requester stop their own active goal', () => {
    expect(canStopGoal({ status: 'running', requestedBy: 'u1' }, 'u1', can([]))).toBe(true)
  })

  it('lets someone holding task:cancel stop a goal requested by someone else', () => {
    expect(canStopGoal({ status: 'waiting', requestedBy: 'u1' }, 'u2', can(['task:cancel']))).toBe(true)
  })

  it('refuses another employee with no task:cancel', () => {
    expect(canStopGoal({ status: 'running', requestedBy: 'u1' }, 'u2', can([]))).toBe(false)
  })

  it('refuses once the goal has finished', () => {
    expect(canStopGoal({ status: 'completed', requestedBy: 'u1' }, 'u1', can(['task:cancel']))).toBe(false)
  })
})

describe('canRetryGoal', () => {
  it('lets the requester retry their own finished goal when they hold task:create', () => {
    expect(canRetryGoal({ status: 'failed', requestedBy: 'u1' }, 'u1', can(['task:create']))).toBe(true)
  })

  it('lets a task:cancel holder retry a goal requested by someone else, given task:create too', () => {
    expect(canRetryGoal({ status: 'cancelled', requestedBy: 'u1' }, 'u2', can(['task:create', 'task:cancel']))).toBe(true)
  })

  it('refuses without task:create, even for the requester', () => {
    expect(canRetryGoal({ status: 'failed', requestedBy: 'u1' }, 'u1', can([]))).toBe(false)
  })

  it('refuses task:cancel alone, without task:create', () => {
    expect(canRetryGoal({ status: 'failed', requestedBy: 'u1' }, 'u2', can(['task:cancel']))).toBe(false)
  })

  it('refuses a goal that is still active', () => {
    expect(canRetryGoal({ status: 'running', requestedBy: 'u1' }, 'u1', can(['task:create']))).toBe(false)
  })
})
