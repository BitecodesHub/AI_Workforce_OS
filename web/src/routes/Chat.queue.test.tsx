// @find: tests for chat message queueing, queued message, cancel queued, vitest, Chat component tests, Chat page
// @what: Automated tests that check the chat message queueing screen (/chat) behaves as users expect.
// @flow: Renders Chat from Chat.tsx inside a QueryClientProvider and RouterProvider with mocked API calls
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ConversationDetail, ConversationPage, QueuedMessage } from '../lib/queries'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import { Chat } from './Chat'

/*
 * One answer at a time per conversation: a message sent while an answer is being worked on is
 * queued by the server and shown as a queued bubble after the thread, never as an ordinary sent
 * message, and the message box stays usable meanwhile.
 */

const EMPTY_PAGE: ConversationPage = { pinned: [], needsYou: [], conversations: [], hasMore: false }

const BASE: ConversationDetail = {
  conversation: {
    id: 'live',
    title: 'Q3 quotes',
    createdBy: 'user-1',
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    lastMessagePreview: 'Compare the quotes',
    messageCount: 1,
    pinned: false,
    archived: false,
    activity: 'idle',
    canManage: true,
    unread: false,
    match: null,
  },
  messages: [
    {
      id: 'u1',
      position: 0,
      authorKind: 'user',
      authorId: 'user-1',
      agentId: null,
      kind: 'text',
      content: 'Compare the quotes',
      detail: {},
      goalId: null,
      createdAt: '2026-10-01T00:00:00Z',
    },
  ],
  goals: [],
  questions: [],
  hasEarlier: false,
}

const BUSY: ConversationDetail = { ...BASE, busy: 'working', queued: [] }

const QUEUED: QueuedMessage = {
  id: 'q1',
  order: 1,
  authorId: 'user-1',
  text: 'Also check the delivery dates',
  agentIds: [],
  attachmentCount: 0,
  createdAt: '2026-10-01T00:01:00Z',
  updatedAt: '2026-10-01T00:01:00Z',
  status: 'queued',
  canManage: true,
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

function memoryStorage(seed: Record<string, string> = {}): Storage {
  const items = new Map<string, string>(Object.entries(seed))
  return {
    get length() {
      return items.size
    },
    clear: () => items.clear(),
    getItem: (key) => items.get(key) ?? null,
    key: (index) => [...items.keys()][index] ?? null,
    removeItem: (key) => void items.delete(key),
    setItem: (key, value) => void items.set(key, String(value)),
  }
}

const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
let currentDetail: ConversationDetail
let releasePost: (() => void) | null
let posts: Array<{ url: string; method: string }>

beforeEach(() => {
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: memoryStorage() })
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
  currentDetail = BUSY
  releasePost = null
  posts = []
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString()
      const method = (init?.method ?? 'GET').toUpperCase()
      if (method !== 'GET') posts.push({ url, method })
      if (url === '/api/conversations/live/messages' && method === 'POST') {
        // The server stores the message as queued; the next read reports it in the queue.
        return new Promise<Response>((resolve) => {
          releasePost = () => {
            currentDetail = { ...BUSY, queued: [QUEUED] }
            resolve(json({ messages: [], queued: QUEUED }, 202))
          }
        })
      }
      if (url === '/api/conversations/live/queue/q1' && method === 'DELETE') {
        currentDetail = { ...BUSY, queued: [] }
        return Promise.resolve(new Response(null, { status: 204 }))
      }
      if (/^\/api\/conversations\/live\?/.test(url)) return Promise.resolve(json(currentDetail))
      if (url.startsWith('/api/conversations?')) return Promise.resolve(json(EMPTY_PAGE))
      return Promise.reject(new Error(`No network in tests: ${url}`))
    }),
  )
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
  if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage)
  window.history.pushState({}, '', '/')
})

function renderChat(path: string) {
  window.history.pushState({}, '', path)
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

const composer = () => document.getElementById('chat-composer-input') as HTMLTextAreaElement
const title = () => document.getElementById('chat-thread-title')?.textContent

describe('Chat: sending while an answer is in progress', { timeout: 20_000 }, () => {
  it('queues the message and shows it as queued, never as a sent message, and it can be cancelled', async () => {
    renderChat('/chat?c=live')
    await waitFor(() => expect(title()).toBe('Q3 quotes'))

    // The box stays usable while work is in progress.
    expect(composer()).not.toBeDisabled()
    fireEvent.change(composer(), { target: { value: 'Also check the delivery dates', selectionStart: 29 } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    // While the send travels it already reads as queued, not as a sent message.
    expect(await screen.findByText('Adding this to the queue…')).toBeInTheDocument()
    expect(screen.queryByText('Finding the right agent for this.')).toBeNull()
    const userBubbles = () =>
      [...document.querySelectorAll('.chat-bubble-user')].filter((el) => el.textContent?.includes('delivery dates'))
    expect(userBubbles()).toHaveLength(0)

    await waitFor(() => expect(releasePost).not.toBeNull())
    releasePost!()

    expect(await screen.findByText('Queued — will start when the current answer finishes')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('Adding this to the queue…')).toBeNull())
    expect(screen.getAllByText('Also check the delivery dates')).toHaveLength(1)
    expect(userBubbles()).toHaveLength(0)
    await waitFor(() =>
      expect(screen.getByText('Message queued. It will start when the current answer finishes.')).toBeInTheDocument(),
    )

    fireEvent.click(screen.getByRole('button', { name: 'Cancel queued message' }))
    await waitFor(() => expect(screen.queryByText('Queued — will start when the current answer finishes')).toBeNull())
    expect(posts.some((p) => p.method === 'DELETE' && p.url === '/api/conversations/live/queue/q1')).toBe(true)
  })

  // Regression: while a first message was still being routed (the planner can take twenty
  // seconds) the box refused Enter without a word, so a follow-up could never be queued.
  it('sends a follow-up while the first message is still being routed, and shows it as queued', async () => {
    currentDetail = { ...BASE, busy: 'idle', queued: [] }
    renderChat('/chat?c=live')
    await waitFor(() => expect(title()).toBe('Q3 quotes'))

    fireEvent.change(composer(), { target: { value: 'Compare the quotes again', selectionStart: 24 } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    expect(await screen.findByText('Finding the right agent for this.')).toBeInTheDocument()

    fireEvent.change(composer(), { target: { value: 'Also check the delivery dates', selectionStart: 29 } })
    const send = screen.getByRole('button', { name: 'Send' })
    expect(send).not.toBeDisabled()
    fireEvent.click(send)

    await waitFor(() => expect(posts.filter((p) => p.method === 'POST' && p.url === '/api/conversations/live/messages')).toHaveLength(2))
    expect(await screen.findByText('Adding this to the queue…')).toBeInTheDocument()
  })

  it('shows the queue the server reports, in order, with the waiting-for-decision wording', async () => {
    currentDetail = {
      ...BASE,
      busy: 'waiting_decision',
      queued: [{ ...QUEUED, id: 'q2', order: 2, text: 'Then draft the email' }, QUEUED],
    }
    renderChat('/chat?c=live')
    const bubbles = await screen.findAllByRole('article', { name: /Queued message/ })
    expect(bubbles).toHaveLength(2)
    expect(bubbles[0]).toHaveTextContent('Also check the delivery dates')
    expect(bubbles[1]).toHaveTextContent('Then draft the email')
    expect(screen.getAllByText('Waiting for your decision above — this will start after it')).toHaveLength(2)
    expect(screen.getAllByRole('button', { name: 'Start now anyway' })).toHaveLength(2)
  })
})
