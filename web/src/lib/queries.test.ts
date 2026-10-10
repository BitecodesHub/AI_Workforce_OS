// @find: tests for queries, react-query hooks tests, conversation polling, board polling, chat merge, goal pages, run list polling, questions defaults, invalidateWork, deprecated useConversations
// @what: Unit tests for the data hooks in queries.ts: poll intervals, thread merging, paged lists and cache invalidation.
// @flow: Runs under vitest with mocked api(); exercises hooks from queries.ts used by the Chat, Orchestrator, Runs and Tasks pages.
import { QueryClient, QueryClientProvider, type InfiniteData } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { createElement } from 'react'
import type { ReactElement, ReactNode } from 'react'
import { afterEach, describe, expect, it, vi, type MockInstance } from 'vitest'
import { ApiError } from './api'
import {
  boardPollMs,
  conversationPollMs,
  deltaMissesHeldWork,
  invalidateWork,
  mergeConversationDetail,
  settleWithWholeRead,
  mergeFirstPage,
  useBoard,
  useConversation,
  useConversations,
  useAgents,
  useCreateGoal,
  useCreateRun,
  useCreateSchedule,
  useGoalPages,
  useQuestions,
  useRunList,
  useRunSteps,
} from './queries'
import type {
  Board,
  BoardApproval,
  BoardGoal,
  BoardTask,
  ChatMessage,
  Conversation,
  ConversationDetail,
  Run,
  RunQuestion,
} from './queries'

/*
 * conversationPollMs and boardPollMs are pure and checked directly. questionRow, approvalSummaryRow,
 * conversationRow and boardRow are internal to this module (not exported): a response missing every
 * optional field must still come back with the documented defaults filled in, so they are checked
 * through the hooks that call them, against a server response with those fields left out - exactly
 * what non_null inclusion sends today.
 */

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

/** One QueryClient per test, wired to a fresh QueryClientProvider, with retries off so a failed
 * fetch settles immediately instead of being retried into a timeout. */
function wrapper(): (props: { children: ReactNode }) => ReactElement {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }) => createElement(QueryClientProvider, { client }, children)
}

/** The same wrapper, with the client in hand, for tests that read or change the cache. */
function wrapperWith(client: QueryClient): (props: { children: ReactNode }) => ReactElement {
  return ({ children }) => createElement(QueryClientProvider, { client }, children)
}

function stubFetch(body: unknown) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(body)))
}

afterEach(() => {
  vi.unstubAllGlobals()
})

function task(status: string): BoardTask {
  return {
    id: 't1',
    agentId: 'a1',
    title: 'Draft the rota',
    status,
    position: 0,
    dependsOn: [],
    attempt: 1,
    maxAttempts: 3,
    result: null,
    failureReason: null,
    startedAt: null,
    completedAt: null,
    runId: null,
    runStatus: null,
    stepCount: null,
    cost: null,
  }
}

function goal(status: string, tasks: BoardTask[]): BoardGoal {
  return {
    id: 'g1',
    title: 'Plan the offsite',
    description: '',
    status,
    createdAt: '2026-09-28T00:00:00Z',
    completedAt: null,
    source: 'manual',
    requestedBy: 'u1',
    conversationId: 'c1',
    scheduleId: null,
    tasks,
  }
}

function question(status: RunQuestion['status']): RunQuestion {
  return {
    id: 'q1',
    runId: 'r1',
    taskId: null,
    goalId: 'g1',
    conversationId: 'c1',
    agentId: 'a1',
    goalTitle: 'Plan the offsite',
    status,
    questions: [],
    answer: null,
    answeredBy: null,
    answeredVia: null,
    answeredAt: null,
    requestedBy: 'u1',
    createdAt: '2026-09-28T00:00:00Z',
    expiresAt: '2026-09-29T00:00:00Z',
    closedReason: null,
    canAnswer: true,
    extendable: false,
    runStatus: 'waiting_input',
  }
}

function approval(partial: Partial<BoardApproval> = {}): BoardApproval {
  return {
    id: 'ap1',
    runId: 'r1',
    taskId: null,
    goalId: null,
    agentId: 'a1',
    tool: 'gmail.send_message',
    actionClass: 'OUTBOUND',
    summary: 'Send an email',
    requestedAt: '2026-09-28T00:00:00Z',
    expiresAt: '2026-09-29T00:00:00Z',
    requestedBy: 'u1',
    canDecide: true,
    ...partial,
  }
}

