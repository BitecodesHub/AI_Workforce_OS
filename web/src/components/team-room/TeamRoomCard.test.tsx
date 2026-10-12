// @find: tests for team room card, command map link card, team room menu item, Settings and more, run:read gating, TeamRoomCard
// @what: Tests that the Command Map card and the "Settings and more" menu lead to the team room, only for roles with run:read.
// @flow: Renders TeamRoomCard and Navbar with a saved session and fetch stubbed.
import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Navbar } from '../layout/Navbar'
import { RouterProvider } from '../../lib/router'
import { clearSession, saveSession } from '../../lib/session'
import { TeamRoomCard } from './TeamRoomCard'

/*
 * The team room is reached from the gear menu ("Team room", "Your AI team at work, live") and from
 * a card on the Command Map, and from neither for a role that cannot read runs. The card reads the
 * presence once, never on a timer, to say how many agents are at work.
 */

let fetchMock: ReturnType<typeof vi.fn>

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
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

const presence = (id: string, name: string, state: string) => ({
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
})

beforeEach(() => {
  fetchMock = vi.fn((input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
    if (url === '/api/orchestrator/team-room') {
      return Promise.resolve(
        json({
          generatedAt: '2026-10-12T10:00:00Z',
          agents: [
            presence('a1', 'Ada', 'thinking'),
            presence('a2', 'Ben', 'browsing'),
            presence('a3', 'Cy', 'waiting_approval'),
            presence('a4', 'Dee', 'idle'),
          ],
        }),
      )
    }
    return Promise.resolve(json([]))
  })
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
})

describe('TeamRoomCard', () => {
  it('links to the team room and says how many agents are at work and waiting', async () => {
    signIn(['run:read'])
    withProviders(<TeamRoomCard />)
    const link = await screen.findByRole('link', {
      name: 'Team room. 2 agents at work now, 1 waiting on a person.',
    })
    expect(link).toHaveAttribute('href', '/team-room')
    const calls = fetchMock.mock.calls.filter(([input]) => input === '/api/orchestrator/team-room')
    expect(calls).toHaveLength(1)
  })

  it('is not shown, and reads nothing, for a role without run:read', () => {
    signIn(['chat:use'])
    const { container } = withProviders(<TeamRoomCard />)
    expect(container).toBeEmptyDOMElement()
    expect(fetchMock.mock.calls.some(([input]) => input === '/api/orchestrator/team-room')).toBe(false)
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
