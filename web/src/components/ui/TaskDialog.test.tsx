import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Agent, Goal } from '../../lib/queries'
import { RouterProvider } from '../../lib/router'
import { clearSession, saveSession } from '../../lib/session'
import { ToastProvider } from '../../lib/toast'
import { STARTED_MESSAGE, TaskDialog, lastAgentKey, waitForFirstRun } from './TaskDialog'

/*
 * Giving an agent a task answers as soon as the work is saved. The dialog closes at once, never
 * holds the person while the agent works, and opens the run - for a goal, once its first run has
 * appeared - or the goal on Tasks when it has not.
 */

// jsdom has the <dialog> element but not its modal methods.
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

function agent(overrides: Partial<Agent> = {}): Agent {
  return {
    id: 'agent-1',
    key: 'general',
    name: 'General Employee',
    category: 'general',
    status: 'active',
    revision: 1,
    summary: null,
    fallback: true,
    ...overrides,
  }
}

const AGENTS = [agent(), agent({ id: 'agent-2', key: 'ops', name: 'Ops Agent', category: 'operations', fallback: false })]

function goal(runId: string | null, failureReason: string | null = null): Goal {
  return {
    id: 'goal-1',
    title: 'Send the weekly report',
    description: '',
    status: 'running',
    createdAt: '2026-10-04T09:00:00Z',
    completedAt: null,
    source: 'manual',
    requestedBy: 'user-1',
    conversationId: null,
    scheduleId: null,
    tasks: [
      {
        id: 'task-1',
        agentId: 'agent-1',
        title: 'Send the weekly report',
        status: runId ? 'running' : 'pending',
        position: 0,
        dependsOn: [],
        attempt: runId ? 1 : 0,
        maxAttempts: 2,
        result: null,
        failureReason,
        startedAt: null,
        completedAt: null,
        runId,
      },
    ],
  }
}

/*
 * An in-memory localStorage. Recent Node versions define their own global localStorage, which
 * shadows jsdom's and reads as undefined without a --localstorage-file flag (see persist.test.ts).
 */
function memoryStorage(): Storage {
  const items = new Map<string, string>()
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

let calls: Array<{ url: string; method: string; body: unknown }>
let respond: (url: string, method: string) => Response | Promise<Response>

beforeEach(() => {
  calls = []
  respond = (url) => (url === '/api/agents' ? json(AGENTS) : new Response(null, { status: 404 }))
  vi.stubGlobal('localStorage', memoryStorage())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = init?.method ?? 'GET'
      calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined })
      return respond(url, method)
    }),
  )
  vi.stubGlobal('scrollTo', () => {})
  saveSession('test-token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions: ['task:create', 'agent:read'],
    displayName: 'Maya Manager',
    email: 'maya@example.com',
    role: 'manager',
  })
  window.history.pushState({}, '', '/')
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
})

function renderDialog(props: { agentId?: string } = {}) {
  const onClose = vi.fn()
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <TaskDialog open onClose={onClose} {...props} />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
  return { onClose }
}

async function giveTask(text: string) {
  fireEvent.change(screen.getByLabelText(/Instruction/), { target: { value: text } })
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: 'Start' }))
  })
}

