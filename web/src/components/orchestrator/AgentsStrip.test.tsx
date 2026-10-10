// @find: tests for agents strip, Pause all, Resume all, pause agent, resume agent, POST /api/agents/:id/pause, POST /api/agents/:id/resume, General Employee, bulk actions
// @what: Tests for the Orchestrator agents list and its pause all and resume all actions.
// @flow: Exercises AgentsStrip.tsx
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Board, BoardAgent } from '../../lib/queries'
import { clearSession, saveSession } from '../../lib/session'
import { ToastProvider } from '../../lib/toast'
import { AgentsStrip } from './AgentsStrip'

/*
 * The agents list on the Orchestrator and its two bulk actions. Resume all takes every paused
 * agent, the General Employee included, so Pause all then Resume all leaves chat able to answer;
 * both skip an agent already in the state asked for, and the toast counts only real changes. Each
 * row has its own spinner, and pausing the General Employee asks first.
 */

// jsdom has the <dialog> element but not its modal methods; every dialog here opens with one.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

const agent = (id: string, name: string, status: string, fallback = false): BoardAgent => ({
  id,
  name,
  category: 'operations',
  status,
  fallback,
  runningRunIds: [],
  waitingRunIds: [],
  askingRunIds: [],
  queued: 0,
})

const board = (agents: BoardAgent[]) => ({ agents, goals: [] }) as unknown as Board

type Call = { method: string; url: string }

let calls: Call[]
/** Agents whose request is refused, by id. */
let refused: Set<string>
/** Agents whose request waits until released, by id. */
let held: Map<string, () => void>

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  calls = []
  refused = new Set()
  held = new Map()
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      calls.push({ method, url })
      const match = /^\/api\/agents\/([^/]+)\/(pause|resume)$/.exec(url)
      if (method === 'POST' && match) {
        const id = match[1]!
        if (refused.has(id)) return json(409, { code: 'conflict', detail: 'It cannot be changed.' })
        if (!held.has(id) && id === 'a-slow') {
          await new Promise<void>((release) => held.set(id, release))
        }
        return json(200, { id, key: id, name: id, category: 'operations', status: match[2] === 'pause' ? 'paused' : 'active', revision: 1 })
      }
      return json(404, { code: 'not_found', detail: 'Not here.' })
    }),
  )
})

