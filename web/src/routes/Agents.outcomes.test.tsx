// @find: tests for agent outcome figures, success rate, 30-day cost, vitest, Agents component tests, Agents page
// @what: Automated tests that check the agent outcome figures screen (/agents) behaves as users expect.
// @flow: Renders Agents from Agents.tsx inside a QueryClientProvider and RouterProvider with mocked API calls
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import { Agents } from './Agents'

/*
 * The agent cards: two outcome figures on each so a manager can compare agents at a glance (success
 * rate and 30-day cost, "Not enough runs yet" below five finished runs), and Pause or Resume on the
 * card for people who may change agents, outside the card's own link.
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

type Call = { method: string; url: string }

const agent = (id: string, name: string, status = 'active', fallback = false) => ({
  id,
  key: id,
  name,
  category: 'operations',
  status,
  revision: 1,
  summary: `You are ${name}.`,
  tools: [],
  fallback,
})

let calls: Call[]
let agents: Array<Record<string, unknown>>
let permissions: string[]
let outcomes: Array<Record<string, unknown>> | null

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  calls = []
  agents = [agent('a-general', 'General Employee', 'active', true), agent('a-legal', 'Legal'), agent('a-old', 'Old agent', 'retired')]
  permissions = ['agent:read']
  outcomes = [
    { agentId: 'a-general', runs: 40, finishedRuns: 40, completed: 38, failed: 2, enoughRuns: true, successRate: 0.95, totalCost: 12.4 },
    { agentId: 'a-legal', runs: 2, finishedRuns: 2, completed: 2, failed: 0, enoughRuns: false, totalCost: 0.03 },
  ]
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      calls.push({ method, url })
      if (method === 'GET' && url === '/api/agents') return json(200, agents)
      if (method === 'GET' && url === '/api/orchestrator/insights/agents?window=30d') {
        return outcomes ? json(200, { window: '30d', agents: outcomes }) : json(500, { code: 'internal', detail: 'Down.' })
      }
      if (method === 'POST' && /^\/api\/agents\/[^/]+\/(pause|resume)$/.test(url)) {
        return json(200, { ...agent('a-legal', 'Legal'), status: url.endsWith('pause') ? 'paused' : 'active' })
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

async function open() {
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
      <RouterProvider>
        <ToastProvider>
          <Agents />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
  await screen.findByRole('heading', { name: 'Legal' })
}

const cardOf = (name: string) => screen.getByRole('heading', { name }).closest('a')!

describe('outcome figures', () => {
  it('show each agent\'s success rate and 30-day cost on its card', async () => {
    permissions = ['agent:read', 'run:read']
    await open()

    const general = cardOf('General Employee')
    expect(await within(general).findByText('95%')).toBeInTheDocument()
    expect(within(general).getByText('Success rate (30 days)')).toBeInTheDocument()
    expect(within(general).getByText('US$12.40')).toBeInTheDocument()
    expect(within(general).getByText('Cost (30 days)')).toBeInTheDocument()
  })

  it('say there are not enough runs yet for an agent with fewer than five finished runs', async () => {
    permissions = ['agent:read', 'run:read']
    await open()

    const legal = cardOf('Legal')
    expect(await within(legal).findByText('Not enough runs yet')).toBeInTheDocument()
    // A retired agent is folded away until its section is opened.
    expect(screen.queryByRole('heading', { name: 'Old agent' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Retired \(1\)/ }))
    // An agent with no run at all is the same, with a real zero for its cost.
    const old = cardOf('Old agent')
    expect(within(old).getByText('Not enough runs yet')).toBeInTheDocument()
    expect(within(old).getByText('US$0.00')).toBeInTheDocument()
  })

  it('are left out without run:read, and not asked for', async () => {
    await open()

    expect(screen.queryByText('Success rate (30 days)')).not.toBeInTheDocument()
    expect(calls.some((call) => call.url.startsWith('/api/orchestrator/insights'))).toBe(false)
  })

  it('are left out when they cannot be loaded, rather than shown as zeros', async () => {
    permissions = ['agent:read', 'run:read']
    outcomes = null
    await open()

    await vi.waitFor(() => expect(calls.some((call) => call.url.startsWith('/api/orchestrator/insights'))).toBe(true))
    await vi.waitFor(() => expect(screen.queryByText('Success rate (30 days)')).not.toBeInTheDocument())
    expect(screen.queryByText('US$0.00')).not.toBeInTheDocument()
  })
})

describe('pause and resume on the cards', () => {
  it('sit beside each active or paused agent\'s link, not inside it, for people who may change agents', async () => {
    permissions = ['agent:read', 'agent:update']
    agents = [agent('a-general', 'General Employee', 'active', true), agent('a-legal', 'Legal', 'paused'), agent('a-old', 'Old agent', 'retired')]
    await open()

    const pause = screen.getByRole('button', { name: 'Pause General Employee' })
    const resume = screen.getByRole('button', { name: 'Resume Legal' })
    expect(pause.closest('a')).toBeNull()
    expect(resume.closest('a')).toBeNull()
    // A retired agent has neither.
    expect(screen.queryByRole('button', { name: /Old agent/ })).not.toBeInTheDocument()
  })

  it('resume a paused agent without opening it', async () => {
    permissions = ['agent:read', 'agent:update']
    agents = [agent('a-legal', 'Legal', 'paused')]
    await open()

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Resume Legal' }))
    })

    expect(calls.filter((call) => call.method === 'POST').map((call) => call.url)).toEqual(['/api/agents/a-legal/resume'])
    expect(window.location.pathname).toBe('/')
  })

  it('ask before pausing the General Employee', async () => {
    permissions = ['agent:read', 'agent:update']
    await open()

    fireEvent.click(screen.getByRole('button', { name: 'Pause General Employee' }))

    const dialog = await screen.findByRole('dialog', { name: 'Pause General Employee?' })
    expect(within(dialog).getByText(/will ask people to choose an agent/)).toBeInTheDocument()
    expect(calls.filter((call) => call.method === 'POST')).toEqual([])
  })

  it('are not offered to somebody who cannot change agents', async () => {
    await open()

    expect(screen.queryByRole('button', { name: /^(Pause|Resume) / })).not.toBeInTheDocument()
  })
})
