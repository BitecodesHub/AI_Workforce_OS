import { describe, expect, it } from 'vitest'
import { countChanged, diffCards } from './boardChanges'
import type { Board, BoardGoal, BoardTask } from '../../lib/queries'

function task(overrides: Partial<BoardTask> & { id: string }): BoardTask {
  return {
    agentId: 'agent-1',
    title: 'Task',
    status: 'running',
    position: 0,
    dependsOn: [],
    attempt: 1,
    maxAttempts: 2,
    result: null,
    failureReason: null,
    startedAt: null,
    completedAt: null,
    runId: null,
    runStatus: null,
    stepCount: null,
    cost: null,
    ...overrides,
  }
}

function goal(overrides: Partial<BoardGoal> & { id: string; tasks: BoardTask[] }): BoardGoal {
  return {
    title: 'Goal',
    description: '',
    status: 'running',
    createdAt: '2026-09-28T00:00:00Z',
    completedAt: null,
    source: 'manual',
    requestedBy: null,
    conversationId: null,
    scheduleId: null,
    ...overrides,
  }
}

function board(goals: BoardGoal[]): Board {
  return {
    generatedAt: '2026-09-28T00:00:00Z',
    timezone: 'Australia/Melbourne',
    window: 'PT2H',
    windowMinutes: 120,
    stats: {
      running: 0,
      waitingApproval: 0,
      waitingInput: 0,
      queued: 0,
      held: 0,
      completedToday: 0,
      failedToday: 0,
      spendToday: 0,
      goalsCompletedToday: 0,
      goalsFailedToday: 0,
      directRuns: 0,
    },
    agents: [],
    goals,
    queue: [],
    timeline: [],
    questions: [],
    approvals: [],
    failedToday: [],
  }
}

describe('diffCards', () => {
  it('reports nothing changed with no previous board to compare against', () => {
    const next = board([goal({ id: 'g1', tasks: [task({ id: 't1', status: 'running' })] })])
    expect(diffCards(null, next)).toEqual(new Set())
  })

  it('flags a goal whose task status changed', () => {
    const previous = board([goal({ id: 'g1', tasks: [task({ id: 't1', status: 'running' })] })])
    const next = board([goal({ id: 'g1', tasks: [task({ id: 't1', status: 'completed' })], status: 'completed' })])
    expect(diffCards(previous, next)).toEqual(new Set(['g1']))
  })

  it('flags a goal whose column moved even with the same task id', () => {
    const previous = board([goal({ id: 'g1', tasks: [task({ id: 't1', status: 'waiting_input' })] })])
    const next = board([goal({ id: 'g1', tasks: [task({ id: 't1', status: 'running' })] })])
    expect(diffCards(previous, next)).toEqual(new Set(['g1']))
  })

  it('leaves an unchanged goal out of the diff', () => {
    const previous = board([goal({ id: 'g1', tasks: [task({ id: 't1', status: 'running' })] })])
    const next = board([goal({ id: 'g1', tasks: [task({ id: 't1', status: 'running' })] })])
    expect(diffCards(previous, next)).toEqual(new Set())
  })

  it('does not flag a goal that only just appeared', () => {
    const previous = board([])
    const next = board([goal({ id: 'g1', tasks: [task({ id: 't1', status: 'running' })] })])
    expect(diffCards(previous, next)).toEqual(new Set())
  })
})

describe('countChanged', () => {
  it('counts the changed goals', () => {
    const previous = board([
      goal({ id: 'g1', tasks: [task({ id: 't1', status: 'running' })] }),
      goal({ id: 'g2', tasks: [task({ id: 't2', status: 'running' })] }),
    ])
    const next = board([
      goal({ id: 'g1', tasks: [task({ id: 't1', status: 'completed' })], status: 'completed' }),
      goal({ id: 'g2', tasks: [task({ id: 't2', status: 'running' })] }),
    ])
    expect(countChanged(previous, next)).toBe(1)
  })
})
