import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessage, ConversationDetail, ConversationPage } from '../lib/queries'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import { Chat } from './Chat'

/*
 * What a poll that brought nothing new costs an open conversation: no message is drawn again. A
 * poll that brought one new message asks only for what changed after the newest message held, and
 * draws the one that arrived. MessageItem is replaced by a counter, so what is measured is how
 * often each message is handed to be drawn.
 */

const drawn = vi.hoisted(() => [] as string[])

vi.mock('../components/chat/MessageItem', async () => {
  const { memo } = await import('react')
  return {
    MessageItem: memo(function CountedItem({ message }: { message: { id: string; content: string } }) {
      drawn.push(message.id)
      return <span>{message.content}</span>
    }),
  }
})

const EMPTY_PAGE: ConversationPage = { pinned: [], needsYou: [], conversations: [], hasMore: false }

function message(position: number): ChatMessage {
  return {
    id: `m${position}`,
    position,
    authorKind: 'user',
    authorId: 'user-1',
    agentId: null,
    kind: 'text',
    content: `Message number ${position}`,
    detail: {},
    goalId: null,
    createdAt: '2026-10-01T00:00:00Z',
  }
}

function detail(positions: number[], generatedAt: string): ConversationDetail {
  return {
    conversation: {
      id: 'live',
      title: 'Q3 quotes',
      createdBy: 'user-1',
      createdAt: '2026-10-01T00:00:00Z',
      updatedAt: '2026-10-01T00:00:00Z',
      lastMessagePreview: 'Compare the quotes',
      messageCount: positions.length,
      pinned: false,
      archived: false,
      activity: 'idle',
      canManage: true,
      unread: false,
      match: null,
    },
    messages: positions.map(message),
    goals: [],
    questions: [],
    hasEarlier: false,
    generatedAt,
  }
}

const json = (body: unknown) =>
  new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })

let detailRequests: string[]
let replies: Array<() => ConversationDetail>

beforeEach(() => {
  drawn.length = 0
  detailRequests = []
  replies = []
  saveSession('test-token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions: ['chat:use', 'task:create', 'agent:read'],
    displayName: 'Maya Manager',
    email: 'maya@example.com',
    role: 'manager',
  })
  vi.stubGlobal(
    'matchMedia',
    (query: string) =>
      ({ matches: false, media: query, addEventListener: () => {}, removeEventListener: () => {} }) as unknown as MediaQueryList,
  )
  vi.stubGlobal('scrollTo', () => {})
  if (!Element.prototype.scrollTo) Element.prototype.scrollTo = () => {}
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString()
      if (/^\/api\/conversations\/live\?/.test(url)) {
        detailRequests.push(url)
        const next = replies.shift()
        return Promise.resolve(json(next ? next() : detail([0, 1, 2], '2026-10-04T01:00:00.000Z')))
      }
      if (url.startsWith('/api/conversations?')) return Promise.resolve(json(EMPTY_PAGE))
      if (url === '/api/agents') return Promise.resolve(json([]))
      return Promise.reject(new Error(`No network in tests: ${url}`))
    }),
  )
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
})

function renderChat() {
  window.history.pushState({}, '', '/chat?c=live')
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <Chat />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
  return client
}

const poll = (client: QueryClient) =>
  act(async () => {
    await client.refetchQueries({ queryKey: ['conversations', 'live'] })
  })

describe('Chat: what a poll redraws', { timeout: 20_000 }, () => {
  it('draws no message again when the poll brought nothing new', async () => {
    const client = renderChat()
    expect(await screen.findByText('Message number 2')).toBeInTheDocument()
    expect(drawn.sort()).toEqual(['m0', 'm1', 'm2'])
    drawn.length = 0

    // Nothing changed, but the server's clock has moved on: the detail is a new object, its thread is not.
    replies.push(() => ({ ...detail([], '2026-10-04T01:00:03.000Z') }))
    await poll(client)
    await waitFor(() => expect(detailRequests).toHaveLength(2))
    await act(() => new Promise((resolve) => setTimeout(resolve, 50)))

    expect(drawn).toEqual([])
  })

  it('asks for what changed after the newest message, and draws the message that arrived', async () => {
    const client = renderChat()
    expect(await screen.findByText('Message number 2')).toBeInTheDocument()
    drawn.length = 0

    replies.push(() => detail([3], '2026-10-04T01:00:05.000Z'))
    await poll(client)

    expect(await screen.findByText('Message number 3')).toBeInTheDocument()
    const second = new URL(detailRequests[1]!, 'http://localhost').searchParams
    expect(second.get('after')).toBe('2')
    expect(second.get('since')).toBe('2026-10-04T01:00:00.000Z')
    expect(drawn).toContain('m3')
  })
})