function board(partial: Partial<Board> = {}): Board {
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
    goals: [],
    queue: [],
    timeline: [],
    questions: [],
    approvals: [],
    failedToday: [],
    ...partial,
  }
}

function conversation(partial: Partial<Conversation> = {}): Conversation {
  return {
    id: 'c1',
    title: 'Plan the offsite',
    createdBy: 'u1',
    createdAt: '2026-09-28T00:00:00Z',
    updatedAt: '2026-09-28T00:00:00Z',
    lastMessagePreview: 'Hello there',
    messageCount: 3,
    pinned: false,
    archived: false,
    activity: 'idle',
    canManage: true,
    unread: false,
    match: null,
    ...partial,
  }
}

function detail(goals: BoardGoal[], questions: RunQuestion[]): ConversationDetail {
  return { conversation: conversation(), messages: [], goals, questions, hasEarlier: false }
}

/** A task with the fields the poll rules read; the rest are filled in as a quiet task would have them. */
function taskAt(id: string, position: number, status: string, extra: Partial<BoardTask> = {}): BoardTask {
  return { ...task(status), id, position, ...extra }
}

describe('conversationPollMs', () => {
  it('does not poll without a conversation yet', () => {
    expect(conversationPollMs(undefined)).toBe(false)
  })

  it('does not poll once everything is idle', () => {
    expect(conversationPollMs(detail([goal('completed', [task('completed')])], []))).toBe(false)
  })

  it('polls every 3 s while a goal is actively running', () => {
    expect(conversationPollMs(detail([goal('running', [task('running')])], []))).toBe(3_000)
  })

  it('polls every 3 s while a task is ready to start', () => {
    expect(conversationPollMs(detail([goal('running', [task('ready')])], []))).toBe(3_000)
  })

  it('polls every 3 s while a goal is still being planned and has no tasks yet', () => {
    expect(conversationPollMs(detail([goal('planning', [])], []))).toBe(3_000)
  })

  it('polls every 10 s while a task is only parked for an approval', () => {
    expect(conversationPollMs(detail([goal('waiting', [task('waiting_approval')])], []))).toBe(10_000)
  })

  it('polls every 10 s while a question is pending, even with no active goal', () => {
    expect(conversationPollMs(detail([], [question('pending')]))).toBe(10_000)
  })

  it('polls every 3 s while a sibling still runs, although another task is parked', () => {
    const parked = taskAt('t1', 0, 'waiting_approval')
    const running = taskAt('t2', 0, 'running')
    expect(conversationPollMs(detail([goal('running', [parked, running])], []))).toBe(3_000)
  })

  it('polls every 10 s when the only task left to run is waiting behind a parked one', () => {
    const parked = taskAt('t1', 0, 'waiting_approval')
    const behind = taskAt('t2', 1, 'pending')
    expect(conversationPollMs(detail([goal('waiting', [parked, behind])], []))).toBe(10_000)
  })

  it('polls every 3 s when the next task is free to start because the earlier ones have finished', () => {
    const done = taskAt('t1', 0, 'completed')
    const next = taskAt('t2', 1, 'pending')
    expect(conversationPollMs(detail([goal('running', [done, next])], []))).toBe(3_000)
  })

  it('follows dependsOn rather than position when a task names what it waits on', () => {
    const parked = taskAt('t1', 0, 'waiting_approval')
    const done = taskAt('t2', 1, 'completed')
    const independent = taskAt('t3', 2, 'pending', { dependsOn: ['t2'] })
    const dependent = taskAt('t4', 3, 'pending', { dependsOn: ['t1'] })
    expect(conversationPollMs(detail([goal('running', [parked, done, independent])], []))).toBe(3_000)
    expect(conversationPollMs(detail([goal('waiting', [parked, dependent])], []))).toBe(10_000)
  })
})

