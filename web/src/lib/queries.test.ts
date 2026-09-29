import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { createElement } from 'react'
import type { ReactElement, ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { boardPollMs, conversationPollMs, useBoard, useConversations, useQuestions } from './queries'
import type { Board, BoardApproval, BoardGoal, BoardTask, Conversation, ConversationDetail, RunQuestion } from './queries'

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

  it('polls every 10 s while a task is only parked for an approval', () => {
    expect(conversationPollMs(detail([goal('waiting', [task('waiting_approval')])], []))).toBe(10_000)
  })

  it('polls every 10 s while a question is pending, even with no active goal', () => {
    expect(conversationPollMs(detail([], [question('pending')]))).toBe(10_000)
  })
})

describe('boardPollMs', () => {
  it('polls every 15 s without a board yet', () => {
    expect(boardPollMs(undefined)).toBe(15_000)
  })

  it('polls every 15 s when nothing is active, pending or waiting on a decision', () => {
    expect(boardPollMs(board())).toBe(15_000)
  })

  it('polls every 3 s while a goal is active', () => {
    expect(boardPollMs(board({ goals: [goal('running', [task('running')])] }))).toBe(3_000)
  })

  it('polls every 3 s while a question is pending', () => {
    expect(boardPollMs(board({ questions: [question('pending')] }))).toBe(3_000)
  })

  it('polls every 3 s while an approval is waiting on someone', () => {
    expect(boardPollMs(board({ approvals: [approval()] }))).toBe(3_000)
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
