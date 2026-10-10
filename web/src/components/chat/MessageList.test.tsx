// @find: tests for MessageList, message list, chat thread, day divider, grouped messages, thread scroll, new message announce, conversation messages
// @what: Automated tests for MessageList.
// @flow: Run with the web test runner; covers MessageList.
import { render, screen } from '@testing-library/react'
import type { ComponentProps } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { BoardGoal, BoardTask, ChatMessage } from '../../lib/queries'
import type { useSpeaker } from '../../lib/voice'
import { MessageList } from './MessageList'

/*
 * The thread is redrawn only for what changed. Chat re-renders on every poll, so a poll that
 * brought nothing new must not redraw two hundred messages, and a goal's step count moving must
 * redraw that goal's cards and no others. MessageItem is replaced by a counter, so what is
 * measured is which messages MessageList hands new props to.
 */

const drawn = vi.hoisted(() => [] as string[])

vi.mock('./MessageItem', async () => {
  const { memo: memoise } = await import('react')
  return {
    MessageItem: memoise(function CountedItem({ message }: { message: { id: string; content: string } }) {
      drawn.push(message.id)
      return <span>{message.content}</span>
    }),
  }
})

const speaker = { speakingKey: null, provider: 'none', muted: true } as unknown as ReturnType<typeof useSpeaker>

function task(status: string, stepCount: number | null): BoardTask {
  return {
    id: 't1',
    agentId: 'agent-1',
    title: 'Send the quote',
    status,
    position: 0,
    dependsOn: [],
    attempt: 0,
    maxAttempts: 2,
    result: null,
    failureReason: null,
    startedAt: null,
    completedAt: null,
    runId: 'run-1',
    runStatus: null,
    stepCount,
    cost: null,
  }
}

function goal(id: string, stepCount: number | null): BoardGoal {
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
    tasks: [task('running', stepCount)],
  }
}

function message(position: number, partial: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id: `m${position}`,
    position,
    authorKind: 'user',
    authorId: 'user-1',
    agentId: null,
    kind: 'text',
    content: `Message ${position}`,
    detail: {},
    goalId: null,
    createdAt: '2026-10-01T12:00:00Z',
    ...partial,
  }
}

const thread: ChatMessage[] = [
  message(0),
  message(1, { kind: 'progress', authorKind: 'coordinator', goalId: 'g1' }),
  message(2, { kind: 'progress', authorKind: 'coordinator', goalId: 'g2' }),
]

const noop = () => {}

function props(overrides: Partial<ComponentProps<typeof MessageList>> = {}): ComponentProps<typeof MessageList> {
  return {
    messages: thread,
    goals: [goal('g1', 1), goal('g2', 1)],
    questions: [],
    agentNames: {},
    memberNames: {},
    me: 'user-1',
    speaker,
    freshIds: new Set<string>(),
    reroutingId: null,
    onReroute: noop,
    onAskAgain: noop,
    onEditAndResend: noop,
    onReplyInOwnWords: noop,
    onStop: noop,
    onRetry: noop,
    onAnswerFromDocuments: noop,
    conversationId: 'c1',
    agentsForReroute: [],
    answeringMessageId: null,
    composerTargetQuestionId: null,
    now: Date.parse('2026-10-01T12:00:00Z'),
    ...overrides,
  }
}

beforeEach(() => {
  drawn.length = 0
})

describe('MessageList', () => {
  it('is wrapped in React.memo', () => {
    expect((MessageList as unknown as { $$typeof: symbol }).$$typeof).toBe(Symbol.for('react.memo'))
  })

  it('draws every message once, and again for none when its parent redraws with the same props', () => {
    const same = props()
    const { rerender } = render(<MessageList {...same} />)
    expect(drawn.sort()).toEqual(['m0', 'm1', 'm2'])
    drawn.length = 0

    rerender(<MessageList {...same} />)

    expect(drawn).toEqual([])
  })

  it('redraws only the messages of the goal that changed', () => {
    const held = props()
    const { rerender } = render(<MessageList {...held} />)
    drawn.length = 0

    // The first goal's step count moved: a new object for it, the same one for the other.
    rerender(<MessageList {...held} goals={[goal('g1', 2), held.goals[1]!]} />)

    expect(drawn).toEqual(['m1'])
  })

  it('accepts the clock as a number, and still labels the day', () => {
    render(<MessageList {...props({ now: Date.parse('2026-10-01T12:05:00Z') })} />)

    expect(screen.getByText('Today')).toBeInTheDocument()
  })

  it('still accepts a Date for the clock', () => {
    render(<MessageList {...props({ now: new Date('2026-10-02T12:00:00Z') })} />)

    expect(screen.getByText('Yesterday')).toBeInTheDocument()
  })

  it('leaves a progress update out until its goal is known', () => {
    render(<MessageList {...props({ goals: [goal('g1', 1)] })} />)

    expect(drawn.sort()).toEqual(['m0', 'm1'])
  })
})
