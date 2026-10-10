// @find: tests for chat conversation errors, unavailable conversation, try again, vitest, Chat component tests, Chat page
// @what: Automated tests that check the chat conversation errors screen (/chat) behaves as users expect.
// @flow: Renders Chat from Chat.tsx inside a QueryClientProvider and RouterProvider with mocked API calls
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ConversationDetail, ConversationPage } from '../lib/queries'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import { Chat } from './Chat'

/*
 * A conversation that cannot be opened says so, in place of the thread, instead of looking like a
 * new empty chat: a deleted or inaccessible one offers a new conversation, any other failure
 * offers to try again, and in both nothing can be sent into it. Neither asks the server again on
 * its own.
 */

const EMPTY_PAGE: ConversationPage = { pinned: [], needsYou: [], conversations: [], hasMore: false }

const DETAIL: ConversationDetail = {
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
let storage: Storage
let detailRequests: string[]
let detailResponses: Array<() => Response>

beforeEach(() => {
  storage = memoryStorage({ 'chat.draft.user-1.dead': JSON.stringify('An unsent thought') })
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: storage })
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
      ({
        matches: false,
        media: query,
        addEventListener: () => {},
        removeEventListener: () => {},
      }) as unknown as MediaQueryList,
  )
  vi.stubGlobal('scrollTo', () => {})
  if (!Element.prototype.scrollTo) Element.prototype.scrollTo = () => {}
  detailRequests = []
  detailResponses = []
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString()
      const detail = /^\/api\/conversations\/(dead|broken|live)\?/.exec(url)
      if (detail) {
        detailRequests.push(url)
        const next = detailResponses.shift()
        if (next) return Promise.resolve(next())
        if (detail[1] === 'dead') return Promise.resolve(json({ code: 'not_found', detail: 'Conversation not found.' }, 404))
        if (detail[1] === 'broken') return Promise.resolve(json({ code: 'internal', detail: 'Something broke.' }, 500))
        return Promise.resolve(json(DETAIL))
      }
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

const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 100)))

// Plain lookups rather than role queries: the whole chat page is a large tree, and computing
// accessible names across all of it on every retry makes these tests slow.
const title = () => document.getElementById('chat-thread-title')?.textContent
const composer = () => document.getElementById('chat-composer-input') as HTMLTextAreaElement
const button = (name: string) => screen.getByText(name, { selector: 'button' })

// The whole route renders here, sidebar and all, so give it room on a busy machine.
describe('Chat: a conversation that cannot be opened', { timeout: 20_000 }, () => {
  it('says a deleted conversation is unavailable, and offers a new one', async () => {
    renderChat('/chat?c=dead')

    expect(await screen.findByText('This conversation was deleted or you do not have access to it.')).toBeInTheDocument()
    expect(title()).toBe('Conversation unavailable')
    // Still the Chat screen, under its eyebrow="Chat".
    expect(document.querySelector('.chat-thread-heading .eyebrow')?.textContent).toBe('Chat')
    expect(screen.queryByText('Visible to everyone in this workspace')).toBeNull()
    expect(composer()).toBeDisabled()
    // Its draft goes with it.
    await waitFor(() => expect(storage.getItem('chat.draft.user-1.dead')).toBeNull())

    await settle()
    expect(detailRequests).toHaveLength(1)

    fireEvent.click(button('Start a new conversation'))
    expect(window.location.pathname + window.location.search).toBe('/chat')
    expect(title()).toBe('New conversation')
    expect(composer()).not.toBeDisabled()
  })

  it('offers to try again after any other failure', async () => {
    renderChat('/chat?c=broken')

    expect(await screen.findByText('This conversation could not be loaded')).toBeInTheDocument()
    expect(title()).toBe('Conversation unavailable')
    expect(composer()).toBeDisabled()

    detailResponses.push(() => json({ ...DETAIL, conversation: { ...DETAIL.conversation, id: 'broken' } }))
    fireEvent.click(button('Try again'))
    await waitFor(() => expect(title()).toBe('Q3 quotes'))
    expect(composer()).not.toBeDisabled()
  })

  it('says so when the open conversation is deleted, and shows it as unavailable', async () => {
    const client = renderChat('/chat?c=live')
    await waitFor(() => expect(title()).toBe('Q3 quotes'))

    // Someone else deletes it: the next refresh is a 404, and so is every one after.
    detailResponses.push(
      () => json({ code: 'not_found', detail: 'Conversation not found.' }, 404),
      () => json({ code: 'not_found', detail: 'Conversation not found.' }, 404),
    )
    await act(async () => {
      await client.refetchQueries({ queryKey: ['conversations', 'live'] })
    })

    await waitFor(() => expect(title()).toBe('Conversation unavailable'))
    expect(screen.getAllByText('This conversation was deleted or you do not have access to it.').length).toBeGreaterThan(0)
    await settle()
    expect(detailRequests.length).toBeLessThanOrEqual(3)
  })
})
