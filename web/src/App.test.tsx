// @find: tests for the app shell, private link without token, root address redirect, signed-in tab, App routing tests, vitest
// @what: Tests that App redirects signed-out visitors correctly and shows the right screen when signed in.
// @flow: Renders App from App.tsx with mocked sessions
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'
import { RouterProvider } from './lib/router'
import { accessToken, clearSession, saveSession, takeRecentRenewal } from './lib/session'

/*
 * The shell's session continuity: a tab opened at a link restores the session from the refresh
 * cookie instead of asking for the password, a network failure is offered a retry, a first-time
 * visitor at "/" is not kept waiting, a sign-out in another tab reaches this one, and a screen
 * that fails to draw leaves the navigation bar in place. The screens themselves are stand-ins.
 */

vi.mock('./components/layout/Navbar', () => ({
  Navbar: () => (
    <nav aria-label="Primary">
      <a href="/">Command Map</a>
    </nav>
  ),
}))
vi.mock('./routes/SignIn', () => ({ SignIn: () => <h1>Sign in form</h1> }))
vi.mock('./routes/Landing', () => ({ Landing: () => <h1>Public home page</h1> }))
vi.mock('./routes/CommandMap', () => ({ CommandMap: () => <h1>Command Map screen</h1> }))

const approvalsScreen = vi.hoisted(() => ({ fail: false }))
vi.mock('./routes/Approvals', () => ({
  Approvals: () => {
    if (approvalsScreen.fail) throw new Error('Cannot read properties of null')
    return <h1>Approvals queue</h1>
  },
}))

const session = {
  accessToken: 'restored-token',
  userId: 'user-1',
  workspaceId: 'workspace-1',
  permissions: ['approval:read'],
  displayName: 'Maya Manager',
  email: 'maya@demo.test',
  role: 'manager',
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

/** An in-memory localStorage; recent Node versions shadow jsdom's with one that does not work. */
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

/** The refresh request's answer, released by the test when it is ready. */
let refreshAnswer: (() => Promise<Response>) | null
let fetchMock: ReturnType<typeof vi.fn>

function renderApp(path: string, client = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  window.history.replaceState(null, '', path)
  render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <App />
      </RouterProvider>
    </QueryClientProvider>,
  )
  return client
}

beforeEach(() => {
  sessionStorage.clear()
  vi.stubGlobal('localStorage', memoryStorage())
  approvalsScreen.fail = false
  refreshAnswer = null
  fetchMock = vi.fn((path: string) => {
    if (path === '/api/auth/refresh') return refreshAnswer ? refreshAnswer() : Promise.resolve(json({}, 401))
    return Promise.resolve(json([]))
  })
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  sessionStorage.clear()
  takeRecentRenewal()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

const refreshCalls = () => fetchMock.mock.calls.filter(([path]) => path === '/api/auth/refresh').length

describe('opening a private link in a tab with no token', () => {
  it('restores the session from the refresh cookie and shows the screen, not the sign-in form', async () => {
    let release: (response: Response) => void = () => {}
    refreshAnswer = () => new Promise<Response>((resolve) => (release = resolve))
    renderApp('/approvals#approval-7')

    expect(screen.getByRole('status')).toHaveTextContent('Restoring your session')
    expect(screen.getByRole('main')).toHaveAttribute('aria-busy', 'true')

    await act(async () => release(json(session)))

    expect(await screen.findByRole('heading', { name: 'Approvals queue' })).toBeInTheDocument()
    expect(screen.queryByText('Sign in form')).not.toBeInTheDocument()
    expect(window.location.pathname).toBe('/approvals')
    expect(window.location.hash).toBe('#approval-7')
    expect(accessToken()).toBe('restored-token')
    expect(refreshCalls()).toBe(1)
  })

  it('sends the visitor to sign in, keeping the link, when the cookie is refused', async () => {
    renderApp('/approvals#approval-7')
    expect(await screen.findByText('Sign in form')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/sign-in')
    expect(new URLSearchParams(window.location.search).get('next')).toBe('/approvals#approval-7')
  })

  it('offers a retry when the service cannot be reached, and restores on the retry', async () => {
    refreshAnswer = () => Promise.reject(new TypeError('Failed to fetch'))
    renderApp('/approvals')

    expect(await screen.findByRole('heading', { name: 'Your session could not be restored' })).toBeInTheDocument()
    expect(screen.getByText(/We could not reach the service/)).toBeInTheDocument()
    expect(window.location.pathname).toBe('/approvals')

    refreshAnswer = () => Promise.resolve(json(session))
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByRole('heading', { name: 'Approvals queue' })).toBeInTheDocument()
    expect(refreshCalls()).toBe(2)
  })
})

describe('the root address with no token', () => {
  it('shows the home page at once to a browser that has never signed in', async () => {
    renderApp('/')
    expect(await screen.findByRole('heading', { name: 'Public home page' })).toBeInTheDocument()
    expect(refreshCalls()).toBe(0)
  })

  it('restores the session for a browser that signed in before, and shows the Command Map', async () => {
    localStorage.setItem('aiwos.signedIn', '1')
    refreshAnswer = () => Promise.resolve(json(session))
    renderApp('/')
    expect(await screen.findByRole('heading', { name: 'Command Map screen' })).toBeInTheDocument()
    expect(refreshCalls()).toBe(1)
  })

  it('shows the home page, and forgets the hint, when that browser’s cookie is refused', async () => {
    localStorage.setItem('aiwos.signedIn', '1')
    renderApp('/')
    expect(await screen.findByRole('heading', { name: 'Public home page' })).toBeInTheDocument()
    expect(localStorage.getItem('aiwos.signedIn')).toBeNull()
  })
})

describe('a signed-in tab', () => {
  function signIn() {
    saveSession('token', {
      userId: 'user-1',
      workspaceId: 'workspace-1',
      permissions: ['approval:read'],
      displayName: 'Maya Manager',
      email: 'maya@demo.test',
      role: 'manager',
    })
  }

  it('signs out, and empties its cache, when another tab signs out', async () => {
    signIn()
    const client = new QueryClient()
    client.setQueryData(['approvals'], [{ id: 'approval-7' }])
    renderApp('/approvals', client)
    expect(await screen.findByRole('heading', { name: 'Approvals queue' })).toBeInTheDocument()

    const otherTab = new BroadcastChannel('aiwos-session')
    otherTab.postMessage({ type: 'signed-out' })
    otherTab.close()

    expect(await screen.findByText('Sign in form')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/sign-in')
    expect(window.location.search).toBe('?signedOut=1')
    expect(accessToken()).toBeNull()
    expect(client.getQueryCache().getAll()).toHaveLength(0)
  })

  it('keeps the navigation bar when a screen fails to draw', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    approvalsScreen.fail = true
    signIn()
    renderApp('/approvals')
    expect(await screen.findByRole('heading', { name: 'This screen hit a problem.' })).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Primary' })).toBeInTheDocument()

    approvalsScreen.fail = false
    fireEvent.click(screen.getByRole('link', { name: 'Command Map' }))
    expect(await screen.findByRole('heading', { name: 'Command Map screen' })).toBeInTheDocument()
    clearSession()
  })
})