describe('TaskDialog', () => {
  it("from an agent's page, creates a one-task goal for that agent, closes at once and opens its run", async () => {
    respond = (url, method) => {
      if (url === '/api/agents') return json(AGENTS)
      if (url === '/api/goals' && method === 'POST') return json(goal('run-7'), 201)
      return new Response(null, { status: 404 })
    }
    const { onClose } = renderDialog({ agentId: 'agent-2' })

    await giveTask('Check the stock levels')

    await waitFor(() => expect(window.location.pathname).toBe('/runs/run-7'))
    expect(onClose).toHaveBeenCalled()
    expect(screen.getByText(STARTED_MESSAGE)).toBeInTheDocument()
    const created = calls.find((call) => call.url === '/api/goals' && call.method === 'POST')
    expect(JSON.stringify(created?.body)).toContain('agent-2')
    expect(JSON.stringify(created?.body)).toContain('Check the stock levels')
    expect(calls.some((call) => call.url.endsWith('/runs'))).toBe(false)
  })

  it("creates a goal, closes before its run starts, then opens the run once it appears", async () => {
    let looks = 0
    respond = (url, method) => {
      if (url === '/api/agents') return json(AGENTS)
      if (url === '/api/goals' && method === 'POST') return json(goal(null), 201)
      if (url === '/api/goals/goal-1') {
        looks += 1
        return json(goal(looks > 1 ? 'run-9' : null))
      }
      return new Response(null, { status: 404 })
    }
    const { onClose } = renderDialog()
    await screen.findByRole('option', { name: /General Employee/ })

    await giveTask('Send the weekly report')

    // Closed with the goal saved, while its run is still being looked for.
    expect(onClose).toHaveBeenCalled()
    expect(window.location.pathname).toBe('/')
    expect(screen.getByText(STARTED_MESSAGE)).toBeInTheDocument()
    await waitFor(() => expect(window.location.pathname).toBe('/runs/run-9'), { timeout: 3_000 })
    expect(looks).toBe(2)
  })

  it('stays dismissible while the task is being saved', async () => {
    respond = (url) => (url === '/api/agents' ? json(AGENTS) : new Promise<Response>(() => {}))
    const { onClose } = renderDialog({ agentId: 'agent-2' })

    await giveTask('Check the stock levels')

    expect(screen.queryByText(/waits until it finishes/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeEnabled()
    const close = screen.getByRole('button', { name: 'Close' })
    expect(close).toBeEnabled()
    fireEvent.click(close)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('still saves, but stays on the page, when the dialog is dismissed before the save returns', async () => {
    let finish: (response: Response) => void = () => {}
    respond = (url, method) => {
      if (url === '/api/agents') return json(AGENTS)
      if (url === '/api/goals' && method === 'POST') {
        return new Promise<Response>((resolve) => {
          finish = resolve
        })
      }
      return new Response(null, { status: 404 })
    }
    function Closable() {
      const [open, setOpen] = useState(true)
      return <TaskDialog open={open} onClose={() => setOpen(false)} agentId="agent-2" />
    }
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <RouterProvider>
          <ToastProvider>
            <Closable />
          </ToastProvider>
        </RouterProvider>
      </QueryClientProvider>,
    )

    await giveTask('Check the stock levels')
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    await act(async () => {
      finish(json(goal('run-7'), 201))
    })

    await waitFor(() => expect(screen.getByText(STARTED_MESSAGE)).toBeInTheDocument())
    expect(window.location.pathname).toBe('/')
  })

  it('keeps the dialog open with the reason when the task cannot be saved', async () => {
    respond = (url) =>
      url === '/api/agents'
        ? json(AGENTS)
        : json({ code: 'policy_violation', detail: 'That agent is paused.', status: 422 }, 422)
    const { onClose } = renderDialog({ agentId: 'agent-2' })

    await giveTask('Check the stock levels')

    expect(await screen.findByText('That agent is paused.')).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
    expect(window.location.pathname).toBe('/')
  })

  it("remembers the agent per person, and drops the old key shared by everyone on the browser", async () => {
    localStorage.setItem('aiwos.lastAgentId', 'agent-2')
    respond = (url, method) => {
      if (url === '/api/agents') return json(AGENTS)
      if (url === '/api/goals' && method === 'POST') return json(goal('run-1'), 201)
      return new Response(null, { status: 404 })
    }
    renderDialog()
    await screen.findByRole('option', { name: /General Employee/ })

    // The old key is not read: the default is General Employee, not the agent it named.
    expect(localStorage.getItem('aiwos.lastAgentId')).toBeNull()
    expect(screen.getByLabelText(/Agent/)).toHaveValue('agent-1')

    fireEvent.change(screen.getByLabelText(/Agent/), { target: { value: 'agent-2' } })
    await giveTask('Count the stock')

    expect(lastAgentKey()).toBe('aiwos.lastAgentId.user-1')
    expect(localStorage.getItem('aiwos.lastAgentId.user-1')).toBe('agent-2')
    await waitFor(() => expect(window.location.pathname).toBe('/runs/run-1'))
  })
})

describe('waitForFirstRun', () => {
  it('answers at once when the goal already names its run', async () => {
    const load = vi.fn()
    const found = await waitForFirstRun(goal('run-1'), load, { waitMs: 50, pollMs: 10 })
    expect(found.tasks[0]?.runId).toBe('run-1')
    expect(load).not.toHaveBeenCalled()
  })

  it('gives up after the wait, leaving the goal without a run', async () => {
    const load = vi.fn(async () => goal(null))
    const found = await waitForFirstRun(goal(null), load, { waitMs: 60, pollMs: 10 })
    expect(found.tasks[0]?.runId).toBeNull()
    expect(load).toHaveBeenCalled()
  })

  it('stops looking once the task is known not to have started', async () => {
    const load = vi.fn(async () => goal(null, 'That agent is paused.'))
    const found = await waitForFirstRun(goal(null), load, { waitMs: 1_000, pollMs: 10 })
    expect(found.tasks[0]?.failureReason).toBe('That agent is paused.')
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('looks again after a read that fails', async () => {
    const load = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue(goal('run-3'))
    const found = await waitForFirstRun(goal(null), load, { waitMs: 1_000, pollMs: 10 })
    expect(found.tasks[0]?.runId).toBe('run-3')
    expect(load).toHaveBeenCalledTimes(2)
  })
})
