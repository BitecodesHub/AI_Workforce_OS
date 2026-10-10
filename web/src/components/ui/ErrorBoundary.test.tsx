// @find: tests for error boundary, chunk load error, reload once, render error, error screen, ErrorBoundary
// @what: Tests the error screen, the single reload on a stale chunk and the way out.
// @flow: Covers ErrorBoundary.tsx.
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../lib/api'
import { RouterProvider, useRouter } from '../../lib/router'
import { clearSession, saveSession } from '../../lib/session'
import { ErrorBoundary, isChunkLoadError, logRenderError } from './ErrorBoundary'

/*
 * A screen that fails to draw shows what happened inside the shell, with a way to reload and a
 * way out, and the next screen starts afresh. A screen whose code is gone after a deploy reloads
 * the page once, and only once.
 */

function Broken({ error }: { error: unknown }): never {
  throw error
}

/** Stands in for the shell: a navigation bar that stays, and a main landmark keyed on the path. */
function Shell({ failWith = new Error('Cannot read properties of null') }: { failWith?: unknown }) {
  const { path } = useRouter()
  return (
    <div>
      <nav aria-label="Primary">
        <a href="/runs">Runs</a>
        <a href="/agents">Agents</a>
      </nav>
      <main key={path}>
        <ErrorBoundary>{path === '/runs' ? <Broken error={failWith} /> : <p>Every agent, at a glance.</p>}</ErrorBoundary>
      </main>
    </div>
  )
}

/** A boundary that stays mounted across routes, as the public pages' one does. */
function PublicPages() {
  const { path } = useRouter()
  return (
    <>
      <a href="/trust">Technical details</a>
      <ErrorBoundary variant="page">{path === '/home' ? <Broken error={new Error('No hero')} /> : <p>How it is built.</p>}</ErrorBoundary>
    </>
  )
}

function renderAt(path: string, ui: React.ReactElement) {
  window.history.replaceState(null, '', path)
  return render(<RouterProvider>{ui}</RouterProvider>)
}

const fallbackHeading = () => screen.queryByRole('heading', { level: 1, name: 'This screen hit a problem.' })

beforeEach(() => {
  sessionStorage.clear()
  // React reports every caught render error to the console; the tests below expect them.
  vi.spyOn(console, 'error').mockImplementation(() => {})
  saveSession('test-token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions: ['run:read'],
    displayName: 'Maya Manager',
    email: 'maya@demo.test',
    role: 'manager',
  })
})

afterEach(() => {
  clearSession()
  sessionStorage.clear()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('ErrorBoundary inside the shell', () => {
  it('shows the fallback in place of the screen and keeps the navigation bar', () => {
    renderAt('/runs', <Shell />)
    expect(fallbackHeading()).toBeInTheDocument()
    expect(screen.getByText('Something went wrong')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to Command Map' })).toHaveAttribute('href', '/')
    expect(screen.getByRole('navigation', { name: 'Primary' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Agents' })).toBeInTheDocument()
  })

  it('moves focus to the explanation, since the control that had it is gone', () => {
    renderAt('/runs', <Shell />)
    expect(fallbackHeading()).toHaveFocus()
  })

  it('starts afresh when the person moves to another screen', () => {
    renderAt('/runs', <Shell />)
    expect(fallbackHeading()).toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: 'Agents' }))
    expect(window.location.pathname).toBe('/agents')
    expect(fallbackHeading()).not.toBeInTheDocument()
    expect(screen.getByText('Every agent, at a glance.')).toBeInTheDocument()
  })

  it('quotes the request reference only for a server failure that carries one', () => {
    const { unmount } = renderAt('/runs', <Shell failWith={new ApiError(500, 'internal_error', 'Failed.', true, {}, 'req-42')} />)
    expect(screen.getByText('Reference: req-42')).toBeInTheDocument()
    unmount()

    renderAt('/runs', <Shell failWith={new ApiError(500, 'internal_error', 'Failed.', true, {})} />)
    expect(fallbackHeading()).toBeInTheDocument()
    expect(screen.queryByText(/Reference/)).not.toBeInTheDocument()
  })

  it('offers the home page rather than the Command Map to a signed-out visitor', () => {
    clearSession()
    renderAt('/home', <PublicPages />)
    expect(screen.getByRole('main')).toContainElement(fallbackHeading())
    expect(screen.getByRole('link', { name: 'Go to the home page' })).toHaveAttribute('href', '/home')
  })

  it('resets a boundary that stays mounted when the route changes', () => {
    clearSession()
    renderAt('/home', <PublicPages />)
    expect(fallbackHeading()).toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: 'Technical details' }))
    expect(fallbackHeading()).not.toBeInTheDocument()
    expect(screen.getByText('How it is built.')).toBeInTheDocument()
  })
})

