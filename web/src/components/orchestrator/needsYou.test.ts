import { describe, expect, it } from 'vitest'
import { buildNeedsYou } from './needsYou'
import type { Board, BoardApproval, BoardGoal, BoardQueueEntry, BoardTask, RunQuestion } from '../../lib/queries'

function task(overrides: Partial<BoardTask> & { id: string }): BoardTask {
  return {
    agentId: 'agent-1',
    title: 'Task',
    status: 'failed',
    position: 0,
    dependsOn: [],
    attempt: 1,
    maxAttempts: 2,
    result: null,
    failureReason: 'The tool timed out.',
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
    status: 'failed',
    createdAt: '2026-09-28T00:00:00Z',
    completedAt: '2026-09-28T01:00:00Z',
    source: 'manual',
    requestedBy: null,
    conversationId: null,
    scheduleId: null,
    ...overrides,
  }
}

function question(overrides: Partial<RunQuestion> & { id: string }): RunQuestion {
  return {
    runId: 'run-1',
    taskId: null,
    goalId: null,
    conversationId: null,
    agentId: 'agent-1',
    goalTitle: null,
    status: 'pending',
    questions: [{ id: 'q1', header: 'Audience', question: 'Who is this for?', multiSelect: false, options: [] }],
    answer: null,
    answeredBy: null,
    answeredVia: null,
    answeredAt: null,
    requestedBy: null,
    createdAt: '2026-09-28T00:00:00Z',
    expiresAt: '2026-09-29T00:00:00Z',
    closedReason: null,
    canAnswer: true,
    extendable: false,
    runStatus: 'waiting_input',
    ...overrides,
  }
}

function approval(overrides: Partial<BoardApproval> & { id: string }): BoardApproval {
  return {
    runId: 'run-2',
    taskId: null,
    goalId: null,
    agentId: 'agent-1',
    tool: 'gmail.send_message',
    actionClass: 'OUTBOUND',
    summary: 'Send an email',
    requestedAt: '2026-09-28T00:00:00Z',
    expiresAt: '2026-09-29T00:00:00Z',
    requestedBy: null,
    canDecide: true,
    ...overrides,
  }
}

function queueEntry(overrides: Partial<BoardQueueEntry> & { goalId: string; taskId: string }): BoardQueueEntry {
  return {
    goalTitle: 'Goal',
    agentId: 'agent-1',
    position: 0,
    reason: 'agent_paused',
    requestedBy: null,
    source: 'manual',
    createdAt: '2026-09-28T00:00:00Z',
    ...overrides,
  }
}

function board(overrides: Partial<Board> = {}): Board {
  return {
    generatedAt: '2026-09-28T02:00:00Z',
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
    goals: [],
    queue: [],
    timeline: [],
    questions: [],
    approvals: [],
    failedToday: [],
    ...overrides,
  }
}

