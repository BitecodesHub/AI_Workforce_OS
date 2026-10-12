// @find: tests for answer cards in chat, card request body, card send failure, no banner retry for a card, Chat cards, vitest, Chat page
// @what: Tests that a card in a Chat answer sends its request naming the card, and that a failed card request is tried again from the card, never from the shared banner.
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
 * A card's request ("Ask the agent to send it", a choice, a ticked checklist) goes out through
 * Chat's own send, naming the answer and the card, with the card's content as data. When it fails,
 * the card says so and its button stays ready; the shared "Your message was not sent" banner is
 * for typed messages, and its "Try again" would have sent the card's words as a typed message -
 * possibly as the answer to an open question - without the card.
 */

const EMPTY_PAGE: ConversationPage = { pinned: [], needsYou: [], conversations: [], hasMore: false }

const CARD = '```choices\n{"question":"Send the welcome email today?","options":["Yes","No"]}\n```'

const DETAIL: ConversationDetail = {
  conversation: {
    id: 'live',
    title: 'Onboarding',
    createdBy: 'user-1',
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    lastMessagePreview: 'Here is the welcome email',
    messageCount: 2,
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
      content: 'Draft a welcome email for Jane',
      detail: {},
      goalId: null,
      createdAt: '2026-10-01T00:00:00Z',
    },
    {
      id: 'a1',
      position: 1,
      authorKind: 'agent',
      authorId: null,
      agentId: 'agent-hr',
      kind: 'answer',
      content: `Here is the welcome email.\n\n${CARD}`,
      detail: {},
      goalId: null,
      createdAt: '2026-10-01T00:01:00Z',
    },
  ],
  goals: [],
  questions: [],
  hasEarlier: false,
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

let sends: Array<Record<string, unknown>>
let sendResponse: () => Response

beforeEach(() => {
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
  sends = []
  sendResponse = () => json({ code: 'validation', detail: 'The card is not valid.' }, 400)
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString()
      if (url === '/api/conversations/live/messages' && init?.method === 'POST') {
        sends.push(JSON.parse(String(init.body)) as Record<string, unknown>)
        return Promise.resolve(sendResponse())
      }
      if (/^\/api\/conversations\/live\?/.test(url)) return Promise.resolve(json(DETAIL))
      if (url.startsWith('/api/conversations?')) return Promise.resolve(json(EMPTY_PAGE))
      return Promise.reject(new Error(`No network in tests: ${url}`))
    }),
  )
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
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

const option = (name: string) => screen.getByText(name, { selector: 'button.answer-choice' })

describe('Chat: answer cards', { timeout: 20_000 }, () => {
  it("sends a card's request to the card's agent, naming the answer and the card, as a new request", async () => {
    sendResponse = () => json({ messages: [], queued: null })
    renderChat('/chat?c=live')
    await waitFor(() => expect(option('Yes')).toBeInTheDocument())

    await act(async () => {
      fireEvent.click(option('Yes'))
    })

    await waitFor(() => expect(sends).toHaveLength(1))
    expect(sends[0]).toEqual({
      text: 'My choice for "Send the welcome email today?": Yes',
      agentIds: ['agent-hr'],
      card: { messageId: 'a1', index: 0, kind: 'choices', content: { question: 'Send the welcome email today?', option: 'Yes' } },
    })
  })

  it('keeps a failed card request on the card, to be tried again there, never in the banner', async () => {
    renderChat('/chat?c=live')
    await waitFor(() => expect(option('Yes')).toBeInTheDocument())

    await act(async () => {
      fireEvent.click(option('Yes'))
    })

    await waitFor(() => expect(screen.getByText('Your choice could not be sent. Try again.')).toBeInTheDocument())
    expect(screen.queryByText(/Your message was not sent\./)).toBeNull()
    expect(await screen.findByText('Your request could not be sent. The card is not valid.')).toBeInTheDocument()

    // Tried again from the card: the same request, card and all.
    sendResponse = () => json({ messages: [], queued: null })
    await act(async () => {
      fireEvent.click(option('Yes'))
    })
    await waitFor(() => expect(sends).toHaveLength(2))
    expect(sends[1]).toEqual(sends[0])
    expect(sends[1]).toHaveProperty('card.messageId', 'a1')
  })
})
