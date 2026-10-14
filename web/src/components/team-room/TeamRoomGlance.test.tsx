// @find: tests for team room glance, command map team room, agents now, waiting for you, last three lines, team room menu item, run:read gating, TeamRoomGlance
// @what: Tests that the Command Map glance shows each agent's live state, the last three team lines and the ways on, polls only for roles with run:read, and that the menu leads to the team room.
// @flow: Renders TeamRoomGlance and Navbar with a saved session and fetch stubbed.
import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Navbar } from '../layout/Navbar'
import { RouterProvider } from '../../lib/router'
import { clearSession, saveSession } from '../../lib/session'
import { GLANCE_LINES, GLANCE_POLL_MS, TeamRoomGlance, glanceSummary } from './TeamRoomGlance'
import type { AgentPresence } from '../../lib/teamRoomQueries'

/*
 * The glance opens the Command Map: every agent with the state the team room shows, the last three
 * lines of the team conversation, Open team room and the page's own action. It reads every five
 * seconds, and nothing at all for a role that cannot read runs.
 */

let fetchMock: ReturnType<typeof vi.fn>
let presenceFails = false

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

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

function withProviders(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider>{node}</RouterProvider>
    </QueryClientProvider>,
  )
}

const presence = (id: string, name: string, state: string, extra: Partial<AgentPresence> = {}) => ({
  id,
  name,
  category: 'operations',
  description: null,
  fallback: false,
  agentStatus: 'active',
  state,
  since: null,
  runId: null,
  goalId: null,
  goalTitle: null,
  taskId: null,
  taskTitle: null,
  server: null,
  tool: null,
  site: null,
  approvalId: null,
  questionId: null,
  waitingText: null,
  waitingOn: null,
  waitingForYou: false,
  activeRuns: 0,
  queued: 0,
  ...extra,
})

const event = (id: string, kind: string, title: string, at: string) => ({
  id,
  cursor: `c-${id}`,
  kind,
  at,
  agentId: 'a1',
  agentName: 'Ada',
  agentCategory: 'operations',
  agentFallback: false,
  personId: 'user-1',
  goalId: 'g1',
  title,
  forYou: false,
})

const AGENTS = [
  presence('a1', 'Ada', 'thinking'),
  presence('a2', 'Ben', 'browsing', { site: 'en.wikipedia.org' }),
  presence('a3', 'Cy', 'waiting_approval', { waitingForYou: true, approvalId: 'ap1', waitingText: 'Send an email' }),
  presence('a4', 'Dee', 'idle'),
]

