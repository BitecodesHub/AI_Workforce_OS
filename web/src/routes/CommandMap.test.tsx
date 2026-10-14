// @find: tests for command map, first screen, no title block, no setup banner, no model line, no getting started, team room glance first, give an agent a task, CommandMap
// @what: Tests that the Command Map opens on the team room glance with a hidden page heading, keeps Give an agent a task, and no longer shows the title block, setup banner, model line or getting-started tour.
// @flow: Renders CommandMap with a saved session and fetch stubbed.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { CommandMap } from './CommandMap'

let fetchMock: ReturnType<typeof vi.fn>

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function signIn(permissions: string[]) {
  saveSession('test-token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions,
    displayName: 'Olive Owner',
    email: 'olive@demo.test',
    role: 'owner',
  })
}

function renderMap() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <CommandMap />
      </RouterProvider>
    </QueryClientProvider>,
  )
}

const OWNER = [
  'run:read',
  'task:create',
  'approval:read',
  'provider:read',
  'provider:manage',
  'integration:read',
  'chat:use',
  'analytics:read',
  'org:manage',
]

beforeEach(() => {
  fetchMock = vi.fn((input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
    if (url === '/api/orchestrator/team-room') return Promise.resolve(json({ generatedAt: '2026-10-13T10:00:00Z', agents: [] }))
    if (url.startsWith('/api/orchestrator/team-room/feed')) {
      return Promise.resolve(
        json({ generatedAt: '2026-10-13T10:00:00Z', events: [], newestCursor: null, olderCursor: null, hasOlder: false, hasNewer: false }),
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

describe('the Command Map', () => {
  it('opens on the team room glance, with a hidden page heading and the task button', async () => {
    signIn(OWNER)
    renderMap()
    const h1 = screen.getByRole('heading', { level: 1, name: 'Command Map' })
    expect(h1).toHaveClass('visually-hidden')
    expect(await screen.findByRole('heading', { level: 2, name: 'Team room' })).toBeInTheDocument()
    expect(screen.getByText('Your workforce, live')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Give an agent a task' })).toBeInTheDocument()
  })

  it('no longer shows the title block, setup banner, model line or getting-started tour', async () => {
    signIn(OWNER)
    renderMap()
    await screen.findByRole('heading', { level: 2, name: 'Team room' })
    for (const gone of [
      'Your workforce, in focus',
      'What your agents have been doing recently, and anything waiting on a decision.',
      /Finish setting up your workspace/,
      /Agents answer with/,
      /Getting started/i,
      'Meet an agent and read what it does',
    ]) {
      expect(screen.queryByText(gone)).not.toBeInTheDocument()
    }
    // Nor does it read what only those parts needed.
    const read = fetchMock.mock.calls.map(([input]) => String(input).split('?')[0] ?? '')
    for (const path of ['/api/model-policy', '/api/providers', '/api/credentials', '/api/setup']) {
      expect(read.some((url) => url.startsWith(path))).toBe(false)
    }
  })
})
