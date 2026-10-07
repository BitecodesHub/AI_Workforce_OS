import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'
import { RouterProvider } from './lib/router'
import { clearSession, saveSession, takeRecentRenewal } from './lib/session'

/*
 * What is waiting on a person shows in the tab title on every signed-in screen, and the workspace
 * settings screen is for those who may change the workspace. The screens themselves are stand-ins.
 */

vi.mock('./components/layout/Navbar', () => ({ Navbar: () => <nav aria-label="Primary" /> }))
vi.mock('./routes/Agents', () => ({ Agents: () => <h1>Agents screen</h1> }))
vi.mock('./routes/Profile', () => ({ Profile: () => <h1>Profile screen</h1> }))
vi.mock('./routes/Settings', () => ({ Settings: () => <h1>Settings screen</h1> }))
vi.mock('./routes/Landing', () => ({ Landing: () => <h1>Public home page</h1> }))

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

let approvals: Array<{ id: string; summary: string; canDecide: boolean }>

function signIn(permissions: string[]) {
  saveSession('token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions,
    displayName: 'Maya Manager',
    email: 'maya@demo.test',
    role: 'manager',
  })
}

function renderApp(path: string) {
  window.history.replaceState(null, '', path)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <App />
      </RouterProvider>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  approvals = []
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => (url.startsWith('/api/approvals') ? json(approvals) : json([]))),
  )
})

afterEach(() => {
  clearSession()
  takeRecentRenewal()
  sessionStorage.clear()
  vi.unstubAllGlobals()
  window.history.replaceState(null, '', '/')
})

describe('the waiting count in the tab title', () => {
  it('shows (1) on Agents while one approval waits for this person', async () => {
    approvals = [
      { id: 'a1', summary: 'Send an email', canDecide: true },
      // Somebody else's to decide: not counted.
      { id: 'a2', summary: 'Delete a file', canDecide: false },
    ]
    signIn(['agent:read', 'approval:read'])
    renderApp('/agents')

    expect(await screen.findByRole('heading', { name: 'Agents screen' })).toBeInTheDocument()
    await waitFor(() => expect(document.title).toBe('(1) Agents · AI Workforce OS'))
  })

  it('shows on every other signed-in screen too, and goes when nothing waits', async () => {
    approvals = [{ id: 'a1', summary: 'Send an email', canDecide: true }]
    signIn(['approval:read'])
    renderApp('/profile')

    await waitFor(() => expect(document.title).toBe('(1) Your profile · AI Workforce OS'))
  })

  it('shows no count for a role that cannot read approvals, and asks for none', async () => {
    approvals = [{ id: 'a1', summary: 'Send an email', canDecide: true }]
    signIn(['agent:read'])
    renderApp('/agents')

    await screen.findByRole('heading', { name: 'Agents screen' })
    await act(async () => {})
    expect(document.title).toBe('Agents · AI Workforce OS')
    expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).startsWith('/api/approvals'))).toBe(false)
  })
})

describe('Workspace settings', () => {
  it('opens for somebody who may update the workspace, under its own title', async () => {
    signIn(['workspace:update'])
    renderApp('/settings')

    expect(await screen.findByRole('heading', { name: 'Settings screen' })).toBeInTheDocument()
    expect(document.title).toBe('Workspace settings · AI Workforce OS')
  })

  it('says it is not in the role of somebody who may not, and names the permission', async () => {
    signIn(['agent:read'])
    renderApp('/settings')

    expect(await screen.findByRole('heading', { name: 'Workspace settings' })).toBeInTheDocument()
    expect(screen.queryByText('Settings screen')).not.toBeInTheDocument()
    expect(screen.getByText(/Not in your role/)).toBeInTheDocument()
  })
})
