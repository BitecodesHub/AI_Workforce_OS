import { describe, expect, it } from 'vitest'
import {
  activeGoals,
  autoAnswerTarget,
  canRetryGoal,
  canStopGoal,
  choiceMadeFor,
  composerAnswer,
  conversationText,
  currentTaskIndex,
  dayLabel,
  errorHelp,
  goalChainActive,
  goalHasAnswer,
  groupMessages,
  isGroupedWithPrevious,
  lastAnswer,
  liveStepText,
  messageAuthor,
  newMessageIds,
  orphanQuestions,
  progressSummary,
  readyAnswers,
  routingModeLabel,
  routingMessageForGoal,
  routingSummary,
  suggestionChips,
  taskStepStatus,
  withDayDividers,
} from './chatModel'
import type { Agent, BoardGoal, BoardTask, ChatMessage, Member, RunQuestion, RunStep, Task } from '../../lib/queries'

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

function boardTask(overrides: Partial<BoardTask> & Pick<BoardTask, 'id'>): BoardTask {
  return { ...task(overrides), runStatus: null, stepCount: null, cost: null, ...overrides }
}

function goal(overrides: Partial<BoardGoal> & Pick<BoardGoal, 'id'>): BoardGoal {
  return {
    title: 'A goal',
    description: '',
    status: 'running',
    createdAt: '2026-09-28T00:00:00Z',
    completedAt: null,
    source: 'chat',
    requestedBy: 'user-1',
    conversationId: 'c1',
    scheduleId: null,
    tasks: [],
    ...overrides,
  }
}

function questionItem(overrides: Partial<RunQuestion['questions'][number]> = {}) {
  return {
    id: 'q1',
    header: 'Audience',
    question: 'Who is this for?',
    multiSelect: false,
    options: [
      { label: 'The whole team', description: '', recommended: true },
      { label: 'Managers', description: '' },
    ],
    ...overrides,
  }
}

