// @find: tests for Watch live on the agent page, Working now on the Agents page, agent desk entry points, run read permission hides live work
// @what: Page tests for the Agent desk's two ways in: the Working now section on /agents (only with run:read) and Watch live on /agents/:id while the agent is working.
// @flow: Renders Agents and AgentDetail inside QueryClientProvider, RouterProvider and ToastProvider with a mocked fetch
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import { AgentDetail } from './AgentDetail'
import { Agents } from './Agents'

// jsdom has the <dialog> element but not its modal methods; the desk opens as one.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

const STARTED = new Date(Date.now() - 30_000).toISOString()
const LEGAL = {
  id: 'a-legal',
  key: 'legal',
  name: 'Legal',
  category: 'legal',
  status: 'active',
  revision: 3,
  summary: 'You review contracts.',
  tools: [],
  systemPrompt: 'You review contracts.',
  maxSteps: 12,
  sealed: false,
  grants: [],
  fallback: false,
}
const RUN = {
  id: 'r-legal',
  agentId: 'a-legal',
  status: 'running',
  trigger: 'manual',
  stepCount: 1,
  promptTokens: 0,
  completionTokens: 0,
  cost: 0,
  startedAt: STARTED,
}
const BOARD = {
  generatedAt: STARTED,
  timezone: 'UTC',
  window: 'PT2H',
  windowMinutes: 120,
  stats: { running: 1, waitingApproval: 0, waitingInput: 0, queued: 0, held: 0, completedToday: 0, failedToday: 0, spendToday: 0 },
  agents: [{ ...LEGAL, runningRunIds: ['r-legal'], waitingRunIds: [], askingRunIds: [], queued: 0 }],
  goals: [],
  queue: [],
  timeline: [],
  questions: [],
  approvals: [],
  failedToday: [],
}

let permissions: string[]
let runs: Array<Record<string, unknown>>

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  permissions = ['agent:read', 'run:read']
  runs = [RUN]
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      const path = url.split('?')[0]
      if (method !== 'GET') return json(404, { code: 'not_found', detail: 'Not here.' })
      if (path === '/api/agents') return json(200, [LEGAL])
      if (path === '/api/agents/a-legal') return json(200, LEGAL)
      if (path === '/api/runs') return json(200, runs)
      if (path === '/api/orchestrator/board') return json(200, BOARD)
      if (path === '/api/runs/r-legal') return json(200, runs[0])
      if (path === '/api/runs/r-legal/steps') return json(200, [])
      if (path === '/api/runs/r-legal/glance') {
        return json(200, { startedAt: null, kind: null, detail: {}, occurredAt: null, browsing: false })
      }
      if (path === '/api/runs/r-legal/takeover') {
        return json(200, {
          status: 'none',
          active: false,
          starting: false,
          takenBy: null,
          takenAt: null,
          endsAt: null,
          mine: false,
          canTakeOver: false,
          canRelease: false,
          actions: 0,
          releasedBy: null,
        })
      }
      return json(404, { code: 'not_found', detail: 'Not here.' })
    }),
  )
})

afterEach(() => {
  clearSession()
  sessionStorage.clear()
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
})

function show(page: ReactNode) {
  saveSession('token', {
    userId: 'user-1',
    workspaceId: 'ws-1',
    permissions,
    displayName: 'Olivia Owner',
    email: 'olivia@example.test',
    role: 'manager',
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>{page}</ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
}

describe('Working now on the Agents page', () => {
  it('opens the page for someone who can read runs, with a tile per working agent', async () => {
    show(<Agents />)

    expect(await screen.findByRole('heading', { name: 'Working now' })).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Watch Legal live' })).toBeInTheDocument()
  })

  it('is left out for someone who cannot read runs, and the board is never asked for', async () => {
    permissions = ['agent:read']
    show(<Agents />)

    await screen.findByRole('heading', { name: 'Legal' })
    expect(screen.queryByRole('heading', { name: 'Working now' })).not.toBeInTheDocument()
    expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).startsWith('/api/orchestrator/board'))).toBe(false)
  })
})

describe('Watch live on the agent page', () => {
  it('opens the Agent desk on the run the agent is working on', async () => {
    show(<AgentDetail id="a-legal" />)

    fireEvent.click(await screen.findByRole('button', { name: 'Watch live' }))

    const desk = await screen.findByRole('dialog')
    expect(within(desk).getByText('Legal at work')).toBeInTheDocument()
    expect(within(desk).getByRole('link', { name: 'Open full trace' })).toHaveAttribute('href', '/runs/r-legal')
  })

  it('is not offered when the agent is not working', async () => {
    runs = [{ ...RUN, status: 'completed', completedAt: STARTED }]
    show(<AgentDetail id="a-legal" />)

    await screen.findByRole('heading', { name: 'Legal', level: 1 })
    expect(screen.queryByRole('button', { name: 'Watch live' })).not.toBeInTheDocument()
  })
})
