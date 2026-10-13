// @find: tests for working now, live tiles from board data, agents working now, open agent desk from tile, Agents page
// @what: Component tests for WorkingNow: one tile per working agent from the board with its caption (as a sentence, from the run's glance rather than its whole trace) and live frame, the desk opening from a tile, tiles not polling while it is open, and the empty state.
// @flow: Renders WorkingNow inside QueryClientProvider, RouterProvider and ToastProvider with a mocked fetch
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../../lib/router'
import { clearSession, saveSession } from '../../lib/session'
import { ToastProvider } from '../../lib/toast'
import { WorkingNow } from './WorkingNow'

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

const STARTED = new Date(Date.now() - 125_000).toISOString()

function boardAgent(id: string, name: string, runs: { running?: string[]; waiting?: string[] } = {}) {
  return {
    id,
    name,
    category: 'operations',
    status: 'active',
    fallback: false,
    runningRunIds: runs.running ?? [],
    waitingRunIds: runs.waiting ?? [],
    askingRunIds: [],
    queued: 0,
  }
}

function board(agents: ReturnType<typeof boardAgent>[]) {
  return {
    generatedAt: new Date().toISOString(),
    timezone: 'UTC',
    window: 'PT2H',
    windowMinutes: 120,
    stats: { running: 1, waitingApproval: 1, waitingInput: 0, queued: 0, held: 0, completedToday: 0, failedToday: 0, spendToday: 0 },
    agents,
    goals: [],
    queue: [],
    timeline: [{ runId: 'r-research', agentId: 'a-research', goalId: null, status: 'running', startedAt: STARTED, completedAt: null }],
    questions: [],
    approvals: [],
    failedToday: [],
  }
}

const BROWSING_STEPS = [
  { id: 's0', position: 0, kind: 'note', detail: { type: 'instruction', content: 'Find the price.' }, promptTokens: 0, completionTokens: 0, durationMs: 0, occurredAt: STARTED },
  { id: 's1', position: 1, kind: 'model_call', detail: { toolCalls: ['browser.open_page'] }, promptTokens: 0, completionTokens: 0, durationMs: 0, occurredAt: STARTED },
]

let current: ReturnType<typeof board>
let live: { busy: boolean; action: string; since: null; url: string; title: string; frame: string }
let requested: string[]

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  current = board([
    boardAgent('a-research', 'Research', { running: ['r-research'] }),
    boardAgent('a-idle', 'Idle'),
    boardAgent('a-support', 'Support', { waiting: ['r-support'] }),
  ])
  live = { busy: true, action: 'Opening example.com', since: null, url: 'https://example.com/', title: 'Example', frame: 'AAAA' }
  requested = []
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      requested.push(url)
      if (url.startsWith('/api/orchestrator/board')) return json(200, current)
      if (url === '/api/runs/r-research/glance') {
        return json(200, { startedAt: STARTED, kind: 'model_call', detail: { toolCalls: ['browser.open_page'] }, occurredAt: STARTED, browsing: true })
      }
      if (url === '/api/runs/r-research/steps') return json(200, BROWSING_STEPS)
      if (url === '/api/runs/r-research/browser/live') return json(200, live)
      if (url === '/api/runs/r-support/glance') {
        return json(200, { startedAt: STARTED, kind: 'note', detail: { type: 'instruction' }, occurredAt: STARTED, browsing: false })
      }
      if (url === '/api/runs/r-research') {
        return json(200, {
          id: 'r-research',
          agentId: 'a-research',
          status: 'running',
          trigger: 'manual',
          stepCount: 1,
          promptTokens: 0,
          completionTokens: 0,
          cost: 0,
          startedAt: STARTED,
        })
      }
      if (url === '/api/runs/r-research/takeover') {
        return json(200, {
          status: 'none',
          active: false,
          starting: false,
          releasedBy: null,
          takenBy: null,
          takenAt: null,
          endsAt: null,
          mine: false,
          canTakeOver: false,
          canRelease: false,
          actions: 0,
        })
      }
      return json(404, { code: 'not_found', detail: 'Not here.' })
    }),
  )
  saveSession('token', {
    userId: 'user-1',
    workspaceId: 'ws-1',
    permissions: ['agent:read', 'run:read'],
    displayName: 'Olivia Owner',
    email: 'olivia@example.test',
    role: 'viewer',
  })
})

afterEach(() => {
  clearSession()
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

function show() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <WorkingNow />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
}

describe('WorkingNow', () => {
  it('shows one live tile per agent with work in hand, from the board', async () => {
    const { container } = show()

    const research = await screen.findByRole('button', { name: 'Watch Research live' })
    const support = screen.getByRole('button', { name: 'Watch Support live' })
    expect(screen.queryByRole('button', { name: 'Watch Idle live' })).not.toBeInTheDocument()
    expect(screen.getByText('2 working')).toBeInTheDocument()

    // What the browser says it is doing, how long the run has gone, and the small live frame.
    expect(await within(research).findByText('Opening example.com')).toBeInTheDocument()
    expect(within(research).getByText(/^2 min/)).toBeInTheDocument()
    expect(await within(research).findByText('Live')).toBeInTheDocument()
    expect(container.querySelector('.desk-tile-frame img')?.getAttribute('src')).toBe('data:image/jpeg;base64,AAAA')
    expect(within(support).getByText('Waiting for an approval')).toBeInTheDocument()
    // The state is said once: the caption already says it.
    expect(support).toHaveAccessibleDescription('Waiting for an approval')
    expect(research).toHaveAccessibleDescription('Working. Opening example.com')
    expect(support.querySelector('.desk-tile-frame')).toBeNull()
    // A tile reads its run at a glance, never the whole trace.
    expect(requested.some((url) => url.endsWith('/steps'))).toBe(false)
  })

  it('captions what the agent is about to do as a sentence between browser steps', async () => {
    live = { ...live, busy: false, action: '' }
    show()

    const research = await screen.findByRole('button', { name: 'Watch Research live' })
    expect(await within(research).findByText('About to open a web page')).toBeInTheDocument()
    expect(research.textContent).not.toContain('·')
  })

  it('stops polling the tiles while a desk is open', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      show()
      fireEvent.click(await screen.findByRole('button', { name: 'Watch Research live' }))
      await screen.findByRole('dialog')
      const before = requested.filter((url) => url.endsWith('/glance')).length

      await vi.advanceTimersByTimeAsync(20_000)

      expect(requested.filter((url) => url.endsWith('/glance')).length).toBe(before)
    } finally {
      vi.useRealTimers()
    }
  })

  it('opens the Agent desk for the run of the tile clicked', async () => {
    show()

    fireEvent.click(await screen.findByRole('button', { name: 'Watch Research live' }))

    const desk = await screen.findByRole('dialog')
    expect(within(desk).getByText('Research at work')).toBeInTheDocument()
    expect(within(desk).getByRole('link', { name: 'Open full trace' })).toHaveAttribute('href', '/runs/r-research')
  })

  it('says so when no agent is working', async () => {
    current = board([boardAgent('a-idle', 'Idle')])
    show()

    expect(await screen.findByText(/No agent is working right now/)).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Agents working now' })).not.toBeInTheDocument()
  })
})
