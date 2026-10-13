// @find: tests for the approvals Decide view, decide one by one, view toggle, undo window delays the POST, phone default decide, failed poll keeps the deck, app put away keepalive, rotation keeps the view
// @what: Page tests for the Decide view of /approvals: the switch between List and Decide, the phone default (fixed for the visit), the decision reaching POST /api/approvals/:id/decision only after the undo window, a failed poll that keeps the deck and its Undo, and a decision sent as a keepalive request when the app is put away.
// @flow: Renders Approvals inside QueryClientProvider, RouterProvider and ToastProvider with fetch mocked
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Approvals } from './Approvals'
import type { ApprovalItem } from '../lib/approvalQueries'
import { UNDO_WINDOW_MS } from '../lib/decisionHold'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'

if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

function item(id: string, overrides: Partial<ApprovalItem> = {}): ApprovalItem {
  return {
    id,
    runId: `run-${id}`,
    agentId: 'agent-1',
    tool: 'gmail.send_message',
    actionClass: 'OUTBOUND',
    summary: 'Send something outside the workspace using gmail.send_message',
    payload: JSON.stringify({ to: 'jane@customer.example', subject: `Welcome ${id}`, body: 'Hello Jane' }),
    status: 'pending',
    requestedAt: '2026-10-12T09:00:00Z',
    expiresAt: '2026-10-13T09:00:00Z',
    decidedBy: null,
    decidedAt: null,
    decisionNote: null,
    goalId: 'goal-1',
    goalTitle: 'Welcome the new starter',
    requestedBy: 'user-2',
    taskInstruction: null,
    canDecide: true,
    ...overrides,
  }
}

let requests: Array<{ url: string; method: string; body: unknown; keepalive?: boolean }>
let queue: ApprovalItem[]
/** When true, the waiting queue's GET fails as a dropped connection would. */
let queueDown: boolean
/** The data client of the page rendered last, to refresh its queries as a poll would. */
let lastClient: QueryClient

function renderPage(url: string) {
  window.history.replaceState({}, '', url)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  lastClient = client
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <Approvals />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
}

/** Pretends the screen is a phone (or not) for the (max-width: 767px) query; returns a way to turn it, telling listeners. */
function screenWidth(narrow: boolean) {
  const screenNow = { narrow }
  const listeners = new Set<() => void>()
  vi.stubGlobal('matchMedia', (query: string) => ({
    get matches() {
      return screenNow.narrow && query.includes('max-width: 767px')
    },
    media: query,
    addEventListener: (_: string, listener: () => void) => listeners.add(listener),
    removeEventListener: (_: string, listener: () => void) => listeners.delete(listener),
    addListener: (listener: () => void) => listeners.add(listener),
    removeListener: (listener: () => void) => listeners.delete(listener),
  }))
  return (nowNarrow: boolean) => {
    screenNow.narrow = nowNarrow
    act(() => listeners.forEach((listener) => listener()))
  }
}

/** Presses a key inside the deck, where its keys work. */
function pressInDeck(key: string) {
  const deck = document.querySelector('.decide-deck')
  if (!deck) throw new Error('No deck')
  act(() => {
    fireEvent.keyDown(deck, { key })
  })
}

beforeEach(() => {
  requests = []
  queue = [item('a-1'), item('a-2')]
  queueDown = false
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      requests.push({
        url,
        method,
        body: init?.body ? JSON.parse(String(init.body)) : undefined,
        ...(init?.keepalive ? { keepalive: true } : {}),
      })
      if (queueDown && url.startsWith('/api/approvals?status=pending')) return Promise.reject(new TypeError('Failed to fetch'))
      if (url === '/api/approvals/elsewhere-1') {
        return Promise.resolve(
          new Response(JSON.stringify({ status: 404, code: 'not_found', detail: 'That item does not exist.' }), {
            status: 404,
            headers: { 'Content-Type': 'application/problem+json' },
          }),
        )
      }
      let body: unknown = []
      if (url.startsWith('/api/approvals?status=pending')) body = queue
      else if (url.startsWith('/api/approvals/count')) body = { pending: queue.length, canDecide: queue.length }
      else if (url.includes('/decision')) body = { approvalId: url.split('/')[3], status: 'approved', runStatus: 'running' }
      else if (url.startsWith('/api/agents')) {
        body = [{ id: 'agent-1', key: 'hr', name: 'HR Agent', category: 'operations', status: 'active', revision: 1 }]
      }
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    }),
  )
  saveSession('test-token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions: ['approval:read', 'approval:decide'],
    displayName: 'Maya Manager',
    email: 'maya@demo.test',
    role: 'manager',
  })
})

afterEach(() => {
  vi.useRealTimers()
  clearSession()
  vi.unstubAllGlobals()
  window.history.replaceState({}, '', '/')
})