describe('boardPollMs', () => {
  const agent = (id: string, status: string, runningRunIds: string[] = []) => ({
    id,
    name: 'Agent',
    category: 'people',
    status,
    fallback: false,
    runningRunIds,
    waitingRunIds: [],
    askingRunIds: [],
    queued: 0,
  })

  it('polls every 15 s without a board yet', () => {
    expect(boardPollMs(undefined)).toBe(15_000)
  })

  it('polls every 15 s when nothing is active, pending or waiting on a decision', () => {
    expect(boardPollMs(board())).toBe(15_000)
  })

  it('polls every 3 s while a task is running', () => {
    expect(boardPollMs(board({ goals: [goal('running', [task('running')])] }))).toBe(3_000)
  })

  it('polls every 3 s while a task is waiting to start and nothing holds it back', () => {
    expect(boardPollMs(board({ goals: [goal('running', [task('pending')])] }))).toBe(3_000)
    expect(boardPollMs(board({ goals: [goal('running', [task('ready')])] }))).toBe(3_000)
  })

  it('polls every 3 s while a goal is being planned and has no tasks yet', () => {
    expect(boardPollMs(board({ goals: [goal('planning', [])] }))).toBe(3_000)
  })

  it('polls every 3 s while a run is running, including one started straight on an agent', () => {
    expect(boardPollMs(board({ agents: [agent('a1', 'active', ['r9'])] }))).toBe(3_000)
    expect(boardPollMs(board({ stats: { ...board().stats, running: 1 } }))).toBe(3_000)
  })

  it('polls every 15 s while only an approval is waiting on someone, however long it waits', () => {
    const parked = goal('waiting', [task('waiting_approval')])
    expect(boardPollMs(board({ goals: [parked], approvals: [approval()] }))).toBe(15_000)
  })

  it('polls every 15 s while only a question is pending', () => {
    const asking = goal('waiting', [task('waiting_input')])
    expect(boardPollMs(board({ goals: [asking], questions: [question('pending')] }))).toBe(15_000)
  })

  it('polls every 15 s when the only task left to run waits behind one parked for an approval', () => {
    const parked = taskAt('t1', 0, 'waiting_approval')
    const behind = taskAt('t2', 1, 'pending')
    expect(boardPollMs(board({ goals: [goal('waiting', [parked, behind])], approvals: [approval()] }))).toBe(15_000)
  })

  it('polls every 15 s when the task waiting to start is held behind a paused agent', () => {
    const held = taskAt('t1', 0, 'pending', { agentId: 'a1' })
    expect(boardPollMs(board({ goals: [goal('running', [held])], agents: [agent('a1', 'paused')] }))).toBe(15_000)
  })

  it('polls every 3 s while a task still runs next to one that is parked', () => {
    const parked = taskAt('t1', 0, 'waiting_approval')
    const running = taskAt('t2', 0, 'running')
    expect(boardPollMs(board({ goals: [goal('running', [parked, running])], approvals: [approval()] }))).toBe(3_000)
  })

  it('ignores a finished goal, whatever state its tasks were left in', () => {
    expect(boardPollMs(board({ goals: [goal('completed', [task('pending')])] }))).toBe(15_000)
  })
})

