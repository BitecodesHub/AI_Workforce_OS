// @find: tests for AnswerBubble, agent answer, reply bubble, answer message, copy answer, read aloud, thumbs up, thumbs down, feedback, sources, citations, sandbox model, markdown answer, chat thread
// @what: Automated tests for AnswerBubble.
// @flow: Run with the web test runner; covers AnswerBubble.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import axe from 'axe-core'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { choiceRequest } from '../../lib/answerBlocks'
import type { Agent, BoardGoal, BoardTask, ChatMessage, QueuedMessage } from '../../lib/queries'
import { clearSession, saveSession } from '../../lib/session'
import type { useSpeaker } from '../../lib/voice'
import { AnswerBubble } from './AnswerBubble'
import type { CardSend } from './AnswerBubble'
import { passageItemId } from './PassageList'
import type { SourcePassage } from './chatModel'

/*
 * The sources under an agent's answer: a folded "Sources (n)" list of the passages it was given, and
 * a [n] in the answer that opens source n. Only the goal's first step read the passages, so only its
 * answers carry them.
 */

const SPEAKER = { provider: 'none', speakingKey: null, speak: vi.fn(), stop: vi.fn() } as unknown as ReturnType<
  typeof useSpeaker
>

const AGENT: Agent = {
  id: 'agent-1',
  key: 'support',
  name: 'Customer Support',
  category: 'support',
  status: 'active',
  fallback: false,
} as unknown as Agent

function passage(overrides: Partial<SourcePassage> = {}): SourcePassage {
  return {
    chunkId: 'chunk-1',
    documentId: 'doc-1',
    sourceId: 'source-1',
    documentTitle: 'Refund policy',
    uri: null,
    pageNumber: 2,
    heading: 'Timing',
    content: 'Refunds are issued within five business days.',
    score: 0.9,
    ...overrides,
  }
}

const PASSAGES = [
  passage(),
  passage({ chunkId: 'chunk-2', documentId: 'doc-2', sourceId: 'source-2', documentTitle: 'Support handbook', pageNumber: null, heading: null, content: 'Escalate refunds over $500 to a manager.' }),
]

function message(overrides: Partial<ChatMessage> & Pick<ChatMessage, 'id'>): ChatMessage {
  return {
    position: 0,
    authorKind: 'agent',
    authorId: null,
    agentId: 'agent-1',
    kind: 'answer',
    content: '',
    detail: {},
    goalId: 'goal-1',
    createdAt: '2026-10-04T00:00:00Z',
    ...overrides,
  }
}

function routing(overrides: Partial<ChatMessage['detail']> = {}, agents = 1): ChatMessage {
  return message({
    id: 'routing-1',
    kind: 'routing',
    authorKind: 'coordinator',
    agentId: null,
    detail: {
      mode: 'model',
      agents: Array.from({ length: agents }, (_, index) => ({ id: `agent-${index + 1}`, name: `Agent ${index + 1}`, instruction: '' })),
      grounded: true,
      passages: PASSAGES,
      ...overrides,
    },
  })
}

const ANSWER = message({
  id: 'answer-1',
  content: 'Refunds take five business days [1]. Anything over $500 goes to a manager [2]. Nothing covers gift cards [3].',
  detail: { taskId: 'task-1', runId: 'run-1' },
})

function task(id: string, position: number): BoardTask {
  return {
    id,
    agentId: 'agent-1',
    title: 'A task',
    status: 'completed',
    position,
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
  }
}

function goalOf(tasks: BoardTask[]): BoardGoal {
  return {
    id: 'goal-1',
    title: 'A goal',
    description: '',
    status: 'completed',
    createdAt: '2026-10-04T00:00:00Z',
    completedAt: null,
    source: 'chat',
    requestedBy: 'user-1',
    conversationId: 'c1',
    scheduleId: null,
    tasks,
  }
}