function runQuestion(overrides: Partial<RunQuestion> & Pick<RunQuestion, 'id'>): RunQuestion {
  return {
    runId: 'run-1',
    taskId: null,
    goalId: 'goal-1',
    conversationId: 'c1',
    agentId: 'agent-1',
    goalTitle: 'A goal',
    status: 'pending',
    questions: [questionItem()],
    answer: null,
    answeredBy: null,
    answeredVia: null,
    answeredAt: null,
    requestedBy: 'user-1',
    createdAt: '2026-09-28T00:05:00Z',
    expiresAt: '2026-09-29T00:05:00Z',
    closedReason: null,
    canAnswer: true,
    extendable: false,
    runStatus: 'waiting_input',
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

describe('readyAnswers', () => {
  it('is ready at once when every agent name is already known', () => {
    const fresh = [message({ id: 'a', kind: 'answer', agentId: 'agent-1' })]
    const result = readyAnswers(fresh, () => true, new Map(), 0)
    expect(result).toEqual({ ready: fresh, pending: false })
  })

  it('holds an answer back the first time its agent name is missing, remembering when', () => {
    const fresh = [message({ id: 'a', kind: 'answer', agentId: 'agent-1' })]
    const unresolvedSince = new Map<string, number>()
    const result = readyAnswers(fresh, () => false, unresolvedSince, 1_000)
    expect(result).toEqual({ ready: [], pending: true })
    expect(unresolvedSince.get('a')).toBe(1_000)
  })

  it('becomes ready, and forgets the id, as soon as the name resolves on a later call', () => {
    const fresh = [message({ id: 'a', kind: 'answer', agentId: 'agent-1' })]
    const unresolvedSince = new Map<string, number>([['a', 1_000]])
    const result = readyAnswers(fresh, () => true, unresolvedSince, 2_000)
    expect(result).toEqual({ ready: fresh, pending: false })
    expect(unresolvedSince.has('a')).toBe(false)
  })

  it('gives up and announces anyway once the timeout passes, so a removed agent is not silently skipped', () => {
    const fresh = [message({ id: 'a', kind: 'answer', agentId: 'agent-1' })]
    const unresolvedSince = new Map<string, number>([['a', 0]])
    const result = readyAnswers(fresh, () => false, unresolvedSince, 8_000)
    expect(result).toEqual({ ready: fresh, pending: false })
    expect(unresolvedSince.has('a')).toBe(false)
  })

  it('still waits one millisecond before the timeout', () => {
    const fresh = [message({ id: 'a', kind: 'answer', agentId: 'agent-1' })]
    const unresolvedSince = new Map<string, number>([['a', 0]])
    const result = readyAnswers(fresh, () => false, unresolvedSince, 7_999)
    expect(result.pending).toBe(true)
  })

  it('is ready without ever tracking a message answered by no agent (a system message)', () => {
    const fresh = [message({ id: 'a', kind: 'answer', agentId: null })]
    const unresolvedSince = new Map<string, number>()
    const result = readyAnswers(fresh, () => false, unresolvedSince, 0)
    expect(result).toEqual({ ready: fresh, pending: false })
    expect(unresolvedSince.size).toBe(0)
  })

  it('holds the whole batch back for one unresolved answer among several resolved ones', () => {
    const fresh = [
      message({ id: 'a', kind: 'answer', agentId: 'agent-1' }),
      message({ id: 'b', kind: 'answer', agentId: 'agent-2' }),
    ]
    const result = readyAnswers(fresh, (agentId) => agentId === 'agent-1', new Map(), 0)
    expect(result.pending).toBe(true)
  })
})

describe('taskStepStatus', () => {
  it('maps every task status to its chain word', () => {
    expect(taskStepStatus(task({ id: '1', status: 'pending' }))).toBe('queued')
    expect(taskStepStatus(task({ id: '1', status: 'ready' }))).toBe('queued')
    expect(taskStepStatus(task({ id: '1', status: 'running' }))).toBe('working')
    expect(taskStepStatus(task({ id: '1', status: 'waiting_approval' }))).toBe('waiting_approval')
    expect(taskStepStatus(task({ id: '1', status: 'waiting_input' }))).toBe('waiting_input')
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

  it('picks a task waiting on an answer', () => {
    const tasks = [task({ id: '1', status: 'completed' }), task({ id: '2', status: 'waiting_input' })]
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

  it('is true while a task waits for an answer', () => {
    expect(
      goalChainActive({ tasks: [task({ id: '1', status: 'completed' }), task({ id: '2', status: 'waiting_input' })] }),
    ).toBe(true)
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
    expect(routingModeLabel('fallback')).toBe('No specialist matched')
  })

  it('falls back for an unknown or missing mode', () => {
    expect(routingModeLabel(undefined)).toBe('Routed')
  })
})

describe('routingSummary', () => {
  const names = { research: { name: 'Research' }, general: { name: 'General Employee' } }

  it('names a model choice', () => {
    const m = message({ id: 'a', kind: 'routing', detail: { mode: 'model', agents: [{ id: 'research', name: 'Research', instruction: '' }] } })
    expect(routingSummary(m, names)).toBe('Sent to Research')
  })

  it('names a mention', () => {
    const m = message({ id: 'a', kind: 'routing', detail: { mode: 'mention', agents: [{ id: 'research', name: 'Research', instruction: '' }] } })
    expect(routingSummary(m, names)).toBe('Sent to @Research, as you asked')
  })

  it('names a manual choice', () => {
    const m = message({ id: 'a', kind: 'routing', detail: { mode: 'manual', agents: [{ id: 'research', name: 'Research', instruction: '' }] } })
    expect(routingSummary(m, names)).toBe('You chose Research')
  })

  it('names keyword matches', () => {
    const m = message({ id: 'a', kind: 'routing', detail: { mode: 'rules', agents: [{ id: 'research', name: 'Research', instruction: '' }] } })
    expect(routingSummary(m, names)).toBe('Sent to Research')
  })

  it('explains a fallback to General Employee', () => {
    const m = message({
      id: 'a',
      kind: 'routing',
      detail: { mode: 'fallback', agents: [{ id: 'general', name: 'General Employee', instruction: '' }] },
    })
    expect(routingSummary(m, names)).toBe('Sent to General Employee, as no specialist matched')
  })
})

describe('progressSummary', () => {
  it('reads a completed goal', () => {
    const g = goal({
      id: 'g1',
      status: 'completed',
      createdAt: '2026-09-28T00:00:00Z',
      completedAt: '2026-09-28T00:01:12Z',
      tasks: [boardTask({ id: 't1', status: 'completed', cost: 0.01 }), boardTask({ id: 't2', status: 'completed', cost: 0.01 })],
    })
    expect(progressSummary(g)).toBe('Done in 1 min 12 s · 2 steps · US$0.02')
  })

  it('reads a failed goal at its failed step', () => {
    const g = goal({
      id: 'g1',
      status: 'failed',
      createdAt: '2026-09-28T00:00:00Z',
      completedAt: '2026-09-28T00:00:40Z',
      tasks: [boardTask({ id: 't1', status: 'completed' }), boardTask({ id: 't2', status: 'failed' }), boardTask({ id: 't3', status: 'pending' })],
    })
    expect(progressSummary(g)).toBe('Failed at step 2 of 3 · 40 s')
  })

  it('reads a stopped goal', () => {
    const g = goal({
      id: 'g1',
      status: 'cancelled',
      createdAt: '2026-09-28T00:00:00Z',
      completedAt: '2026-09-28T00:00:12Z',
      tasks: [boardTask({ id: 't1', status: 'cancelled' })],
    })
    expect(progressSummary(g)).toBe('Stopped · 12 s')
  })
})

describe('choiceMadeFor', () => {
  it('names the agent a later routing message rerouted to', () => {
    const messages = [
      message({ id: 'a', kind: 'routing', detail: {} }),
      message({ id: 'b', kind: 'routing', detail: { rerouteOf: 'a', agents: [{ id: 'x', name: 'Research', instruction: '' }] } }),
    ]
    expect(choiceMadeFor('a', messages)).toBe('Research')
  })

  it('returns null when nothing rerouted it', () => {
    expect(choiceMadeFor('a', [message({ id: 'a', kind: 'routing' })])).toBeNull()
  })
})

describe('routingMessageForGoal and goalHasAnswer', () => {
  it('finds the routing message that started a goal', () => {
    const messages = [
      message({ id: 'a', kind: 'routing', goalId: 'g1' }),
      message({ id: 'b', kind: 'progress', goalId: 'g1' }),
    ]
    expect(routingMessageForGoal('g1', messages)?.id).toBe('a')
  })

  it('is true once an answer for the goal has arrived', () => {
    const messages = [message({ id: 'a', kind: 'answer', goalId: 'g1' })]
    expect(goalHasAnswer('g1', messages)).toBe(true)
    expect(goalHasAnswer('g2', messages)).toBe(false)
  })
})

describe('lastAnswer', () => {
  it('returns the most recent answer message', () => {
    const messages = [message({ id: 'a', kind: 'answer' }), message({ id: 'b', kind: 'text' }), message({ id: 'c', kind: 'answer' })]
    expect(lastAnswer(messages)?.id).toBe('c')
  })

  it('returns undefined with no answer at all', () => {
    expect(lastAnswer([message({ id: 'a', kind: 'text' })])).toBeUndefined()
  })
})

describe('dayLabel', () => {
  const now = new Date('2026-09-28T12:00:00Z')

  it('reads today and yesterday', () => {
    expect(dayLabel(new Date('2026-09-28T12:00:00Z'), now)).toBe('Today')
    expect(dayLabel(new Date('2026-09-27T12:00:00Z'), now)).toBe('Yesterday')
  })

  it('reads an older date this year with its weekday', () => {
    expect(dayLabel(new Date('2026-09-21T12:00:00Z'), now)).toBe('Monday 21 September')
  })

  it('reads a date from another year without a weekday', () => {
    expect(dayLabel(new Date('2025-09-22T12:00:00Z'), now)).toBe('22 September 2025')
  })
})

describe('withDayDividers', () => {
  it('inserts one divider per calendar day, and keeps grouping across it', () => {
    const now = new Date('2026-09-29T12:00:00Z')
    const messages = [
      message({ id: 'a', createdAt: '2026-09-28T12:00:00Z', authorKind: 'user', kind: 'text' }),
      message({ id: 'b', createdAt: '2026-09-29T12:00:00Z', authorKind: 'user', kind: 'text' }),
    ]
    const entries = withDayDividers(messages, now)
    expect(entries.map((e) => e.type)).toEqual(['day', 'message', 'day', 'message'])
    expect(entries[0]).toMatchObject({ type: 'day', label: 'Yesterday' })
    expect(entries[2]).toMatchObject({ type: 'day', label: 'Today' })
  })
})

describe('orphanQuestions', () => {
  it('keeps a pending question with no message of its own', () => {
    const q = runQuestion({ id: 'q1', status: 'pending' })
    expect(orphanQuestions([q], [])).toEqual([q])
  })

  it('drops a question that already has a message', () => {
    const q = runQuestion({ id: 'q1', status: 'pending' })
    const messages = [message({ id: 'm1', kind: 'question', detail: { questionId: 'q1' } })]
    expect(orphanQuestions([q], messages)).toEqual([])
  })

  it('drops a question that is no longer pending', () => {
    const q = runQuestion({ id: 'q1', status: 'answered' })
    expect(orphanQuestions([q], [])).toEqual([])
  })
})

describe('autoAnswerTarget', () => {
  it('targets an orphan question when nothing else has happened since', () => {
    const q = runQuestion({ id: 'q1', createdAt: '2026-09-28T00:05:00Z' })
    const messages = [message({ id: 'm1', kind: 'text', createdAt: '2026-09-28T00:00:00Z' })]
    expect(autoAnswerTarget([q], messages, 'user-1', new Set())?.id).toBe('q1')
  })

  it('targets a question message that is the last item in the thread', () => {
    const q = runQuestion({ id: 'q1' })
    const messages = [
      message({ id: 'm1', kind: 'text' }),
      message({ id: 'm2', kind: 'question', detail: { questionId: 'q1' } }),
    ]
    expect(autoAnswerTarget([q], messages, 'user-1', new Set())?.id).toBe('q1')
  })

  it('returns null once a user message follows the question', () => {
    const q = runQuestion({ id: 'q1' })
    const messages = [
      message({ id: 'm1', kind: 'question', detail: { questionId: 'q1' } }),
      message({ id: 'm2', kind: 'text' }),
    ]
    expect(autoAnswerTarget([q], messages, 'user-1', new Set())).toBeNull()
  })

  it('ignores trailing notices', () => {
    const q = runQuestion({ id: 'q1' })
    const messages = [
      message({ id: 'm1', kind: 'question', detail: { questionId: 'q1' } }),
      message({ id: 'm2', kind: 'notice' }),
    ]
    expect(autoAnswerTarget([q], messages, 'user-1', new Set())?.id).toBe('q1')
  })

  it('returns null once the question is dismissed', () => {
    const q = runQuestion({ id: 'q1' })
    const messages = [message({ id: 'm1', kind: 'question', detail: { questionId: 'q1' } })]
    expect(autoAnswerTarget([q], messages, 'user-1', new Set(['q1']))).toBeNull()
  })

  it('returns null when the viewer cannot answer it', () => {
    const q = runQuestion({ id: 'q1', canAnswer: false })
    const messages = [message({ id: 'm1', kind: 'question', detail: { questionId: 'q1' } })]
    expect(autoAnswerTarget([q], messages, 'user-1', new Set())).toBeNull()
  })

  it('picks the newest of two open questions', () => {
    const older = runQuestion({ id: 'q1', createdAt: '2026-09-28T00:00:00Z' })
    const newer = runQuestion({ id: 'q2', createdAt: '2026-09-28T00:10:00Z' })
    const messages = [
      message({ id: 'm1', kind: 'question', detail: { questionId: 'q1' }, createdAt: '2026-09-28T00:00:00Z' }),
      message({ id: 'm2', kind: 'question', detail: { questionId: 'q2' }, createdAt: '2026-09-28T00:10:00Z' }),
    ]
    expect(autoAnswerTarget([older, newer], messages, 'user-1', new Set())?.id).toBe('q2')
  })
})

describe('composerAnswer', () => {
  it('maps an exact label, ignoring case', () => {
    const q = runQuestion({ id: 'q1' })
    expect(composerAnswer(q, 'managers')).toEqual({ answers: [{ questionId: 'q1', selected: ['Managers'], other: null }] })
  })

  it('maps a number to its option', () => {
    const q = runQuestion({ id: 'q1' })
    expect(composerAnswer(q, '2')).toEqual({ answers: [{ questionId: 'q1', selected: ['Managers'], other: null }] })
  })

  it('falls back to other text for anything else', () => {
    const q = runQuestion({ id: 'q1' })
    expect(composerAnswer(q, 'something else entirely')).toEqual({
      answers: [{ questionId: 'q1', selected: [], other: 'something else entirely' }],
    })
  })

  it('maps a comma list for a multi-select question', () => {
    const q = runQuestion({
      id: 'q1',
      questions: [questionItem({ multiSelect: true, options: [{ label: 'A', description: '' }, { label: 'B', description: '' }, { label: 'C', description: '' }] })],
    })
    expect(composerAnswer(q, 'a, 3')).toEqual({ answers: [{ questionId: 'q1', selected: ['A', 'C'], other: null }] })
  })

  it('becomes a note for a set of more than one question', () => {
    const q = runQuestion({ id: 'q1', questions: [questionItem({ id: 'q1' }), questionItem({ id: 'q2', header: 'Tone' })] })
    expect(composerAnswer(q, 'formal, whole team')).toEqual({ answers: [], note: 'formal, whole team' })
  })
})

describe('messageAuthor', () => {
  const members: Record<string, Member> = {
    'user-2': { userId: 'user-2', displayName: 'Priya Shah', email: 'p@x.com', role: 'employee', status: 'active' },
  }

  it('reads the viewer\'s own message as You', () => {
    expect(messageAuthor(message({ id: 'a', authorId: 'user-1' }), 'user-1', {})).toEqual({ isMe: true, name: 'You' })
  })

  it('reads another member by name', () => {
    expect(messageAuthor(message({ id: 'a', authorId: 'user-2' }), 'user-1', members)).toEqual({
      isMe: false,
      name: 'Priya Shah',
    })
  })

  it('falls back to a generic phrase for an unknown author', () => {
    expect(messageAuthor(message({ id: 'a', authorId: 'user-9' }), 'user-1', members)).toEqual({
      isMe: false,
      name: 'Someone in the workspace',
    })
  })
})

function agent(overrides: Partial<Agent> & Pick<Agent, 'id' | 'key'>): Agent {
  return { name: 'Agent', category: 'operations', status: 'active', revision: 1, ...overrides }
}

describe('suggestionChips', () => {
  it('offers only document questions without task:create', () => {
    const chips = suggestionChips([agent({ id: 'a1', key: 'hr' })], () => false)
    expect(chips).toEqual([
      'What does our leave policy say about carers’ leave?',
      'Where is the checklist for a new starter’s first week?',
      'Which documents cover incident reporting?',
    ])
  })

  it('never names a person', () => {
    const chips = suggestionChips(
      [agent({ id: 'a1', key: 'hr' }), agent({ id: 'a2', key: 'general', fallback: true })],
      () => true,
    )
    for (const chip of chips) {
      expect(chip).not.toMatch(/Priya/)
    }
  })

  it('gives General a chip when it is active', () => {
    const chips = suggestionChips([agent({ id: 'a1', key: 'general', fallback: true })], () => true)
    expect(chips).toContain('Plan a 30-minute team meeting about next month’s rosters')
  })

  it('caps at four chips with no repeats', () => {
    const chips = suggestionChips(
      [
        agent({ id: 'a1', key: 'hr' }),
        agent({ id: 'a2', key: 'engineering-manager' }),
        agent({ id: 'a3', key: 'research' }),
        agent({ id: 'a4', key: 'support', category: 'support' }),
        agent({ id: 'a5', key: 'general', fallback: true }),
      ],
      () => true,
    )
    expect(chips.length).toBeLessThanOrEqual(4)
    expect(new Set(chips).size).toBe(chips.length)
  })
})

describe('conversationText', () => {
  it('turns the thread into plain lines', () => {
    const messages = [
      message({ id: 'a', kind: 'text', authorKind: 'user', content: 'Hi there' }),
      message({ id: 'b', kind: 'answer', agentId: 'r1', content: 'On it' }),
    ]
    expect(conversationText(messages, { r1: { name: 'Research' } })).toBe('You: Hi there\n\nResearch: On it')
  })
})

describe('activeGoals', () => {
  it('keeps only goals whose chain can still change', () => {
    const g1 = goal({ id: 'g1', status: 'running' })
    const g2 = goal({ id: 'g2', status: 'completed' })
    expect(activeGoals([g1, g2])).toEqual([g1])
  })
})

describe('liveStepText', () => {
  function step(overrides: Partial<RunStep> & Pick<RunStep, 'kind'>): RunStep {
    return {
      id: 's1',
      position: 0,
      detail: {},
      promptTokens: 0,
      completionTokens: 0,
      durationMs: 0,
      occurredAt: '2026-09-28T00:00:00Z',
      ...overrides,
    }
  }

  it('reads every step kind', () => {
    expect(liveStepText(undefined)).toBe('Starting')
    expect(liveStepText(step({ kind: 'model_call' }))).toBe('Working on a reply')
    expect(liveStepText(step({ kind: 'tool_call', detail: { tool: 'gmail.send_message' } }))).toBe('Last step: Gmail · send message')
    expect(liveStepText(step({ kind: 'question' }))).toBe('Waiting for an answer')
    expect(liveStepText(step({ kind: 'approval' }))).toBe('Waiting for approval')
    expect(liveStepText(step({ kind: 'handoff', detail: { fromAgentName: 'Research' } }))).toBe('Picking up from Research')
    expect(liveStepText(step({ kind: 'note' }))).toBe('Starting')
  })
})

describe('errorHelp', () => {
  it('links to model routing for a model problem', () => {
    expect(errorHelp('no_model_available')).toEqual({
      text: 'Check the model routing and provider credentials in Model routing.',
      href: '/routing',
    })
  })

  it('explains a used-up budget with no link', () => {
    expect(errorHelp('budget_exceeded')).toEqual({
      text: 'The workspace budget for model spend is used up. An administrator can raise it.',
      href: null,
    })
  })

  it('returns null for an unknown or missing code', () => {
    expect(errorHelp(null)).toBeNull()
    expect(errorHelp('something_else')).toBeNull()
  })
})

describe('canStopGoal and canRetryGoal', () => {
  it('are re-exported from lib/goals', () => {
    const g = goal({ id: 'g1', status: 'running', requestedBy: 'user-1' })
    expect(canStopGoal(g, 'user-1', () => false)).toBe(true)
    expect(canRetryGoal({ ...g, status: 'failed' }, 'user-1', () => true)).toBe(true)
  })
})