describe('useQuestions fills every default questionRow leaves out', () => {
  it('restores the nulls, false flags and unknown run status a minimal response omits', async () => {
    stubFetch([
      {
        id: 'q1',
        runId: 'r1',
        goalId: 'g1',
        agentId: 'a1',
        status: 'pending',
        questions: [
          {
            id: 'i1',
            header: 'Date range',
            question: 'Which period should the report cover?',
            options: [{ label: 'Last quarter', description: 'July to September 2026.' }],
          },
        ],
        requestedBy: 'u1',
        createdAt: '2026-09-28T00:00:00Z',
        expiresAt: '2026-09-29T00:00:00Z',
        canAnswer: true,
        extendable: false,
        // taskId, conversationId, goalTitle, answer, answeredBy, answeredVia, answeredAt,
        // closedReason and runStatus are all left out, as non_null inclusion does for a null field.
      },
    ])

    const { result } = renderHook(() => useQuestions(), { wrapper: wrapper() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const [row] = result.current.data!
    expect(row).toBeDefined()
    expect(row!.taskId).toBeNull()
    expect(row!.conversationId).toBeNull()
    expect(row!.goalTitle).toBeNull()
    expect(row!.answer).toBeNull()
    expect(row!.answeredBy).toBeNull()
    expect(row!.answeredVia).toBeNull()
    expect(row!.answeredAt).toBeNull()
    expect(row!.closedReason).toBeNull()
    expect(row!.runStatus).toBe('unknown')
    expect(row!.questions[0]!.multiSelect).toBe(false)
    expect(row!.questions[0]!.options[0]!.recommended).toBe(false)
  })
})

describe('useBoard fills every default boardRow and approvalSummaryRow leave out', () => {
  it('restores window, windowMinutes, the stats, agent flags, and the approval and list defaults', async () => {
    stubFetch({
      generatedAt: '2026-09-28T00:00:00Z',
      timezone: 'Australia/Melbourne',
      stats: { running: 1, waitingApproval: 0, queued: 0, held: 0, completedToday: 0, failedToday: 0, spendToday: 0 },
      agents: [{ id: 'a1', name: 'HR', category: 'people', status: 'active', runningRunIds: [], waitingRunIds: [], queued: 0 }],
      goals: [],
      queue: [],
      timeline: [],
      approvals: [
        {
          id: 'ap1',
          runId: 'r1',
          agentId: 'a1',
          actionClass: 'OUTBOUND',
          summary: 'Send an email',
          requestedAt: '2026-09-28T00:00:00Z',
          expiresAt: '2026-09-29T00:00:00Z',
        },
      ],
      // window, windowMinutes, questions and failedToday are all left out.
    })

    const { result } = renderHook(() => useBoard(), { wrapper: wrapper() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const data = result.current.data!
    expect(data.window).toBe('PT2H')
    expect(data.windowMinutes).toBe(120)
    expect(data.questions).toEqual([])
    expect(data.failedToday).toEqual([])
    expect(data.stats.waitingInput).toBe(0)
    expect(data.stats.goalsCompletedToday).toBe(0)
    expect(data.stats.goalsFailedToday).toBe(0)
    expect(data.stats.directRuns).toBe(0)
    expect(data.agents[0]!.fallback).toBe(false)
    expect(data.agents[0]!.askingRunIds).toEqual([])

    const [decision] = data.approvals
    expect(decision).toBeDefined()
    expect(decision!.taskId).toBeNull()
    expect(decision!.goalId).toBeNull()
    expect(decision!.tool).toBeNull()
    expect(decision!.requestedBy).toBeNull()
    expect(decision!.canDecide).toBe(false)
  })
})

describe('the deprecated useConversations adapter', () => {
  it('flattens pinned ahead of the rest, de-duplicates by id, and fills every default conversationRow leaves out', async () => {
    const pinned = { id: 'c1', title: 'Pinned thread', createdAt: '2026-09-28T00:00:00Z', updatedAt: '2026-09-28T00:00:00Z', lastMessagePreview: 'Hi', pinned: true }
    const bare = { id: 'c2', title: 'Support ticket', createdAt: '2026-09-28T00:00:00Z', updatedAt: '2026-09-28T00:00:00Z', lastMessagePreview: 'Hi there' }
    stubFetch({
      pinned: [pinned],
      needsYou: [],
      // c1 also comes back in `conversations`, as the server sends every pinned row there too.
      conversations: [pinned, bare],
      hasMore: false,
    })

    const { result } = renderHook(() => useConversations(), { wrapper: wrapper() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const rows = result.current.data!
    expect(rows.map((row) => row.id)).toEqual(['c1', 'c2'])

    const [, plain] = rows
    expect(plain).toBeDefined()
    expect(plain!.createdBy).toBeNull()
    expect(plain!.messageCount).toBe(0)
    expect(plain!.pinned).toBe(false)
    expect(plain!.archived).toBe(false)
    expect(plain!.activity).toBe('idle')
    expect(plain!.canManage).toBe(false)
    expect(plain!.unread).toBe(false)
    expect(plain!.match).toBeNull()
  })
})

/* ---- Conversation reads: only what changed -------------------------------------------------------- */

function message(position: number, partial: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id: `m${position}`,
    position,
    authorKind: 'user',
    authorId: 'u1',
    agentId: null,
    kind: 'text',
    content: `Message ${position}`,
    detail: {},
    goalId: null,
    createdAt: `2026-10-04T00:00:${String(position).padStart(2, '0')}Z`,
    ...partial,
  }
}

function thread(positions: number[], partial: Partial<ConversationDetail> = {}): ConversationDetail {
  return {
    conversation: conversation(),
    messages: positions.map((position) => message(position)),
    goals: [],
    questions: [],
    hasEarlier: false,
    generatedAt: '2026-10-04T01:00:00.000Z',
    ...partial,
  }
}

describe('mergeConversationDetail', () => {
  it('adds the new messages after the ones held and keeps the thread in position order', () => {
    const merged = mergeConversationDetail(thread([0, 1, 2]), thread([3, 4], { generatedAt: '2026-10-04T01:00:03.000Z' }))

    expect(merged.messages.map((m) => m.position)).toEqual([0, 1, 2, 3, 4])
    expect(merged.generatedAt).toBe('2026-10-04T01:00:03.000Z')
  })

  it('keeps each message once when the reply repeats one already held, the reply winning', () => {
    const held = thread([0, 1, 2])
    const repeated = { ...message(2), content: 'Edited' }

    const merged = mergeConversationDetail(held, thread([], { messages: [repeated, message(3)] }))

    expect(merged.messages.map((m) => m.id)).toEqual(['m0', 'm1', 'm2', 'm3'])
    expect(merged.messages[2]!.content).toBe('Edited')
  })

  it('merges a whole window the same way, which is what a server that ignores the question sends', () => {
    // Held 0-2 (older than anything in the window); the window the server sends is 1-5.
    const merged = mergeConversationDetail(thread([0, 1, 2], { hasEarlier: true }), thread([1, 2, 3, 4, 5], { hasEarlier: true }))

    expect(merged.messages.map((m) => m.position)).toEqual([0, 1, 2, 3, 4, 5])
    expect(merged.hasEarlier).toBe(true)
  })

  it('replaces goals and questions by id, and keeps the ones the reply does not mention', () => {
    const running = { ...goal('running', [task('running')]), id: 'g1' }
    const finished = { ...goal('completed', [task('completed')]), id: 'g2', createdAt: '2026-09-27T00:00:00Z' }
    const held = thread([0, 1], { goals: [finished, running], questions: [question('pending')] })
    const settled = { ...goal('completed', [task('completed')]), id: 'g1' }

    const merged = mergeConversationDetail(held, thread([], { goals: [settled], questions: [{ ...question('answered'), id: 'q1' }] }))

    expect(merged.goals.map((g) => [g.id, g.status])).toEqual([
      ['g2', 'completed'],
      ['g1', 'completed'],
    ])
    expect(merged.questions.map((q) => q.status)).toEqual(['answered'])
  })

  it('keeps what it knew about earlier messages, since a change says nothing about them', () => {
    const merged = mergeConversationDetail(thread([0, 1, 2], { hasEarlier: true }), thread([3], { hasEarlier: false }))

    expect(merged.hasEarlier).toBe(true)
  })

  it('drops its generatedAt when the reply has none, so the next read asks for the whole window', () => {
    const { generatedAt: _unused, ...withoutClock } = thread([3])
    expect(_unused).toBeDefined()

    const merged = mergeConversationDetail(thread([0, 1, 2]), withoutClock)

    expect(merged.generatedAt).toBeUndefined()
  })

  it('replaces what it holds when more arrived than the window reaches, so no gap is left in the thread', () => {
    const received = thread([40, 41, 42], { hasEarlier: true })

    const merged = mergeConversationDetail(thread([0, 1, 2]), received)

    expect(merged).toBe(received)
  })

  it('does not mistake an empty change for a gap', () => {
    const merged = mergeConversationDetail(thread([0, 1, 2]), thread([], { hasEarlier: true }))

    expect(merged.messages.map((m) => m.position)).toEqual([0, 1, 2])
  })
})

describe('deltaMissesHeldWork and settleWithWholeRead', () => {
  const running = { ...goal('running', [task('running')]), id: 'g1' }
  const done = { ...goal('completed', [task('completed')]), id: 'g2' }

  it('needs a whole read only when an open goal or a pending question went missing', () => {
    expect(deltaMissesHeldWork(thread([0], { goals: [running] }), thread([], { goals: [running] }))).toBe(false)
    expect(deltaMissesHeldWork(thread([0], { goals: [done] }), thread([]))).toBe(false)
    expect(deltaMissesHeldWork(thread([0], { goals: [running] }), thread([]))).toBe(true)
    expect(deltaMissesHeldWork(thread([0], { questions: [question('pending')] }), thread([]))).toBe(true)
    expect(deltaMissesHeldWork(thread([0], { questions: [question('answered')] }), thread([]))).toBe(false)
  })

  it('drops an open goal or pending question the whole read no longer has, so polling can stop', () => {
    const settled = settleWithWholeRead(
      thread([0], { goals: [running, done], questions: [question('pending')] }),
      thread([0], { goals: [done] }),
    )
    expect(settled.goals.map((g) => g.id)).toEqual(['g2'])
    expect(settled.questions).toEqual([])
    expect(conversationPollMs(settled)).toBe(false)
  })
})

describe('useConversation', () => {
  const key = ['conversations', 'c1']

  function stubReplies(...replies: ConversationDetail[]) {
    const urls: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        urls.push(typeof input === 'string' ? input : input.toString())
        const reply = replies[Math.min(urls.length - 1, replies.length - 1)]!
        return Promise.resolve(jsonResponse(reply))
      }),
    )
    return urls
  }

  it('reads the recent window first, then only what changed after the newest message and since the server clock', async () => {
    const urls = stubReplies(
      thread([0, 1, 2], { hasEarlier: true }),
      thread([3, 4], { generatedAt: '2026-10-04T01:00:05.000Z' }),
    )
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result } = renderHook(() => useConversation('c1'), { wrapper: wrapperWith(client) })
    // Reading data is what subscribes the hook to changes of it.
    await waitFor(() => expect(result.current.data?.messages).toHaveLength(3))

    await act(() => client.refetchQueries({ queryKey: key }))

    expect(urls[0]).toBe('/api/conversations/c1?limit=200')
    const second = new URL(urls[1]!, 'http://localhost')
    expect(second.searchParams.get('limit')).toBe('200')
    expect(second.searchParams.get('after')).toBe('2')
    expect(second.searchParams.get('since')).toBe('2026-10-04T01:00:00.000Z')
    await waitFor(() => expect(result.current.data!.messages.map((m) => m.position)).toEqual([0, 1, 2, 3, 4]))
    expect(result.current.data!.hasEarlier).toBe(true)
    expect(result.current.data!.generatedAt).toBe('2026-10-04T01:00:05.000Z')
  })

  it('merges correctly when the server ignores after and since and sends the whole window again', async () => {
    const whole = thread([0, 1, 2, 3], { hasEarlier: false })
    const urls = stubReplies(thread([0, 1, 2]), whole)
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result } = renderHook(() => useConversation('c1'), { wrapper: wrapperWith(client) })
    await waitFor(() => expect(result.current.data?.messages).toHaveLength(3))

    await act(() => client.refetchQueries({ queryKey: key }))

    expect(urls).toHaveLength(2)
    await waitFor(() => expect(result.current.data!.messages.map((m) => m.id)).toEqual(['m0', 'm1', 'm2', 'm3']))
  })

  it('asks for the whole window each time when the server never says what time it is', async () => {
    const { generatedAt: _unused, ...bare } = thread([0, 1, 2])
    expect(_unused).toBeDefined()
    const urls = stubReplies(bare)
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result } = renderHook(() => useConversation('c1'), { wrapper: wrapperWith(client) })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    await act(() => client.refetchQueries({ queryKey: key }))

    expect(urls).toEqual(['/api/conversations/c1?limit=200', '/api/conversations/c1?limit=200'])
  })

  // Regression: a goal that finished in a change the "what changed since" reply did not cover was
  // held as open for good: its card stayed on "Starting" and the thread polled on until a reload.
  it('reads the whole window again when a reply leaves out a goal it holds as open', async () => {
    const running = { ...goal('running', [task('running')]), id: 'g1' }
    const finished = { ...goal('completed', [task('completed')]), id: 'g1' }
    const urls = stubReplies(
      thread([0, 1, 2], { goals: [running] }),
      // The change: no goal at all, since an open one is always sent and this one is no longer open.
      thread([], { generatedAt: '2026-10-04T01:00:05.000Z' }),
      thread([0, 1, 2, 3], { goals: [finished], generatedAt: '2026-10-04T01:00:06.000Z' }),
    )
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result } = renderHook(() => useConversation('c1'), { wrapper: wrapperWith(client) })
    await waitFor(() => expect(result.current.data?.goals[0]?.status).toBe('running'))

    await act(() => client.refetchQueries({ queryKey: key }))

    expect(urls).toHaveLength(3)
    expect(new URL(urls[1]!, 'http://localhost').searchParams.get('since')).toBe('2026-10-04T01:00:00.000Z')
    expect(urls[2]).toBe('/api/conversations/c1?limit=200')
    await waitFor(() => expect(result.current.data!.goals.map((g) => g.status)).toEqual(['completed']))
    expect(result.current.data!.messages.map((m) => m.position)).toEqual([0, 1, 2, 3])
    expect(result.current.data!.generatedAt).toBe('2026-10-04T01:00:06.000Z')
    const query = client.getQueryCache().find({ queryKey: key })!
    const interval = query.observers[0]!.options.refetchInterval as (q: typeof query) => number | false
    expect(interval(query)).toBe(false)
  })

  it('does not read again when every goal it holds as open is in the reply', async () => {
    const running = { ...goal('running', [task('running')]), id: 'g1' }
    const urls = stubReplies(thread([0], { goals: [running] }), thread([1], { goals: [running] }))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result } = renderHook(() => useConversation('c1'), { wrapper: wrapperWith(client) })
    await waitFor(() => expect(result.current.data?.goals).toHaveLength(1))

    await act(() => client.refetchQueries({ queryKey: key }))

    expect(urls).toHaveLength(2)
  })

  it('does not read at all without a conversation', () => {
    const urls = stubReplies(thread([0]))
    renderHook(() => useConversation(null), { wrapper: wrapper() })
    expect(urls).toEqual([])
  })

  it('stops polling once the conversation is gone, however much work is still showing in it', async () => {
    stubReplies(detail([goal('running', [task('running')])], []))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result } = renderHook(() => useConversation('c1'), { wrapper: wrapperWith(client) })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    const query = client.getQueryCache().find({ queryKey: key })!
    const interval = query.observers[0]!.options.refetchInterval as (q: typeof query) => number | false
    expect(interval(query)).toBe(3_000)

    // The next read finds it deleted: the copy held stays on screen, but nothing asks again.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ code: 'not_found', detail: 'Conversation not found.' }), { status: 404 }),
      ),
    )
    await act(() => client.refetchQueries({ queryKey: key }))

    expect(query.state.error).toBeInstanceOf(ApiError)
    expect(query.state.data).toBeDefined()
    expect(interval(query)).toBe(false)
  })
})

