// @find: tests for chatModel, chat model, group messages, message grouping, new message ids, answer announce, read aloud, goal chain, thread rules, pure functions
// @what: Automated tests for chatModel.
// @flow: Run with the web test runner; covers chatModel.
import { describe, expect, it } from 'vitest'
import {
  retryLeftInBox,
  titleFromFirstMessage,
  isRoutingLastMessage,
  activeGoals,
  autoAnswerTarget,
  canRetryGoal,
  canStopGoal,
  choiceMadeFor,
  composerAnswer,
  conversationText,
  currentTaskIndex,
  dayLabel,
  describeAgent,
  draftKey,
  errorHelp,
  goalChainActive,
  goalHasAnswer,
  goalTarget,
  groupMessages,
  isGroupedWithPrevious,
  isLegacyDraftKey,
  lastAnswer,
  liveStepText,
  messageAuthor,
  newestPosition,
  newMessageIds,
  orphanQuestions,
  passageLink,
  pendingEchoed,
  progressMessageForGoal,
  progressSummary,
  readyAnswers,
  removeLegacyDrafts,
  resendText,
  routingModeLabel,
  routingMessageForGoal,
  routingPassages,
  routingSummary,
  sendsAsNewRequest,
  sourcesForAnswer,
  taskStepStatus,
  withDayDividers,
  workStripSummary,
} from './chatModel'
import type { SourcePassage } from './chatModel'
import type { BoardGoal, BoardTask, ChatMessage, Member, RunQuestion, RunStep, Task } from '../../lib/queries'

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

  it('names the documents an answer draws on', () => {
    const m = message({
      id: 'a',
      kind: 'routing',
      detail: {
        mode: 'model',
        agents: [{ id: 'general', name: 'General Employee', instruction: '' }],
        reason: 'Found 5 passages in SIH deck.pptx. It is a general question.',
      },
    })
    expect(routingSummary(m, names)).toBe('Sent to General Employee, using 5 passages from SIH deck.pptx')
  })

  it('names the documents from the recorded passages, once each, rather than from the reason sentence', () => {
    const m = message({
      id: 'a',
      kind: 'routing',
      detail: {
        mode: 'model',
        agents: [{ id: 'general', name: 'General Employee', instruction: '' }],
        // The sentence and the passages disagree on purpose: the structured field is what counts.
        reason: 'Found 9 passages in Something else. The model chose it.',
        passages: [
          passage({ chunkId: 'c1', documentTitle: 'Refund policy' }),
          passage({ chunkId: 'c2', documentTitle: 'Refund policy', pageNumber: 4 }),
          passage({ chunkId: 'c3', documentTitle: 'Support handbook' }),
        ],
      },
    })
    expect(routingSummary(m, names)).toBe('Sent to General Employee, using 3 passages from Refund policy, Support handbook')
  })

  it('says "passage" for one, and names at most three documents', () => {
    const one = message({
      id: 'a',
      kind: 'routing',
      detail: { mode: 'model', agents: [{ id: 'research', name: 'Research', instruction: '' }], passages: [passage({ chunkId: 'c1' })] },
    })
    expect(routingSummary(one, names)).toBe('Sent to Research, using 1 passage from Refund policy')

    const many = message({
      id: 'b',
      kind: 'routing',
      detail: {
        mode: 'model',
        agents: [{ id: 'research', name: 'Research', instruction: '' }],
        passages: ['A', 'B', 'C', 'D'].map((title, index) => passage({ chunkId: `c${index}`, documentTitle: title })),
      },
    })
    expect(routingSummary(many, names)).toBe('Sent to Research, using 4 passages from A, B, C')
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

function passage(overrides: Partial<SourcePassage> = {}): SourcePassage {
  return {
    chunkId: 'chunk-1',
    documentId: 'doc-1',
    sourceId: 'source-1',
    documentTitle: 'Refund policy',
    uri: null,
    pageNumber: null,
    heading: null,
    content: 'Refunds are issued within five business days.',
    score: 0.9,
    ...overrides,
  }
}

describe('routingPassages', () => {
  it('reads the passages a routing message recorded, and none from one that did not', () => {
    const recorded = message({ id: 'r', kind: 'routing', detail: { passages: [passage()] } })
    expect(routingPassages(recorded)).toHaveLength(1)
    expect(routingPassages(message({ id: 'r', kind: 'routing' }))).toEqual([])
    expect(routingPassages(undefined)).toEqual([])
  })
})

describe('sourcesForAnswer', () => {
  const passages = [passage({ chunkId: 'c1' }), passage({ chunkId: 'c2', documentTitle: 'Handbook' })]
  const routing = (agents: number, extra: Partial<ChatMessage['detail']> = {}) =>
    message({
      id: 'routing',
      kind: 'routing',
      goalId: 'g1',
      detail: {
        agents: Array.from({ length: agents }, (_, index) => ({ id: `a${index}`, name: `Agent ${index}`, instruction: '' })),
        passages,
        grounded: true,
        ...extra,
      },
    })
  const answerFor = (taskId: string | undefined, goalId: string | null = 'g1') =>
    message({ id: `answer-${taskId ?? 'none'}`, kind: 'answer', goalId, detail: taskId ? { taskId } : {} })
  const chain = { tasks: [boardTask({ id: 't2', position: 1 }), boardTask({ id: 't1', position: 0 })] }

  it('shows the passages under the answer of the goal\'s first step, in the order they were read', () => {
    expect(sourcesForAnswer(answerFor('t1'), [routing(2)], chain).map((p) => p.chunkId)).toEqual(['c1', 'c2'])
  })

  it('shows them under no later step of a chain, which was given the earlier work and not the passages', () => {
    expect(sourcesForAnswer(answerFor('t2'), [routing(2)], chain)).toEqual([])
  })

  it('without the goal, trusts a routing message that names one agent and not one that names several', () => {
    expect(sourcesForAnswer(answerFor('t1'), [routing(1)])).toHaveLength(2)
    expect(sourcesForAnswer(answerFor('t1'), [routing(2)])).toEqual([])
    expect(sourcesForAnswer(answerFor(undefined), [routing(1)], chain)).toHaveLength(2)
  })

  it('shows nothing for work that was given no passages, an answer with no goal, or another goal', () => {
    expect(sourcesForAnswer(answerFor('t1'), [routing(1, { passages: [] })], chain)).toEqual([])
    expect(sourcesForAnswer(answerFor('t1', null), [routing(1)], chain)).toEqual([])
    expect(sourcesForAnswer(answerFor('t1', 'g2'), [routing(1)], chain)).toEqual([])
    expect(sourcesForAnswer(answerFor('t1'), [], chain)).toEqual([])
  })

  it('is only ever for an answer message', () => {
    const text = message({ id: 'x', kind: 'text', goalId: 'g1', detail: { taskId: 't1' } })
    expect(sourcesForAnswer(text, [routing(1)], chain)).toEqual([])
  })
})

describe('passageLink', () => {
  it('goes to the source in Knowledge when the person may read Knowledge', () => {
    expect(passageLink(passage({ sourceId: 'abc', uri: 'https://example.org/policy' }), true)).toEqual({
      href: '/knowledge/abc',
      label: 'Open in Knowledge',
      external: false,
    })
  })

  it('falls back to the passage\'s own web address without that permission, or without a source', () => {
    const link = { href: 'https://example.org/policy', label: 'Open source', external: true }
    expect(passageLink(passage({ sourceId: 'abc', uri: 'https://example.org/policy' }), false)).toEqual(link)
    expect(passageLink(passage({ sourceId: null, uri: 'https://example.org/policy' }), true)).toEqual(link)
    // A passage recorded before the knowledge service sent a source has no such field at all.
    const withoutSource = passage({ uri: 'http://example.org/policy' })
    delete withoutSource.sourceId
    expect(passageLink(withoutSource, true)).toEqual({ ...link, href: 'http://example.org/policy' })
  })

  it('has no link when the only address is not a web one, or there is none', () => {
    expect(passageLink(passage({ sourceId: null, uri: 'file:///policy.pdf' }), true)).toBeNull()
    expect(passageLink(passage({ sourceId: null, uri: 'javascript:alert(1)' }), true)).toBeNull()
    expect(passageLink(passage({ sourceId: 'abc', uri: null }), false)).toBeNull()
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
    expect(liveStepText(step({ kind: 'memory_read' }))).toBe('Last step: read its notes')
    expect(liveStepText(step({ kind: 'knowledge_query' }))).toBe('Last step: searched the documents')
  })

  // Regression: a model call is recorded only after the model replies, so a run waiting on its
  // first reply has only its instruction note, and the card read "Starting" for the whole wait.
  it('reads a run waiting on its first reply as working, never as starting', () => {
    expect(liveStepText(step({ kind: 'note', detail: { type: 'instruction' } }))).toBe('Working on a reply')
    expect(liveStepText(step({ kind: 'error' }))).toBe('Working on a reply')
    expect(liveStepText(step({ kind: 'something_new' }))).toBe('Working on a reply')
  })
})

describe('errorHelp', () => {
  it('links to model routing for a model problem', () => {
    expect(errorHelp('no_model_available')).toEqual({
      text: 'Check the model routing and provider credentials in Model routing.',
      href: '/routing',
    })
  })

  it('explains a used-up budget and links to where it is raised', () => {
    expect(errorHelp('budget_exceeded')).toEqual({
      text: 'The workspace budget for model spend is used up. An administrator can raise it.',
      href: '/analytics#budget',
    })
  })

  it('says a platform service was briefly unreachable, with no link', () => {
    expect(errorHelp('dependency_unavailable')).toEqual({
      text: 'A platform service was briefly unreachable. Try again in a minute.',
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

describe('progressSummary while work is parked', () => {
  it('says a folded card waits for an approval', () => {
    const g = goal({ id: 'g1', tasks: [boardTask({ id: 't1', status: 'waiting_approval' })] })
    expect(progressSummary(g)).toBe('Waiting for your approval')
  })

  it('says a folded card waits for an answer', () => {
    const g = goal({ id: 'g1', tasks: [boardTask({ id: 't1', status: 'waiting_input' })] })
    expect(progressSummary(g)).toBe('Waiting for an answer')
  })

  it('puts an approval first when one task waits on each', () => {
    const g = goal({
      id: 'g1',
      tasks: [boardTask({ id: 't1', status: 'waiting_input' }), boardTask({ id: 't2', status: 'waiting_approval', position: 1 })],
    })
    expect(progressSummary(g)).toBe('Waiting for your approval')
  })
})

describe('goalTarget', () => {
  const thread = [
    message({ id: 'm1', position: 0 }),
    message({ id: 'm2', position: 1, kind: 'routing', goalId: 'g1', authorKind: 'coordinator' }),
    message({ id: 'm3', position: 2, kind: 'progress', goalId: 'g1', authorKind: 'coordinator' }),
  ]

  it('finds the progress message that tracks a goal', () => {
    expect(progressMessageForGoal('g1', thread)?.id).toBe('m3')
    expect(progressMessageForGoal('g2', thread)).toBeUndefined()
  })

  it("points at the progress card's own message anchor when it is loaded", () => {
    expect(goalTarget('g1', thread, 'approval')).toEqual({ elementId: 'm-m3' })
  })

  it('falls back to the approvals queue, or the goal in the Orchestrator, when it is not', () => {
    expect(goalTarget('g2', thread, 'approval')).toEqual({ href: '/approvals' })
    expect(goalTarget('g2', thread, 'progress')).toEqual({ href: '/orchestrator?goal=g2' })
  })
})

describe('workStripSummary', () => {
  const running = (id: string) => goal({ id, tasks: [boardTask({ id: `${id}-t`, status: 'running' })] })
  const parked = (id: string, status: 'waiting_approval' | 'waiting_input') =>
    goal({ id, tasks: [boardTask({ id: `${id}-t`, status })] })

  it('counts running work when nothing waits on a person', () => {
    expect(workStripSummary([running('g1')]).text).toBe('1 running')
    expect(workStripSummary([running('g1'), running('g2')]).text).toBe('2 running')
  })

  it('leads with an approval, then an answer', () => {
    expect(workStripSummary([running('g1'), parked('g2', 'waiting_approval')])).toEqual({
      text: '1 needs your approval',
      approvals: 1,
      answers: 0,
    })
    expect(workStripSummary([running('g1'), parked('g2', 'waiting_input')]).text).toBe('1 needs your answer')
    expect(workStripSummary([parked('g1', 'waiting_approval'), parked('g2', 'waiting_approval')]).text).toBe(
      '2 need your approval',
    )
  })
})

describe('sendsAsNewRequest', () => {
  const auto = { auto: true, agentId: 'agent-1' }
  const explicit = { auto: false, agentId: 'agent-1' }

  it('sends an automatic answer that mentions another agent as a new request', () => {
    expect(sendsAsNewRequest(auto, ['agent-2'])).toBe(true)
    expect(sendsAsNewRequest(auto, ['agent-1', 'agent-2'])).toBe(true)
  })

  it('still answers when only the asking agent is mentioned, or nobody is', () => {
    expect(sendsAsNewRequest(auto, ['agent-1'])).toBe(false)
    expect(sendsAsNewRequest(auto, [])).toBe(false)
  })

  it('always answers a reply the person chose, whoever it mentions', () => {
    expect(sendsAsNewRequest(explicit, ['agent-2'])).toBe(false)
  })

  it('is never a redirect with no question targeted', () => {
    expect(sendsAsNewRequest(null, ['agent-2'])).toBe(false)
  })
})

describe('pendingEchoed', () => {
  const pending = { text: '  Draft the Q3 quote ', afterPosition: 4 }

  it('is true once the stored copy of the message arrives after the send started', () => {
    const thread = [message({ id: 'm5', position: 5, content: 'Draft the Q3 quote', authorId: 'user-1' })]
    expect(pendingEchoed(thread, pending, 'user-1')).toBe(true)
  })

  it('ignores the same words sent earlier, by someone else, or not as a text message', () => {
    expect(pendingEchoed([message({ id: 'm3', position: 3, content: 'Draft the Q3 quote' })], pending, 'user-1')).toBe(false)
    expect(
      pendingEchoed([message({ id: 'm5', position: 5, content: 'Draft the Q3 quote', authorId: 'user-2' })], pending, 'user-1'),
    ).toBe(false)
    expect(
      pendingEchoed(
        [message({ id: 'm5', position: 5, content: 'Draft the Q3 quote', kind: 'routing', authorKind: 'coordinator' })],
        pending,
        'user-1',
      ),
    ).toBe(false)
  })

  it('treats an empty thread as starting before position 0', () => {
    expect(newestPosition([])).toBe(-1)
    expect(newestPosition([message({ id: 'a', position: 7 }), message({ id: 'b', position: 3 })])).toBe(7)
    const first = message({ id: 'm0', position: 0, content: 'Hi', authorId: 'user-1' })
    expect(pendingEchoed([first], { text: 'Hi', afterPosition: -1 }, 'user-1')).toBe(true)
  })
})

describe('resendText', () => {
  const ERROR = 'The coordinator could not decide who takes this. Try again, or mention an agent with @.'
  const error = (overrides: Partial<ChatMessage> = {}) =>
    message({ id: 'err', position: 5, kind: 'error', authorKind: 'coordinator', authorId: null, content: ERROR, ...overrides })

  it("puts back the request the error recorded", () => {
    expect(resendText(error({ detail: { reason: ERROR, requestText: 'Compare the three quotes' } }), [])).toBe(
      'Compare the three quotes',
    )
  })

  it("uses the goal's routing request for an agent's own failure", () => {
    const routing = message({
      id: 'r1',
      position: 2,
      kind: 'routing',
      authorKind: 'coordinator',
      goalId: 'g1',
      detail: { requestText: 'Summarise the roster' },
    })
    expect(resendText(error({ goalId: 'g1', content: 'The agent stopped.' }), [routing])).toBe('Summarise the roster')
  })

  it('falls back to the nearest earlier message the person wrote', () => {
    const thread = [
      message({ id: 'u1', position: 1, content: 'First request' }),
      message({ id: 'u2', position: 3, content: 'Second request' }),
      message({ id: 'u3', position: 7, content: 'A later message' }),
    ]
    expect(resendText(error(), thread)).toBe('Second request')
  })

  it('never offers the error sentence itself, and offers nothing when no request is found', () => {
    const thread = [message({ id: 'u1', position: 1, content: ERROR })]
    expect(resendText(error({ detail: { requestText: ERROR } }), thread)).toBeNull()
    expect(resendText(error(), [])).toBeNull()
    for (const candidate of [error(), error({ detail: { requestText: '  ' } })]) {
      expect(resendText(candidate, [error()])).not.toBe(ERROR)
    }
  })
})

describe('draftKey', () => {
  it('keeps a draft per person and per conversation', () => {
    expect(draftKey('user-1', 'c1')).toBe('chat.draft.user-1.c1')
    expect(draftKey('user-1', null)).toBe('chat.draft.user-1.new')
  })

  it('recognises only the keys from before drafts were per person', () => {
    expect(isLegacyDraftKey('chat.draft.new')).toBe(true)
    expect(isLegacyDraftKey('chat.draft.c1')).toBe(true)
    expect(isLegacyDraftKey(draftKey('user-1', 'c1'))).toBe(false)
    expect(isLegacyDraftKey('chat.details')).toBe(false)
  })

  it('removes the old unscoped drafts and nothing else', () => {
    const items = new Map([
      ['chat.draft.new', '"Someone else typed this"'],
      ['chat.draft.c1', '"And this"'],
      ['chat.draft.user-1.c1', '"Mine"'],
      ['chat.details', '"auto"'],
    ])
    removeLegacyDrafts({
      get length() {
        return items.size
      },
      key: (index) => [...items.keys()][index] ?? null,
      removeItem: (key) => void items.delete(key),
    })
    expect([...items.keys()]).toEqual(['chat.draft.user-1.c1', 'chat.details'])
  })
})

describe('describeAgent', () => {
  it('turns second-person instructions into a third-person description', () => {
    expect(describeAgent('You triage support tickets by urgency. Never promise refunds.', 'Support')).toBe('Triages support tickets by urgency.')
    expect(describeAgent("You are the workspace's General Employee: you take any request no one else fits.", 'Operations')).toBe(
      'Takes any request no one else fits.',
    )
    expect(describeAgent('You reply to customers', 'Support')).toBe('Replies to customers')
    expect(describeAgent('You keep tickets current, summarise open pull requests, and write the note.', 'Engineering')).toBe(
      'Keeps tickets current, summarises open pull requests, and writes the note.',
    )
  })

  it('keeps a description already in the third person, and falls back when there is none to use', () => {
    expect(describeAgent('Reconciles invoices against purchase orders.', 'Operations')).toBe('Reconciles invoices against purchase orders.')
    expect(describeAgent('', 'Growth')).toBe('Growth')
    expect(describeAgent('You are a helpful assistant.', 'Engineering')).toBe('Engineering')
  })
})

describe('isRoutingLastMessage', () => {
  const user = { authorKind: 'user', kind: 'text' } as ChatMessage
  const routing = { authorKind: 'coordinator', kind: 'routing' } as ChatMessage
  const active = { status: 'running', tasks: [] } as unknown as BoardGoal

  it('is true while the server routes the person\'s newest message, so a reload still shows it', () => {
    expect(isRoutingLastMessage([routing, user], [], 'working')).toBe(true)
  })

  it('is false once routed, while a goal runs, or when nothing is in progress', () => {
    expect(isRoutingLastMessage([user, routing], [], 'working')).toBe(false)
    expect(isRoutingLastMessage([user], [active], 'working')).toBe(false)
    expect(isRoutingLastMessage([user], [], 'idle')).toBe(false)
    expect(isRoutingLastMessage([], [], 'working')).toBe(false)
  })
})

describe('titleFromFirstMessage', () => {
  it('keeps a short message whole and cuts a long one at a word, as the server does', () => {
    expect(titleFromFirstMessage('  Email   Jane about the refund ')).toBe('Email Jane about the refund')
    const long = 'Email jane@example.com to tell her that her refund for order 4471 has been processed'
    const title = titleFromFirstMessage(long)
    expect(title).toBe('Email jane@example.com to tell her that her refund for')
    expect(title.length).toBeLessThanOrEqual(60)
  })
})

describe('retryLeftInBox', () => {
  it('is true only when the box still holds exactly the words that were sent again', () => {
    expect(retryLeftInBox('What is 12 x 12? ', 'What is 12 x 12?')).toBe(true)
    expect(retryLeftInBox('What is 12 x 12? And 13?', 'What is 12 x 12?')).toBe(false)
    expect(retryLeftInBox('', 'What is 12 x 12?')).toBe(false)
    expect(retryLeftInBox(undefined, 'x')).toBe(false)
  })
})

