// @find: tests for WorkStrip, work strip, active goals, running goals, review link, current step, dock, goal status line
// @what: Automated tests for WorkStrip.
// @flow: Run with the web test runner; covers WorkStrip.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BoardGoal, BoardTask, ChatMessage } from '../../lib/queries'
import type { useSpeaker } from '../../lib/voice'
import { MessageList } from './MessageList'
import { WorkStrip } from './WorkStrip'
import { goalTarget } from './chatModel'

/*
 * The work strip's "Review" link for a goal parked on an approval: it must land somewhere real.
 * Chat.tsx resolves the goal with goalTarget, so the anchor that resolves to has to exist in the
 * thread MessageList renders, and the strip has to say plainly that a person is needed.
 */

function task(overrides: Partial<BoardTask> & Pick<BoardTask, 'id'>): BoardTask {
  return {
    agentId: 'agent-1',
    title: 'Send the quote',
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
    runStatus: null,
    stepCount: null,
    cost: null,
    ...overrides,
  }
}

function goal(id: string, tasks: BoardTask[]): BoardGoal {
  return {
    id,
    title: 'Send the Q3 quote',
    description: '',
    status: 'running',
    createdAt: '2026-10-01T00:00:00Z',
    completedAt: null,
    source: 'chat',
    requestedBy: 'user-1',
    conversationId: 'c1',
    scheduleId: null,
    tasks,
  }
}

function message(overrides: Partial<ChatMessage> & Pick<ChatMessage, 'id' | 'position'>): ChatMessage {
  return {
    authorKind: 'coordinator',
    authorId: null,
    agentId: null,
    kind: 'text',
    content: '',
    detail: {},
    goalId: null,
    createdAt: '2026-10-01T00:00:00Z',
    ...overrides,
  }
}

const AGENT_NAMES = {
  'agent-1': { id: 'agent-1', key: 'sales', name: 'Sales', category: 'sales', status: 'active', revision: 1 },
} as never

const speaker = { speakingKey: null, provider: 'none', muted: true, setMuted: () => {}, speak: async () => {}, stop: () => {} } as unknown as ReturnType<
  typeof useSpeaker
>

const parked = goal('g1', [task({ id: 't1', status: 'waiting_approval', runId: 'run-1' })])

const thread: ChatMessage[] = [
  message({ id: 'u1', position: 0, kind: 'text', authorKind: 'user', authorId: 'user-1', content: 'Send the Q3 quote' }),
  message({ id: 'r1', position: 1, kind: 'routing', goalId: 'g1', detail: { mode: 'rules', requestText: 'Send the Q3 quote' } }),
  message({ id: 'p1', position: 2, kind: 'progress', goalId: 'g1' }),
]

let requested: string[]

beforeEach(() => {
  requested = []
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL) => {
      requested.push(typeof input === 'string' ? input : input.toString())
      return Promise.reject(new Error('No network in tests'))
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function wrap(children: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}>{children}</QueryClientProvider>)
}

function strip(goals: BoardGoal[], compact: boolean, onReview: (goalId: string) => void = () => {}) {
  return (
    <WorkStrip
      goals={goals}
      questions={[]}
      agentNames={AGENT_NAMES}
      me="user-1"
      compact={compact}
      onStop={() => {}}
      onGoTo={() => {}}
      onReview={onReview}
    />
  )
}

describe('WorkStrip', () => {
  it("sends Review to the goal's progress card, which is in the thread", () => {
    const reviewed: string[] = []
    wrap(
      <>
        <MessageList
          messages={thread}
          goals={[parked]}
          questions={[]}
          agentNames={AGENT_NAMES}
          memberNames={{}}
          me="user-1"
          speaker={speaker}
          freshIds={new Set()}
          reroutingId={null}
          onReroute={() => {}}
          onAskAgain={() => {}}
          onEditAndResend={() => {}}
          onReplyInOwnWords={() => {}}
          onStop={() => {}}
          onRetry={() => {}}
          onAnswerFromDocuments={() => {}}
          conversationId="c1"
          agentsForReroute={[]}
          answeringMessageId={null}
          composerTargetQuestionId={null}
          now={new Date('2026-10-01T00:05:00Z')}
        />
        {strip([parked], false, (goalId) => reviewed.push(goalId))}
      </>,
    )

    const line = screen.getByText('Sales is waiting for approval').closest('.chat-workstrip-line')
    expect(line).toHaveAttribute('data-approval')
    fireEvent.click(screen.getByRole('button', { name: 'Review' }))
    expect(reviewed).toEqual(['g1'])

    const target = goalTarget('g1', thread, 'approval')
    if (!('elementId' in target)) throw new Error('expected the progress card in the thread')
    const anchor = document.getElementById(target.elementId)
    expect(anchor).not.toBeNull()
    expect(anchor?.querySelector('.chat-progress-card')).not.toBeNull()
  })

  it('says in the narrow strip that work waits for an approval', () => {
    const { container } = wrap(strip([parked, goal('g2', [task({ id: 't2', status: 'running', runId: 'run-2' })])], true))
    expect(screen.getByRole('button', { name: '1 needs your approval · Show' })).toBeInTheDocument()
    expect(container.querySelector('.chat-workstrip')).toHaveAttribute('data-approval')
  })

  it('says in the narrow strip that work waits for an answer, or how much is running', () => {
    const asking = goal('g3', [task({ id: 't3', status: 'waiting_input', runId: 'run-3' })])
    const { unmount } = wrap(strip([asking], true))
    expect(screen.getByRole('button', { name: '1 needs your answer · Show' })).toBeInTheDocument()
    unmount()

    wrap(strip([goal('g4', [task({ id: 't4', status: 'running', runId: 'run-4' })]), goal('g5', [task({ id: 't5', status: 'running', runId: 'run-5' })])], true))
    expect(screen.getByRole('button', { name: '2 running · Show' })).toBeInTheDocument()
  })

  it('asks for no steps for a task that has no run yet', () => {
    wrap(strip([goal('g6', [task({ id: 't6', status: 'pending', runId: null })])], false))
    expect(screen.getByText('Sales is working')).toBeInTheDocument()
    expect(requested.some((url) => url.includes('/api/runs//steps'))).toBe(false)
  })
})