/* ---- Lists: only the first page is read again ------------------------------------------------------ */

function run(id: string, status: string): Run {
  return {
    id,
    agentId: 'a1',
    status,
    trigger: 'manual',
    stepCount: 0,
    promptTokens: 0,
    completionTokens: 0,
    cost: 0,
    startedAt: '2026-10-04T00:00:00Z',
  }
}

describe('mergeFirstPage', () => {
  const idOf = (row: { id: string }) => row.id
  const pages = (...lists: Array<Array<{ id: string; n: number }>>): InfiniteData<Array<{ id: string; n: number }>, number> => ({
    pages: lists,
    pageParams: lists.map((_, index) => index),
  })

  it('replaces the first page with its newer copies and leaves every later page as it was', () => {
    const data = pages([{ id: 'a', n: 1 }, { id: 'b', n: 1 }], [{ id: 'c', n: 1 }])

    const merged = mergeFirstPage(data, [{ id: 'a', n: 2 }, { id: 'b', n: 2 }], idOf)

    expect(merged!.pages[0]).toEqual([{ id: 'a', n: 2 }, { id: 'b', n: 2 }])
    expect(merged!.pages[1]).toBe(data.pages[1])
  })

  it('asks for a full read when the first page gained a row, since every later page shifted', () => {
    const data = pages([{ id: 'a', n: 1 }, { id: 'b', n: 1 }], [{ id: 'c', n: 1 }])

    expect(mergeFirstPage(data, [{ id: 'z', n: 1 }, { id: 'a', n: 1 }], idOf)).toBeNull()
  })

  it('asks for a full read when the first page lost a row', () => {
    const data = pages([{ id: 'a', n: 1 }, { id: 'b', n: 1 }])

    expect(mergeFirstPage(data, [{ id: 'a', n: 1 }], idOf)).toBeNull()
  })

  it('asks for a full read when nothing has been loaded to merge into', () => {
    expect(mergeFirstPage({ pages: [], pageParams: [] }, [{ id: 'a', n: 1 }], idOf)).toBeNull()
  })
})

