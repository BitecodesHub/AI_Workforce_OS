// @find: tests for the Workflows page, list workflows, last run, watch or replay link, delete workflow after confirming, empty state, read-only role, replay recent work, /workflows
// @what: Tests that the Workflows page lists workflows with their last run, deletes only after a confirmation, offers replays of recent multi-step goals, and offers changes only to a role that may make them.
// @flow: Renders routes/Workflows with a mocked fetch inside the query client, router and toast providers.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import type { WorkflowSummary } from '../lib/workflowQueries'
import { Workflows } from './Workflows'

if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

const summary = (overrides: Partial<WorkflowSummary> & Pick<WorkflowSummary, 'id' | 'name'>): WorkflowSummary => ({
  description: '',
  steps: 3,
  connections: 2,
  version: 0,
  createdBy: 'user-1',
  updatedBy: 'user-1',
  createdAt: '2026-10-12T08:00:00Z',
  updatedAt: '2026-10-12T08:00:00Z',
  lastRun: null,
  ...overrides,
})

const REPLY = summary({
  id: 'wf-1',
  name: 'Reply to a customer',
  description: 'Research, draft and send',
  lastRun: { goalId: 'goal-1', status: 'completed', startedAt: '2026-10-12T09:00:00Z', completedAt: '2026-10-12T09:05:00Z' },
})
const DIGEST = summary({
  id: 'wf-2',
  name: 'Morning digest',
  steps: 1,
  connections: 0,
  lastRun: { goalId: 'goal-2', status: 'running', startedAt: '2026-10-12T09:00:00Z', completedAt: null },
})
const FRESH = summary({ id: 'wf-3', name: 'Not run yet' })

const GOALS = [
  {
    id: 'goal-chain',
    title: 'Answer the supplier',
    description: '',
    status: 'completed',
    createdAt: '2026-10-11T09:00:00Z',
    completedAt: '2026-10-11T09:10:00Z',
    source: 'chat',
    requestedBy: 'user-1',
    conversationId: null,
    scheduleId: null,
    tasks: [
      { id: 't1', agentId: 'a', title: 'One', status: 'completed', position: 0, dependsOn: [], attempt: 1, maxAttempts: 2, result: null, failureReason: null, startedAt: null, completedAt: null, runId: null },
      { id: 't2', agentId: 'b', title: 'Two', status: 'completed', position: 1, dependsOn: [], attempt: 1, maxAttempts: 2, result: null, failureReason: null, startedAt: null, completedAt: null, runId: null },
    ],
  },
  {
    id: 'goal-single',
    title: 'One step only',
    description: '',
    status: 'completed',
    createdAt: '2026-10-11T09:00:00Z',
    completedAt: '2026-10-11T09:10:00Z',
    source: 'manual',
    requestedBy: 'user-1',
    conversationId: null,
    scheduleId: null,
    tasks: [
      { id: 't3', agentId: 'a', title: 'Only', status: 'completed', position: 0, dependsOn: [], attempt: 1, maxAttempts: 2, result: null, failureReason: null, startedAt: null, completedAt: null, runId: null },
    ],
  },
]

let calls: Array<{ url: string; method: string }>
let rows: WorkflowSummary[]
let goals: unknown[]

beforeEach(() => {
  calls = []
  rows = [REPLY, DIGEST, FRESH]
  goals = GOALS
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = init?.method ?? 'GET'
      calls.push({ url, method })
      if (url === '/api/workflows' && method === 'GET') return json(rows)
      if (url.startsWith('/api/workflows/') && method === 'DELETE') {
        rows = rows.filter((row) => row.id !== url.split('/').pop())
        return new Response(null, { status: 204 })
      }
      if (url === '/api/goals') return json(goals)
      return new Response(null, { status: 404 })
    }),
  )
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
  window.history.replaceState(null, '', '/')
})