describe('Approvals, the Decide view', () => {
  it('opens on the list on a wide screen and on Decide on a phone, and the switch moves between them', async () => {
    screenWidth(false)
    const wide = renderPage('/approvals')
    expect(await screen.findAllByRole('button', { name: 'Approve' })).toHaveLength(2)
    expect(screen.getByRole('button', { name: 'List' })).toHaveAttribute('aria-pressed', 'true')

    fireEvent.click(screen.getByRole('button', { name: 'Decide one by one' }))
    expect(await screen.findByText('1 of 2')).toBeInTheDocument()
    expect(window.location.search).toBe('?view=decide')
    wide.unmount()

    screenWidth(true)
    renderPage('/approvals')
    expect(await screen.findByText('1 of 2')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Decide one by one' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('sends the decision only when the undo window closes', async () => {
    renderPage('/approvals?view=decide')
    await screen.findByText('1 of 2')

    vi.useFakeTimers()
    pressInDeck('a')
    expect(screen.getByText(/^Approved:/, { selector: '.deck-undo-text' })).toBeInTheDocument()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(UNDO_WINDOW_MS - 100)
    })
    expect(requests.filter((request) => request.method === 'POST')).toEqual([])

    await act(async () => {
      await vi.advanceTimersByTimeAsync(100)
    })
    vi.useRealTimers()
    await waitFor(() =>
      expect(requests.filter((request) => request.method === 'POST')).toEqual([
        { url: '/api/approvals/a-1/decision', method: 'POST', body: { approved: true, mode: 'approve', note: null } },
      ]),
    )
  })

  it('never sends a decision that was undone', async () => {
    renderPage('/approvals?view=decide')
    await screen.findByText('1 of 2')

    vi.useFakeTimers()
    pressInDeck('a')
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(UNDO_WINDOW_MS * 2)
    })

    expect(requests.filter((request) => request.method === 'POST')).toEqual([])
    expect(screen.getByText('1 of 2')).toBeInTheDocument()
  })

  it('keeps the deck and its Undo through a failed refresh of the queue, and sends nothing early', async () => {
    renderPage('/approvals?view=decide')
    await screen.findByText('1 of 2')

    pressInDeck('a')
    queueDown = true
    // The background poll fails while the decision is still in its undo window.
    await act(async () => {
      await lastClient.refetchQueries({ queryKey: ['approvals'] })
    })

    expect(await screen.findByText(/Lost connection/)).toBeInTheDocument()
    expect(document.querySelector('.decide-deck')).not.toBeNull()
    expect(screen.getByRole('button', { name: 'Undo' })).toBeInTheDocument()
    expect(requests.filter((request) => request.method === 'POST')).toEqual([])
  })

  it('sends a decision still in its undo window as a keepalive request when the app is put away', async () => {
    renderPage('/approvals?view=decide')
    await screen.findByText('1 of 2')
    const visibility = { state: 'visible' as DocumentVisibilityState }
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => visibility.state })
    try {
      pressInDeck('a')
      expect(requests.filter((request) => request.method === 'POST')).toEqual([])

      visibility.state = 'hidden'
      act(() => {
        document.dispatchEvent(new Event('visibilitychange'))
      })

      await waitFor(() =>
        expect(requests.filter((request) => request.method === 'POST')).toEqual([
          {
            url: '/api/approvals/a-1/decision',
            method: 'POST',
            body: { approved: true, mode: 'approve', note: null },
            keepalive: true,
          },
        ]),
      )
    } finally {
      delete (document as unknown as { visibilityState?: unknown }).visibilityState
    }
  })

  it('a decision sent on the way to the List is not offered there again while it travels, and returns if it fails', async () => {
    // Seen in the browser on 13 Oct 2026: approving a card and switching to the List at once sent
    // the decision, but the List still showed the card with its Approve button until the server
    // answered, as if nothing had been decided.
    renderPage('/approvals?view=decide')
    await screen.findByText('1 of 2')
    let answer!: (response: Response) => void
    const answered = new Promise<Response>((resolve) => {
      answer = resolve
    })
    const plain = vi.mocked(fetch).getMockImplementation()!
    vi.mocked(fetch).mockImplementation((url, init) =>
      String(url).includes('/decision') ? answered : plain(url as string, init as RequestInit),
    )

    pressInDeck('a')
    fireEvent.click(screen.getByRole('button', { name: 'List' }))

    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Approve' })).toHaveLength(1))
    expect(document.getElementById('approval-a-1')).toBeNull()
    expect(document.getElementById('approval-a-2')).not.toBeNull()
    expect(vi.mocked(fetch).mock.calls.filter(([url]) => String(url).includes('/a-1/decision'))).toHaveLength(1)

    // The send fails: the request is still waiting, so it is offered again.
    await act(async () => {
      answer(
        new Response(JSON.stringify({ status: 503, code: 'upstream_unavailable', detail: 'Try again.' }), {
          status: 503,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      )
    })
    await waitFor(() => expect(document.getElementById('approval-a-1')).not.toBeNull())
  })

  it('a phone turned sideways keeps the Decide view it opened on, and the decision held in it', async () => {
    const turn = screenWidth(true)
    renderPage('/approvals')
    await screen.findByText('1 of 2')
    pressInDeck('a')

    turn(false)

    expect(screen.getByRole('button', { name: 'Decide one by one' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Undo' })).toBeInTheDocument()
    expect(requests.filter((request) => request.method === 'POST')).toEqual([])
  })

  it('a link to a request not in this workspace says it may belong to another workspace the person uses', async () => {
    renderPage('/approvals?view=decide#approval-elsewhere-1')

    expect(await screen.findByText(/not waiting in this workspace/)).toHaveTextContent(
      'That request is not waiting in this workspace. It was decided, withdrawn or expired, or it belongs to another workspace you use.',
    )
  })

  it('celebrates an empty queue in the Decide view', async () => {
    queue = []
    renderPage('/approvals?view=decide')

    expect(await screen.findByRole('heading', { name: 'Nothing is waiting for you' })).toBeInTheDocument()
  })
})