describe('the paged lists poll only their first page', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('reads page 0 again on the timer, and not the page the person loaded after it', async () => {
    const requests: string[] = []
    const page0 = Array.from({ length: 50 }, (_, index) => run(`r${index}`, index === 0 ? 'running' : 'completed'))
    const page1 = [run('r50', 'completed')]
    let firstPageStatus = 'running'
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = typeof input === 'string' ? input : input.toString()
        requests.push(url)
        const page = new URL(url, 'http://localhost').searchParams.get('page')
        if (page === '1') return Promise.resolve(jsonResponse(page1))
        return Promise.resolve(jsonResponse(page0.map((row) => (row.id === 'r0' ? { ...row, status: firstPageStatus } : row))))
      }),
    )
    // Installed before the hook renders, so the list's own timer is the one under test.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result } = renderHook(() => useRunList(), { wrapper: wrapperWith(client) })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    await act(async () => {
      await result.current.fetchNextPage()
    })
    expect(requests).toHaveLength(2)

    firstPageStatus = 'completed'
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_200)
    })
    await waitFor(() => expect(requests.length).toBeGreaterThanOrEqual(3))

    const later = requests.slice(2).map((url) => new URL(url, 'http://localhost').searchParams.get('page'))
    expect(later.every((page) => page === '0')).toBe(true)
    await waitFor(() => expect(result.current.data!.pages[0]!.find((row) => row.id === 'r0')!.status).toBe('completed'))
    expect(result.current.data!.pages).toHaveLength(2)
  })
})