describe('buildNeedsYou', () => {
  it('orders questions and approvals by how soon they close', () => {
    const soon = question({ id: 'q-soon', requestedBy: 'me', expiresAt: '2026-09-28T03:00:00Z' })
    const later = question({ id: 'q-later', requestedBy: 'me', expiresAt: '2026-09-28T06:00:00Z' })
    const items = buildNeedsYou(board({ questions: [later, soon] }), { me: 'me', scope: 'forMe' })
    expect(items.map((item) => item.id)).toEqual(['q-soon', 'q-later'])
  })

  it('puts failed goals after questions and approvals, newest first', () => {
    const q = question({ id: 'q1', requestedBy: 'me' })
    const failedOld = goal({ id: 'g-old', requestedBy: 'me', completedAt: '2026-09-28T01:00:00Z', tasks: [task({ id: 't1' })] })
    const failedNew = goal({ id: 'g-new', requestedBy: 'me', completedAt: '2026-09-28T02:00:00Z', tasks: [task({ id: 't2' })] })
    const items = buildNeedsYou(board({ questions: [q], failedToday: [failedOld, failedNew] }), { me: 'me', scope: 'forMe' })
    expect(items.map((item) => item.kind)).toEqual(['question', 'failed', 'failed'])
    expect(items.slice(1).map((item) => item.id)).toEqual(['g-new', 'g-old'])
  })

  it('keeps only my own questions and my own failed goals under For me', () => {
    const mine = question({ id: 'mine', requestedBy: 'me' })
    const someoneElses = question({ id: 'someone-elses', requestedBy: 'them' })
    const items = buildNeedsYou(board({ questions: [mine, someoneElses] }), { me: 'me', scope: 'forMe' })
    expect(items.map((item) => item.id)).toEqual(['mine'])
  })

  it('shows every question under Everyone, whoever asked', () => {
    const mine = question({ id: 'mine', requestedBy: 'me' })
    const someoneElses = question({ id: 'someone-elses', requestedBy: 'them' })
    const items = buildNeedsYou(board({ questions: [mine, someoneElses] }), { me: 'me', scope: 'everyone' })
    expect(items.map((item) => item.id).sort()).toEqual(['mine', 'someone-elses'])
  })

  it('keeps an approval under For me by whether I can decide it, not who asked for the work', () => {
    const decidable = approval({ id: 'a-mine', requestedBy: 'them', canDecide: true })
    const notMine = approval({ id: 'a-not-mine', requestedBy: 'me', canDecide: false })
    const items = buildNeedsYou(board({ approvals: [decidable, notMine] }), { me: 'me', scope: 'forMe' })
    expect(items.map((item) => item.id)).toEqual(['a-mine'])
  })

  it('reads an approval in words, whichever way the tool is named', () => {
    const dotted = approval({
      id: 'a-dotted',
      tool: 'gmail.send_message',
      summary: 'Send something outside the workspace using gmail.send_message',
    })
    const wire = approval({
      id: 'a-wire',
      tool: 'slack__post_message',
      summary: 'Send something outside the workspace using slack__post_message',
    })
    const noTool = approval({ id: 'a-none', tool: null, summary: 'Send an email' })

    const items = buildNeedsYou(board({ approvals: [dotted, wire, noTool] }), { me: 'me', scope: 'everyone' })

    const summaries = Object.fromEntries(items.map((item) => [item.id, (item as { summary: string }).summary]))
    expect(summaries['a-dotted']).toBe('Send something outside the workspace using Gmail · send message')
    expect(summaries['a-wire']).toBe('Send something outside the workspace using Slack · post message')
    expect(summaries['a-none']).toBe('Send an email')
  })

  it('reads a failed goal from failedToday even when the window would otherwise exclude it', () => {
    const failed = goal({ id: 'g1', requestedBy: 'me', tasks: [task({ id: 't1', failureReason: 'The tool timed out.' })] })
    const items = buildNeedsYou(board({ window: 'PT1H', windowMinutes: 60, goals: [], failedToday: [failed] }), {
      me: 'me',
      scope: 'forMe',
    })
    expect(items).toHaveLength(1)
    expect(items[0]).toMatchObject({ kind: 'failed', id: 'g1', reason: 'The tool timed out.' })
  })

  it('makes one held item per goal, not one per queue entry', () => {
    const entries = [
      queueEntry({ goalId: 'g1', taskId: 't1', requestedBy: 'me' }),
      queueEntry({ goalId: 'g1', taskId: 't2', requestedBy: 'me' }),
    ]
    const items = buildNeedsYou(board({ queue: entries }), { me: 'me', scope: 'forMe' })
    expect(items.filter((item) => item.kind === 'held')).toHaveLength(1)
  })

  it('ignores a queued goal that is not held on a paused agent', () => {
    const entries = [queueEntry({ goalId: 'g1', taskId: 't1', reason: 'ready', requestedBy: 'me' })]
    const items = buildNeedsYou(board({ queue: entries }), { me: 'me', scope: 'forMe' })
    expect(items).toHaveLength(0)
  })
})