afterEach(() => {
  clearSession()
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

function open(agents: BoardAgent[], permissions = ['agent:update', 'task:create']) {
  saveSession('token', {
    userId: 'user-1',
    workspaceId: 'ws-1',
    permissions,
    displayName: 'Olivia Owner',
    email: 'olivia@example.test',
    role: 'owner',
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <AgentsStrip board={board(agents)} onOpenGoal={() => undefined} />
      </ToastProvider>
    </QueryClientProvider>,
  )
}

const posts = () => calls.filter((call) => call.method === 'POST').map((call) => call.url)

async function confirm(opener: string, dialogTitle: string, confirmLabel: string) {
  fireEvent.click(screen.getByRole('button', { name: opener }))
  const dialog = await screen.findByRole('dialog', { name: dialogTitle })
  await act(async () => {
    fireEvent.click(within(dialog).getByRole('button', { name: confirmLabel }))
  })
}

describe('Resume all', () => {
  it('resumes every paused agent, the General Employee included, and no other', async () => {
    open([
      agent('a-general', 'General Employee', 'paused', true),
      agent('a-legal', 'Legal', 'paused'),
      agent('a-hr', 'HR', 'active'),
      agent('a-old', 'Old agent', 'retired'),
    ])

    await confirm('Resume all', 'Resume every paused agent?', 'Resume all')

    expect(posts().sort()).toEqual(['/api/agents/a-general/resume', '/api/agents/a-legal/resume'])
    expect(await screen.findByText('Resumed 2 agents.')).toBeInTheDocument()
  })

  it('counts only the agents that were changed when one is refused', async () => {
    refused.add('a-legal')
    open([agent('a-general', 'General Employee', 'paused', true), agent('a-legal', 'Legal', 'paused'), agent('a-hr', 'HR', 'active')])

    await confirm('Resume all', 'Resume every paused agent?', 'Resume all')

    expect(await screen.findByText('Resumed 1 agent. 1 agent could not be resumed.')).toBeInTheDocument()
  })

  it('says there was nothing to do instead of reporting a change', async () => {
    open([agent('a-general', 'General Employee', 'active', true), agent('a-hr', 'HR', 'active')])

    await confirm('Resume all', 'Resume every paused agent?', 'Resume all')

    expect(posts()).toEqual([])
    expect(await screen.findByText('There was no paused agent to resume.')).toBeInTheDocument()
  })
})

describe('Pause all', () => {
  it('pauses the active agents, the General Employee included by default', async () => {
    open([agent('a-general', 'General Employee', 'active', true), agent('a-legal', 'Legal', 'paused'), agent('a-hr', 'HR', 'active')])

    await confirm('Pause all', 'Pause every agent?', 'Pause all')

    expect(posts().sort()).toEqual(['/api/agents/a-general/pause', '/api/agents/a-hr/pause'])
    expect(await screen.findByText('Paused 2 agents.')).toBeInTheDocument()
  })

  it('leaves the General Employee out when its box is cleared', async () => {
    open([agent('a-general', 'General Employee', 'active', true), agent('a-hr', 'HR', 'active')])

    fireEvent.click(screen.getByRole('button', { name: 'Pause all' }))
    const dialog = await screen.findByRole('dialog', { name: 'Pause every agent?' })
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'Include General Employee' }))
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Pause all' }))
    })

    expect(posts()).toEqual(['/api/agents/a-hr/pause'])
    expect(await screen.findByText('Paused 1 agent.')).toBeInTheDocument()
  })
})

describe('each row', () => {
  it('shows its own spinner while its own change is under way, and no other row does', async () => {
    open([agent('a-slow', 'Slow', 'active'), agent('a-hr', 'HR', 'active')])

    fireEvent.click(screen.getByRole('button', { name: 'Pause all' }))
    const dialog = await screen.findByRole('dialog', { name: 'Pause every agent?' })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Pause all' }))
    })

    // HR's change has landed, Slow's has not.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Pause Slow' })).toHaveAttribute('aria-busy', 'true'))
    expect(screen.getByRole('button', { name: 'Pause HR' })).not.toHaveAttribute('aria-busy')

    await act(async () => {
      held.get('a-slow')?.()
    })
    expect(await screen.findByText('Paused 2 agents.')).toBeInTheDocument()
  })

  it('pauses one agent without the others', async () => {
    open([agent('a-hr', 'HR', 'active'), agent('a-legal', 'Legal', 'active')])

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Pause HR' }))
    })

    expect(posts()).toEqual(['/api/agents/a-hr/pause'])
  })

  it('asks before pausing the General Employee, and says what it will cost', async () => {
    open([agent('a-general', 'General Employee', 'active', true)])

    fireEvent.click(screen.getByRole('button', { name: 'Pause General Employee' }))

    const dialog = await screen.findByRole('dialog', { name: 'Pause General Employee?' })
    expect(within(dialog).getByText(/will ask people to choose an agent/)).toBeInTheDocument()
    expect(posts()).toEqual([])

    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Pause General Employee' }))
    })
    expect(posts()).toEqual(['/api/agents/a-general/pause'])
  })

  it('offers no control to somebody who may not change agents, or for a retired agent', async () => {
    open([agent('a-hr', 'HR', 'active'), agent('a-old', 'Old agent', 'retired')], ['agent:read'])

    expect(screen.queryByRole('button', { name: /^(Pause|Resume) / })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Pause all' })).not.toBeInTheDocument()
  })

  it('offers no control for a retired agent to somebody who may change agents', async () => {
    open([agent('a-old', 'Old agent', 'retired')])

    expect(screen.queryByRole('button', { name: 'Pause Old agent' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Resume Old agent' })).not.toBeInTheDocument()
  })
})