describe('useGoalPages', () => {
  function urlsOf() {
    const urls: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        urls.push(typeof input === 'string' ? input : input.toString())
        return Promise.resolve(jsonResponse([]))
      }),
    )
    return urls
  }

  it('sends the status, source and schedule to the server, so a filter searches every goal', async () => {
    const urls = urlsOf()
    const { result } = renderHook(
      () => useGoalPages({ status: 'failed', source: 'schedule', scheduleId: 's1' }),
      { wrapper: wrapper() },
    )
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const params = new URL(urls[0]!, 'http://localhost').searchParams
    expect(params.get('status')).toBe('failed')
    expect(params.get('source')).toBe('schedule')
    expect(params.get('scheduleId')).toBe('s1')
    expect(params.get('page')).toBe('0')
    expect(params.get('size')).toBe('50')
  })

  it('sends no filter when none is chosen', async () => {
    const urls = urlsOf()
    const { result } = renderHook(() => useGoalPages(), { wrapper: wrapper() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(urls[0]).toBe('/api/goals?page=0&size=50')
  })

  it('keeps a list for each filter apart', async () => {
    urlsOf()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result, rerender } = renderHook(({ status }) => useGoalPages({ status }), {
      wrapper: wrapperWith(client),
      initialProps: { status: null as string | null },
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    rerender({ status: 'failed' })
    await waitFor(() => expect(client.getQueryCache().findAll({ queryKey: ['goals', 'pages'] }).length).toBeGreaterThanOrEqual(2))
  })
})

/* ---- Requests: cancellation, and what each action refreshes ------------------------------------------ */

describe('every read hands its cancellation signal to the request', () => {
  it('stops a read still waiting when the screen that asked for it goes away', async () => {
    const signals: AbortSignal[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
        if (init?.signal) signals.push(init.signal)
        return new Promise<Response>(() => {})
      }),
    )
    const { unmount } = renderHook(() => useAgents(), { wrapper: wrapper() })
    await waitFor(() => expect(signals).toHaveLength(1))
    expect(signals[0]!.aborted).toBe(false)

    unmount()

    await waitFor(() => expect(signals[0]!.aborted).toBe(true))
  })

  it('does not read a trace without a run id', () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    renderHook(() => useRunSteps(''), { wrapper: wrapper() })
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('invalidateWork', () => {
  const keysOf = (spy: MockInstance<QueryClient['invalidateQueries']>) => spy.mock.calls.map(([filters]) => filters?.queryKey)

  it('refreshes the goals, runs, board, approvals and conversations together', () => {
    const client = new QueryClient()
    const spy = vi.spyOn(client, 'invalidateQueries')

    invalidateWork(client)

    expect(keysOf(spy)).toEqual([['goals'], ['runs'], ['board'], ['approvals'], ['conversations']])
  })

  it.each([
    ['starting a goal', () => useCreateGoal(), (m: { mutateAsync: (input: never) => Promise<unknown> }) =>
      m.mutateAsync({ title: 'Plan', agentId: 'a1', instruction: 'Do it' } as never), { id: 'g1', tasks: [] }],
    ['starting a run', () => useCreateRun('a1'), (m: { mutateAsync: (input: never) => Promise<unknown> }) =>
      m.mutateAsync({ instruction: 'Do it' } as never), { runId: 'r1', status: 'running' }],
  ])('is used when %s, so the board never lags the work', async (_name, useHook, start, reply) => {
    stubFetch(reply)
    const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
    const spy = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useHook(), { wrapper: wrapperWith(client) })

    await act(async () => {
      await start(result.current as never)
    })

    expect(keysOf(spy)).toEqual(expect.arrayContaining([['goals'], ['runs'], ['board'], ['approvals'], ['conversations']]))
  })

  it('refreshes the board when a schedule is created', async () => {
    stubFetch({ id: 's1', name: 'Daily', agentId: 'a1', agentName: 'HR', instruction: 'Do it', kind: 'recurring', timezone: 'UTC', description: '', enabled: true, overlapPolicy: 'skip', consecutiveFailures: 0, createdAt: '2026-10-04T00:00:00Z' })
    const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
    const spy = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useCreateSchedule(), { wrapper: wrapperWith(client) })

    await act(async () => {
      await result.current.mutateAsync({ name: 'Daily', agentId: 'a1', instruction: 'Do it', text: 'every day' })
    })

    expect(keysOf(spy)).toEqual(expect.arrayContaining([['schedules'], ['board']]))
  })
})
