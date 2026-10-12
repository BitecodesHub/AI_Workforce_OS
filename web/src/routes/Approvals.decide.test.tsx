// @find: tests for the approvals Decide view, decide one by one, view toggle, undo window delays the POST, phone default decide
// @what: Page tests for the Decide view of /approvals: the switch between List and Decide, the phone default, and the decision reaching POST /api/approvals/:id/decision only after the undo window.
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

let requests: Array<{ url: string; method: string; body: unknown }>
let queue: ApprovalItem[]

function renderPage(url: string) {
  window.history.replaceState({}, '', url)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
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

/** Pretends the screen is a phone (or not) for the (max-width: 767px) query. */
function screenWidth(narrow: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: narrow && query.includes('max-width: 767px'),
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
  }))
}

beforeEach(() => {
  requests = []
  queue = [item('a-1'), item('a-2')]
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      requests.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined })
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
    act(() => {
      fireEvent.keyDown(window, { key: 'a' })
    })
    expect(screen.getByText(/^Approved:/)).toBeInTheDocument()
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
    act(() => {
      fireEvent.keyDown(window, { key: 'a' })
    })
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(UNDO_WINDOW_MS * 2)
    })

    expect(requests.filter((request) => request.method === 'POST')).toEqual([])
    expect(screen.getByText('1 of 2')).toBeInTheDocument()
  })

  it('celebrates an empty queue in the Decide view', async () => {
    queue = []
    renderPage('/approvals?view=decide')

    expect(await screen.findByRole('heading', { name: 'Nothing is waiting for you' })).toBeInTheDocument()
  })
})