function signIn(permissions: string[]) {
  saveSession('test-token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions,
    displayName: 'Maya Manager',
    email: 'maya@demo.test',
    role: 'manager',
  })
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <Workflows />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
}

describe('Workflows page', () => {
  it('lists each workflow with its size and a way to watch or replay its last run', async () => {
    signIn(['task:read', 'task:create'])
    renderPage()
    const reply = (await screen.findByRole('heading', { name: 'Reply to a customer' })).closest('article')!
    expect(within(reply).getByText('3 steps, 2 connections')).toBeInTheDocument()
    expect(within(reply).getByText('Completed')).toBeInTheDocument()
    expect(within(reply).getByRole('link', { name: 'Replay the last run' })).toHaveAttribute('href', '/workflows/run/goal-1')
    expect(within(reply).getByRole('link', { name: 'Open the editor' })).toHaveAttribute('href', '/workflows/wf-1')

    const digest = screen.getByRole('heading', { name: 'Morning digest' }).closest('article')!
    expect(within(digest).getByRole('link', { name: 'Watch the run' })).toHaveAttribute('href', '/workflows/run/goal-2')
    const fresh = screen.getByRole('heading', { name: 'Not run yet' }).closest('article')!
    expect(within(fresh).getByText('Not run yet', { selector: 'dd span' })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'New workflow' })[0]).toHaveAttribute('href', '/workflows/new')
  })

  it('deletes a workflow only after it is confirmed', async () => {
    signIn(['task:read', 'task:create'])
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Delete Morning digest' }))
    expect(calls.some((call) => call.method === 'DELETE')).toBe(false)
    expect(screen.getByRole('heading', { name: 'Delete Morning digest?' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(calls.some((call) => call.method === 'DELETE' && call.url === '/api/workflows/wf-2')).toBe(true))
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Morning digest' })).not.toBeInTheDocument())
  })

  it('offers replays of recent goals that passed work between agents', async () => {
    signIn(['task:read'])
    renderPage()
    const link = await screen.findByRole('link', { name: 'Replay Answer the supplier' })
    expect(link).toHaveAttribute('href', '/workflows/run/goal-chain')
    expect(screen.queryByRole('link', { name: 'Replay One step only' })).not.toBeInTheDocument()
  })

  it('offers Delete only on the workflows this person may change', async () => {
    rows = [summary({ ...REPLY, canChange: false }), summary({ ...DIGEST, canChange: true })]
    signIn(['task:read', 'task:create'])
    renderPage()
    await screen.findByRole('heading', { name: 'Reply to a customer' })
    expect(screen.queryByRole('button', { name: 'Delete Reply to a customer' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Delete Morning digest' })).toBeInTheDocument()
  })

  it('shows more replays on request, beyond the first six', async () => {
    const chain = GOALS[0] as { id: string; title: string }
    goals = Array.from({ length: 8 }, (_, index) => ({ ...chain, id: `goal-${index}`, title: `Chain ${index + 1}` }))
    signIn(['task:read'])
    renderPage()
    expect(await screen.findByRole('link', { name: 'Replay Chain 6' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Replay Chain 7' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Show more' }))
    expect(screen.getByRole('link', { name: 'Replay Chain 8' })).toHaveAttribute('href', '/workflows/run/goal-7')
    expect(screen.queryByRole('button', { name: 'Show more' })).not.toBeInTheDocument()
  })

  it('shows a role that cannot start work no way to build, change or delete one', async () => {
    signIn(['task:read'])
    renderPage()
    const reply = (await screen.findByRole('heading', { name: 'Reply to a customer' })).closest('article')!
    expect(within(reply).getByRole('link', { name: 'Open' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'New workflow' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Delete/ })).not.toBeInTheDocument()
  })

  it('explains an empty workspace and how to start', async () => {
    rows = []
    signIn(['task:read', 'task:create'])
    renderPage()
    expect(await screen.findByRole('heading', { name: 'No workflows yet' })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'New workflow' })).toHaveLength(2)
  })
})
