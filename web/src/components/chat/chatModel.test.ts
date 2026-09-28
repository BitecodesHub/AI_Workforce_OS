import { describe, expect, it } from 'vitest'
import {
  currentTaskIndex,
  goalChainActive,
  groupMessages,
  isGroupedWithPrevious,
  newMessageIds,
  routingModeLabel,
  taskStepStatus,
} from './chatModel'
import type { ChatMessage, Task } from '../../lib/queries'

function message(overrides: Partial<ChatMessage> & Pick<ChatMessage, 'id'>): ChatMessage {
  return {
    position: 0,
    authorKind: 'user',
    authorId: 'user-1',
    agentId: null,
    kind: 'text',
    content: 'hello',
    detail: {},
    goalId: null,
    createdAt: '2026-09-28T00:00:00Z',
    ...overrides,
  }
}

function task(overrides: Partial<Task> & Pick<Task, 'id'>): Task {
  return {
    agentId: 'agent-1',
    title: 'Do it',
    status: 'pending',
    position: 0,
    dependsOn: [],
    attempt: 0,
    maxAttempts: 2,
    result: null,
    failureReason: null,
    startedAt: null,
    completedAt: null,
    runId: null,
    ...overrides,
  }
}

describe('isGroupedWithPrevious', () => {
  it('is false for the first message', () => {
    expect(isGroupedWithPrevious(message({ id: 'a' }), undefined)).toBe(false)
  })

  it('groups two user text messages in a row', () => {
    const first = message({ id: 'a', authorKind: 'user', kind: 'text' })
    const second = message({ id: 'b', authorKind: 'user', kind: 'text' })
    expect(isGroupedWithPrevious(second, first)).toBe(true)
  })

  it('does not group across a different author', () => {
    const first = message({ id: 'a', authorKind: 'user', kind: 'text' })
    const second = message({ id: 'b', authorKind: 'agent', agentId: 'agent-1', kind: 'answer' })
    expect(isGroupedWithPrevious(second, first)).toBe(false)
  })

  it('does not group two answers from different agents', () => {
    const first = message({ id: 'a', authorKind: 'agent', agentId: 'agent-1', kind: 'answer' })
    const second = message({ id: 'b', authorKind: 'agent', agentId: 'agent-2', kind: 'answer' })
    expect(isGroupedWithPrevious(second, first)).toBe(false)
  })

  it('never groups a routing, documents, progress, schedule or error card', () => {
    const first = message({ id: 'a', authorKind: 'coordinator', kind: 'routing' })
    const second = message({ id: 'b', authorKind: 'coordinator', kind: 'routing' })
    expect(isGroupedWithPrevious(second, first)).toBe(false)
  })
})

describe('groupMessages', () => {
  it('pairs every message with its grouping flag, in order', () => {
    const messages = [
      message({ id: 'a', authorKind: 'user', kind: 'text' }),
      message({ id: 'b', authorKind: 'user', kind: 'text' }),
      message({ id: 'c', authorKind: 'coordinator', kind: 'routing' }),
    ]
    expect(groupMessages(messages).map((entry) => entry.grouped)).toEqual([false, true, false])
  })
})

describe('newMessageIds', () => {
  it('returns every id on the first render (no previous list)', () => {
    const current = [message({ id: 'a' }), message({ id: 'b' })]
    expect(newMessageIds(undefined, current)).toEqual(['a', 'b'])
  })

  it('returns only ids not already seen', () => {
    const previous = [message({ id: 'a' })]
    const current = [message({ id: 'a' }), message({ id: 'b' })]
    expect(newMessageIds(previous, current)).toEqual(['b'])
  })

  it('narrows to one kind when asked', () => {
    const current = [message({ id: 'a', kind: 'answer' }), message({ id: 'b', kind: 'error' })]
    expect(newMessageIds(undefined, current, 'answer')).toEqual(['a'])
  })

  it('returns nothing once every id has been seen', () => {
    const previous = [message({ id: 'a' }), message({ id: 'b' })]
    expect(newMessageIds(previous, previous)).toEqual([])
  })
})

describe('taskStepStatus', () => {
  it('maps every task status to its chain word', () => {
    expect(taskStepStatus(task({ id: '1', status: 'pending' }))).toBe('queued')
    expect(taskStepStatus(task({ id: '1', status: 'ready' }))).toBe('queued')
    expect(taskStepStatus(task({ id: '1', status: 'running' }))).toBe('working')
    expect(taskStepStatus(task({ id: '1', status: 'waiting_approval' }))).toBe('waiting_approval')
    expect(taskStepStatus(task({ id: '1', status: 'completed' }))).toBe('done')
    expect(taskStepStatus(task({ id: '1', status: 'failed' }))).toBe('failed')
    expect(taskStepStatus(task({ id: '1', status: 'cancelled' }))).toBe('cancelled')
    expect(taskStepStatus(task({ id: '1', status: 'skipped' }))).toBe('skipped')
  })
})

describe('currentTaskIndex', () => {
  it('picks the running task over any queued one', () => {
    const tasks = [task({ id: '1', status: 'completed' }), task({ id: '2', status: 'running' }), task({ id: '3', status: 'pending' })]
    expect(currentTaskIndex(tasks)).toBe(1)
  })

  it('picks a task waiting on an approval', () => {
    const tasks = [task({ id: '1', status: 'completed' }), task({ id: '2', status: 'waiting_approval' })]
    expect(currentTaskIndex(tasks)).toBe(1)
  })

  it('falls back to the first still queued when nothing is active', () => {
    const tasks = [task({ id: '1', status: 'completed' }), task({ id: '2', status: 'pending' })]
    expect(currentTaskIndex(tasks)).toBe(1)
  })

  it('returns -1 once every task has ended', () => {
    const tasks = [task({ id: '1', status: 'completed' }), task({ id: '2', status: 'failed' })]
    expect(currentTaskIndex(tasks)).toBe(-1)
  })
})

describe('goalChainActive', () => {
  it('is true while any task can still change', () => {
    expect(goalChainActive({ tasks: [task({ id: '1', status: 'completed' }), task({ id: '2', status: 'pending' })] })).toBe(true)
  })

  it('is false once every task has ended', () => {
    expect(goalChainActive({ tasks: [task({ id: '1', status: 'completed' }), task({ id: '2', status: 'cancelled' })] })).toBe(
      false,
    )
  })
})

describe('routingModeLabel', () => {
  it('reads each mode in plain words', () => {
    expect(routingModeLabel('mention')).toBe('You mentioned')
    expect(routingModeLabel('model')).toBe('Chosen by the model')
    expect(routingModeLabel('rules')).toBe('Matched by keywords')
    expect(routingModeLabel('manual')).toBe('You chose')
  })

  it('falls back for an unknown or missing mode', () => {
    expect(routingModeLabel(undefined)).toBe('Routed')
  })
})