function renderBubble(
  props: {
    message?: ChatMessage
    messages?: readonly ChatMessage[]
    goal?: BoardGoal
    client?: QueryClient
    onCardSend?: (request: CardSend) => Promise<boolean>
    me?: string
    queued?: QueuedMessage[]
  } = {},
) {
  const client = props.client ?? new QueryClient()
  const answer = props.message ?? ANSWER
  return render(
    <QueryClientProvider client={client}>
      <AnswerBubble
        message={answer}
        agent={AGENT}
        speaker={SPEAKER}
        grouped={false}
        latest
        {...(props.goal ? { goal: props.goal } : {})}
        {...(props.messages ? { messages: props.messages } : {})}
        agentsForReroute={[]}
        conversationId="c1"
        onSendTo={() => {}}
        onAskAgain={() => {}}
        {...(props.onCardSend ? { onCardSend: props.onCardSend } : {})}
        {...(props.me ? { me: props.me } : {})}
        {...(props.queued ? { queued: props.queued } : {})}
      />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  saveSession('test-token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions: ['chat:use', 'knowledge:read'],
    displayName: 'Maya Manager',
    email: 'maya@example.com',
    role: 'manager',
  })
})

afterEach(() => {
  clearSession()
})

describe('AnswerBubble sources', () => {
  it('shows a folded "Sources (n)" list of what the agent was given, labelled as such', () => {
    renderBubble({ messages: [routing(), ANSWER] })

    const toggle = screen.getByRole('button', { name: 'Sources (2)' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByText('Sources given to the agent')).toBeInTheDocument()

    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText(/\[1\] Refund policy/)).toBeInTheDocument()
    expect(screen.getByText(/\[2\] Support handbook/)).toBeInTheDocument()
    expect(screen.getByText('About page 2')).toBeInTheDocument()
    expect(screen.getByText('Timing')).toBeInTheDocument()
    expect(screen.getByText('Refunds are issued within five business days.')).toBeInTheDocument()
  })

  it('turns [1] and [2] in the answer into buttons, and leaves [3], which names no source, as text', () => {
    const { container } = renderBubble({ messages: [routing(), ANSWER] })

    expect(screen.getByRole('button', { name: 'Open source 1' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Open source 2' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Open source 3' })).toBeNull()
    expect(container.querySelector('.md')?.textContent).toContain('Nothing covers gift cards [3].')
  })

  it('opens the list and moves focus to the cited source when its number is pressed', () => {
    renderBubble({ messages: [routing(), ANSWER] })
    const toggle = screen.getByRole('button', { name: 'Sources (2)' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')

    fireEvent.click(screen.getByRole('button', { name: 'Open source 2' }))

    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(document.getElementById(passageItemId('answer-answer-1', 2))).toHaveFocus()

    // Asking for another one moves focus again, and asking for the same one again does too.
    fireEvent.click(screen.getByRole('button', { name: 'Open source 1' }))
    expect(document.getElementById(passageItemId('answer-answer-1', 1))).toHaveFocus()
    screen.getByRole('button', { name: 'Sources (2)' }).focus()
    fireEvent.click(screen.getByRole('button', { name: 'Open source 1' }))
    expect(document.getElementById(passageItemId('answer-answer-1', 1))).toHaveFocus()
  })

  it('links each source to Knowledge for a person who may read it, else to the document\'s own address', () => {
    const withAddress = routing({ passages: [passage({ uri: 'https://example.org/refunds' }), PASSAGES[1]!] })
    const { unmount } = renderBubble({ messages: [withAddress, ANSWER] })
    fireEvent.click(screen.getByRole('button', { name: 'Sources (2)' }))
    expect(screen.getAllByRole('link', { name: 'Open in Knowledge' }).map((link) => link.getAttribute('href'))).toEqual([
      '/knowledge/source-1',
      '/knowledge/source-2',
    ])
    unmount()

    clearSession()
    saveSession('test-token', {
      userId: 'user-1',
      workspaceId: 'workspace-1',
      permissions: ['chat:use'],
      displayName: 'Eli Employee',
      email: 'eli@example.com',
      role: 'employee',
    })
    renderBubble({ messages: [withAddress, ANSWER] })
    fireEvent.click(screen.getByRole('button', { name: 'Sources (2)' }))
    expect(screen.queryByRole('link', { name: 'Open in Knowledge' })).toBeNull()
    const external = screen.getByRole('link', { name: 'Open source' })
    expect(external).toHaveAttribute('href', 'https://example.org/refunds')
    expect(external).toHaveAttribute('target', '_blank')
    expect(external).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('shows nothing under an answer whose goal was given no passages', () => {
    renderBubble({ messages: [routing({ passages: [], grounded: false }), ANSWER] })

    expect(screen.queryByRole('button', { name: /^Sources/ })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Open source 1' })).toBeNull()
    expect(screen.getByText(/Refunds take five business days \[1\]/)).toBeInTheDocument()
  })

  it('shows nothing under a later step of a chain, whose agent was not given the passages', () => {
    const later = message({ id: 'answer-2', content: 'The reply, from [1].', detail: { taskId: 'task-2' } })
    renderBubble({
      message: later,
      messages: [routing({}, 2), later],
      goal: goalOf([task('task-1', 0), task('task-2', 1)]),
    })

    expect(screen.queryByRole('button', { name: /^Sources/ })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Open source 1' })).toBeNull()
  })

  it('shows them under the first step of a chain', () => {
    renderBubble({
      messages: [routing({}, 2), ANSWER],
      goal: goalOf([task('task-1', 0), task('task-2', 1)]),
    })

    expect(screen.getByRole('button', { name: 'Sources (2)' })).toBeInTheDocument()
  })

  it('finds the routing message in the loaded conversation when the thread is not handed over', () => {
    const client = new QueryClient()
    client.setQueryData(['conversations', 'c1'], { messages: [routing(), ANSWER], goals: [], questions: [] })

    renderBubble({ client })

    expect(screen.getByRole('button', { name: 'Sources (2)' })).toBeInTheDocument()
  })

  it('says nothing about sources when it can find no thread at all', () => {
    renderBubble()

    expect(screen.queryByRole('button', { name: /^Sources/ })).toBeNull()
  })

  it('has no axe violations with the sources folded or open', async () => {
    const { container } = renderBubble({ messages: [routing(), ANSWER] })
    const check = () => axe.run(container, { rules: { 'color-contrast': { enabled: false } } })

    expect((await check()).violations).toEqual([])
    fireEvent.click(screen.getByRole('button', { name: 'Open source 1' }))
    expect((await check()).violations).toEqual([])
  })
})

describe('AnswerBubble practice data', () => {
  it('says under the answer when its tools ran on practice data, and nothing otherwise', () => {
    const practice = { ...ANSWER, detail: { ...ANSWER.detail, practiceData: true } } as ChatMessage
    const { unmount } = renderBubble({ message: practice })
    expect(screen.getByText('Practice data: nothing was sent or changed outside this workspace.')).toBeInTheDocument()
    unmount()
    renderBubble()
    expect(screen.queryByText(/Practice data/)).toBeNull()
  })
})


describe('AnswerBubble cards', () => {
  const CARD_ANSWER = message({
    id: 'answer-cards',
    position: 3,
    content: [
      'Here is the draft and a question.',
      '```email-draft',
      JSON.stringify({ to: 'sam@example.com', subject: 'Welcome', body: 'Hello Sam.' }),
      '```',
      '```choices',
      JSON.stringify({ question: 'Send it today?', options: ['Yes', 'No'] }),
      '```',
    ].join('\n'),
  })

  it("sends a card's request to the agent that wrote the answer, naming this answer and the card, with the draft as data", async () => {
    const onCardSend = vi.fn(async () => true)
    renderBubble({ message: CARD_ANSWER, messages: [CARD_ANSWER], onCardSend })

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Ask Customer Support to send it' }))
    })
    expect(onCardSend).toHaveBeenCalledWith({
      text: 'Please send my edited email "Welcome" to sam@example.com, exactly as written in the draft.',
      agentId: 'agent-1',
      card: {
        messageId: 'answer-cards',
        index: 0,
        kind: 'email-draft',
        content: { to: 'sam@example.com', cc: '', subject: 'Welcome', body: 'Hello Sam.' },
      },
    })

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Yes' }))
    })
    const { text, content } = choiceRequest('Send it today?', 'Yes')
    expect(onCardSend).toHaveBeenLastCalledWith({
      text,
      agentId: 'agent-1',
      card: { messageId: 'answer-cards', index: 1, kind: 'choices', content },
    })
  })

  /** The person's message as the server records one sent from a card of CARD_ANSWER. */
  const fromCard = (id: string, position: number, index: number, content: Record<string, unknown>, authorId = 'user-1') =>
    message({
      id,
      position,
      kind: 'text',
      authorKind: 'user',
      authorId,
      agentId: null,
      content: 'My choice',
      detail: { context: { card: { messageId: 'answer-cards', index, kind: 'choices', content } } },
    })

  it('marks a choice sent from this card, found by the card and never by words typed in the thread', () => {
    const typed = message({ id: 'typed', position: 4, kind: 'text', authorKind: 'user', agentId: null, content: 'Yes' })
    const chosen = fromCard('reply-1', 5, 1, { question: 'Send it today?', option: 'No' })
    renderBubble({ message: CARD_ANSWER, messages: [CARD_ANSWER, typed, chosen], onCardSend: vi.fn(async () => true), me: 'user-1' })
    expect(screen.getByRole('button', { name: 'No' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Yes' })).toBeDisabled()
    expect(screen.getByText('You chose "No".')).toBeInTheDocument()
  })

  it('does not mark a choice for a "Yes" typed in the thread', () => {
    const typed = message({ id: 'typed', position: 4, kind: 'text', authorKind: 'user', agentId: null, content: 'yes' })
    renderBubble({ message: CARD_ANSWER, messages: [CARD_ANSWER, typed], onCardSend: vi.fn(async () => true) })
    expect(screen.getByRole('button', { name: 'Yes' })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByRole('button', { name: 'No' })).toBeEnabled()
  })

  it("shows a card's request that is still waiting its turn as already sent, after a reload or in another tab", () => {
    const waiting: QueuedMessage = {
      id: 'q1',
      order: 1,
      authorId: 'user-1',
      text: 'Please send my edited email',
      agentIds: ['agent-1'],
      attachmentCount: 0,
      createdAt: '2026-10-12T00:00:00Z',
      updatedAt: '2026-10-12T00:00:00Z',
      status: 'queued',
      canManage: true,
      card: { messageId: 'answer-cards', index: 0, kind: 'email-draft', content: { to: 'sam@example.com' } },
    }
    renderBubble({ message: CARD_ANSWER, messages: [CARD_ANSWER], onCardSend: vi.fn(async () => true), me: 'user-1', queued: [waiting] })
    expect(screen.getByText(/You already asked Customer Support to send this draft; the request waits its turn\./)).toBeInTheDocument()
  })

  it('reads and copies only, with no send, for a role that cannot start work', () => {
    renderBubble({ message: CARD_ANSWER, messages: [CARD_ANSWER] })
    expect(screen.queryByRole('button', { name: /send it/ })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Yes' })).toBeNull()
    expect(screen.getAllByText(/cannot ask agents to act on it/)).toHaveLength(2)
  })

  it('copies the answer with its cards written out as text, not as JSON', async () => {
    const writeText = vi.fn(async () => {})
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    renderBubble({ message: CARD_ANSWER, messages: [CARD_ANSWER] })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Copy answer' }))
    })
    expect(writeText).toHaveBeenCalledWith(
      'Here is the draft and a question.\nTo: sam@example.com\nSubject: Welcome\n\nHello Sam.\nSend it today?\n- Yes\n- No',
    )
  })
})