describe('a screen whose code could not be loaded', () => {
  const staleChunk = () => new TypeError('Failed to fetch dynamically imported module: http://localhost/assets/Runs-1a2b3c.js')

  function stubLocation() {
    const reload = vi.fn()
    vi.stubGlobal('location', { pathname: '/runs', search: '', hash: '', href: 'http://localhost/runs', reload })
    return reload
  }

  it('reloads once, then explains that a newer version is available', () => {
    const reload = stubLocation()
    const first = renderAt('/runs', <Shell failWith={staleChunk()} />)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('heading', { level: 1, name: 'A newer version is available' })).toBeInTheDocument()
    first.unmount()

    // The reloaded page fails the same way: no second automatic reload, just the explanation.
    renderAt('/runs', <Shell failWith={staleChunk()} />)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('heading', { level: 1, name: 'A newer version is available' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Reload' }))
    expect(reload).toHaveBeenCalledTimes(2)
  })

  it('clears the reload flag once a page has run for a while without a chunk failing', async () => {
    vi.resetModules()
    const fresh = await import('./ErrorBoundary')
    vi.useFakeTimers()
    sessionStorage.setItem('aiwos.reloadedFor:unknown', '1')
    sessionStorage.setItem('aiwos.reloadedFor:/assets/index-old.js', '1')
    fresh.clearReloadFlagWhenSettled(10_000)
    vi.advanceTimersByTime(9_000)
    expect(sessionStorage.getItem('aiwos.reloadedFor:unknown')).toBe('1')
    vi.advanceTimersByTime(1_000)
    expect(sessionStorage.getItem('aiwos.reloadedFor:unknown')).toBeNull()
    expect(sessionStorage.getItem('aiwos.reloadedFor:/assets/index-old.js')).toBeNull()
  })

  it('keeps the flag on a page where a chunk failed, so it cannot reload in a loop', async () => {
    vi.resetModules()
    const fresh = await import('./ErrorBoundary')
    stubLocation()
    vi.useFakeTimers()
    expect(fresh.reloadOnce()).toBe(true)
    expect(fresh.reloadOnce()).toBe(false)
    fresh.clearReloadFlagWhenSettled(10_000)
    vi.advanceTimersByTime(10_000)
    expect(sessionStorage.getItem('aiwos.reloadedFor:unknown')).not.toBeNull()
  })

  it('recognises the ways browsers report a missing chunk, and nothing else', () => {
    expect(isChunkLoadError(staleChunk())).toBe(true)
    expect(isChunkLoadError(new TypeError('Importing a module script failed.'))).toBe(true)
    expect(isChunkLoadError(new TypeError('error loading dynamically imported module: /assets/Chat.js'))).toBe(true)
    expect(isChunkLoadError(Object.assign(new Error('Loading chunk 7 failed.'), { name: 'ChunkLoadError' }))).toBe(true)
    expect(isChunkLoadError(new Error('Cannot read properties of null'))).toBe(false)
    expect(isChunkLoadError('Failed to fetch dynamically imported module')).toBe(false)
  })
})

describe('logRenderError', () => {
  it('logs the path without its query or fragment, and an ApiError without its message', () => {
    window.history.replaceState(null, '', '/accept-invite?token=secret-invite#step-2')
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    logRenderError('caught', new ApiError(409, 'already_exists', 'ava@example.com is already a member.', false, {}, 'req-9'))
    const logged = JSON.stringify(log.mock.calls)
    expect(logged).toContain('/accept-invite')
    expect(logged).toContain('req-9')
    expect(logged).not.toContain('secret-invite')
    expect(logged).not.toContain('ava@example.com')
  })
})