beforeEach(() => {
  presenceFails = false
  fetchMock = vi.fn((input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
    if (url === '/api/orchestrator/team-room') {
      if (presenceFails) return Promise.resolve(json({ message: 'down' }, 503))
      return Promise.resolve(json({ generatedAt: '2026-10-13T10:00:00Z', agents: AGENTS }))
    }
    if (url.startsWith('/api/orchestrator/team-room/feed')) {
      return Promise.resolve(
        json({
          generatedAt: '2026-10-13T10:00:00Z',
          events: [
            event('e1', 'goal_requested', 'Oldest request', '2026-10-13T09:00:00Z'),
            event('e2', 'task_started', 'Second', '2026-10-13T09:01:00Z'),
            event('e3', 'task_started', 'Third', '2026-10-13T09:02:00Z'),
            event('e4', 'goal_requested', 'Newest request', '2026-10-13T09:03:00Z'),
          ],
          newestCursor: 'c-e4',
          olderCursor: null,
          hasOlder: false,
          hasNewer: false,
        }),
      )
    }
    return Promise.resolve(json([]))
  })
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  clearSession()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('glanceSummary', () => {
  it('counts agents at work and only what waits on the viewer', () => {
    const agents = AGENTS as unknown as AgentPresence[]
    expect(glanceSummary(agents)).toBe('2 agents at work, 1 waiting for you.')
    expect(glanceSummary([agents[3]!])).toBe('Nobody is at work right now.')
    expect(glanceSummary([])).toBe('No agents yet.')
  })
})

describe('TeamRoomGlance', () => {
  it('shows every agent with its live state, the last three lines, and the ways on', async () => {
    signIn(['run:read', 'approval:read'])
    withProviders(<TeamRoomGlance eyebrow="Your workforce, live" action={<button type="button">Give an agent a task</button>} />)

    expect(await screen.findByText('2 agents at work, 1 waiting for you.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Team room' })).toBeInTheDocument()
    expect(screen.getByText('Your workforce, live')).toBeInTheDocument()

    const agents = within(screen.getByRole('list', { name: 'Your agents now' }))
    expect(agents.getAllByRole('listitem')).toHaveLength(4)
    expect(agents.getByText('Browsing en.wikipedia.org')).toBeInTheDocument()
    // What waits on the viewer leads straight to the decision; every other agent to the team room.
    expect(agents.getByRole('link', { name: /Cy/ })).toHaveAttribute('href', '/approvals#approval-ap1')
    expect(agents.getByRole('link', { name: /Ada/ })).toHaveAttribute('href', '/team-room')
    expect(agents.getAllByRole('listitem')[0]).toHaveAttribute('data-state', 'thinking')

    const latest = within(await screen.findByRole('list', { name: 'Latest in the team room' }))
    const lines = latest.getAllByRole('listitem')
    expect(lines).toHaveLength(GLANCE_LINES)
    expect(lines[0]).toHaveTextContent('Ada started on Second')
    expect(lines[2]).toHaveTextContent('You asked Ada: Newest request')
    expect(latest.queryByText(/Oldest request/)).not.toBeInTheDocument()

    expect(screen.getByRole('link', { name: 'Open team room' })).toHaveAttribute('href', '/team-room')
    expect(screen.getByRole('button', { name: 'Give an agent a task' })).toBeInTheDocument()
  })

  it('reads again every five seconds while it is open', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    signIn(['run:read'])
    withProviders(<TeamRoomGlance eyebrow="Your workforce, live" />)
    await screen.findByText('2 agents at work, 1 waiting for you.')
    const reads = () => fetchMock.mock.calls.filter(([input]) => input === '/api/orchestrator/team-room').length
    const before = reads()
    await vi.advanceTimersByTimeAsync(GLANCE_POLL_MS + 100)
    expect(reads()).toBe(before + 1)
  })

  it('says when the team cannot be read, and offers to try again', async () => {
    presenceFails = true
    signIn(['run:read'])
    withProviders(<TeamRoomGlance eyebrow="Your workforce, live" />)
    expect(await screen.findByText(/could not be read just now/)).toBeInTheDocument()
    presenceFails = false
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('2 agents at work, 1 waiting for you.')).toBeInTheDocument()
  })

  it('reads nothing for a role without run:read and keeps only the page action', () => {
    signIn(['task:create'])
    withProviders(<TeamRoomGlance eyebrow="Your workforce, live" action={<button type="button">Give an agent a task</button>} />)
    expect(screen.getByRole('button', { name: 'Give an agent a task' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Team room' })).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => String(input).startsWith('/api/orchestrator/team-room'))).toBe(false)
  })
})

describe('Team room in the menu', () => {
  it('is offered under Settings and more, with its note, to a role that can read runs', () => {
    signIn(['run:read'])
    withProviders(<Navbar currentPath="/team-room" />)
    fireEvent.click(screen.getByRole('button', { name: 'Settings and more' }))
    const panel = within(screen.getByRole('group', { name: 'Settings and more' }))
    const link = panel.getByRole('link', { name: /^Team room/ })
    expect(link).toHaveAttribute('href', '/team-room')
    expect(link).toHaveTextContent('Your AI team at work, live')
  })

  it('is not offered to a role that cannot read runs', () => {
    signIn(['task:read'])
    withProviders(<Navbar />)
    fireEvent.click(screen.getByRole('button', { name: 'Settings and more' }))
    const panel = within(screen.getByRole('group', { name: 'Settings and more' }))
    expect(panel.queryByRole('link', { name: /^Team room/ })).not.toBeInTheDocument()
  })
})
