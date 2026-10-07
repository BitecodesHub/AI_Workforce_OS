import { QueryClient } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, NETWORK_FAILURE, api, refreshAccessToken, restoreSession } from './api'
import {
  SESSION_EVENT,
  accessToken,
  clearSession,
  profile,
  saveSession,
  takeRecentRenewal,
  watchSessionOwner,
  type Profile,
  type SessionOwnerChange,
} from './session'

/*
 * Session continuity: a tab that opens without a token restores the session from the refresh
 * cookie, tabs never spend the same refresh cookie twice, a sign-out reaches every tab, and the
 * query cache never shows one person's data to the next.
 */

function person(userId: string, workspaceId: string | null = 'workspace-1'): Profile {
  return {
    userId,
    workspaceId,
    permissions: ['run:read'],
    displayName: 'Maya Manager',
    email: 'maya@demo.test',
    role: 'manager',
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const refreshed = {
  accessToken: 'fresh-token',
  userId: 'user-1',
  workspaceId: 'workspace-1',
  permissions: ['run:read', 'approval:read'],
  displayName: 'Maya Manager',
  email: 'maya@demo.test',
  role: 'manager',
}

/** Channel messages arrive asynchronously; this waits long enough for one posted just now. */
const delivered = () => new Promise((resolve) => setTimeout(resolve, 20))

let fetchMock: ReturnType<typeof vi.fn>
/** Another tab's end of the shared channel. */
let otherTab: BroadcastChannel
let heardByOtherTab: unknown[]

beforeEach(() => {
  sessionStorage.clear()
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
  heardByOtherTab = []
  otherTab = new BroadcastChannel('aiwos-session')
  otherTab.onmessage = (event: MessageEvent) => heardByOtherTab.push(event.data)
})

afterEach(() => {
  otherTab.close()
  takeRecentRenewal()
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

describe('restoring a session when a tab opens without a token', () => {
  it('restores it from the refresh cookie, stores it, and tells the shell', async () => {
    fetchMock.mockResolvedValue(json(refreshed))
    const announced = vi.fn()
    window.addEventListener(SESSION_EVENT, announced)

    await expect(restoreSession()).resolves.toBe('restored')

    window.removeEventListener(SESSION_EVENT, announced)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledWith('/api/auth/refresh', { method: 'POST', credentials: 'include' })
    expect(accessToken()).toBe('fresh-token')
    expect(profile()).toMatchObject({ userId: 'user-1', workspaceId: 'workspace-1', permissions: ['run:read', 'approval:read'] })
    expect(announced).toHaveBeenCalled()
  })

  it('reads a refused refresh as signed out and stores nothing', async () => {
    fetchMock.mockResolvedValue(json({ code: 'not_authenticated' }, 401))
    await expect(restoreSession()).resolves.toBe('signed-out')
    expect(accessToken()).toBeNull()
  })

  it('tells a network failure apart, so the person is offered a retry rather than the sign-in form', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    await expect(restoreSession()).resolves.toBe('unreachable')
    expect(accessToken()).toBeNull()
  })

  it('reads a 5xx from the identity service as unreachable, and signs no tab out', async () => {
    fetchMock.mockResolvedValue(json({ code: 'internal' }, 503))
    await expect(restoreSession()).resolves.toBe('unreachable')
    await delivered()
    expect(heardByOtherTab).not.toContainEqual({ type: 'signed-out' })
  })

  it('makes one request when the shell asks twice at once (as StrictMode does)', async () => {
    fetchMock.mockResolvedValue(json(refreshed))
    const [first, second] = await Promise.all([restoreSession(), restoreSession()])
    expect([first, second]).toEqual(['restored', 'restored'])
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})

describe('refreshing across tabs', () => {
  it('offers a renewal it obtained to the other tabs', async () => {
    fetchMock.mockResolvedValue(json(refreshed))
    await refreshAccessToken()
    await vi.waitFor(() =>
      expect(heardByOtherTab).toContainEqual(expect.objectContaining({ type: 'renewed', accessToken: 'fresh-token' })),
    )
  })

  it('adopts a renewal another tab broadcast moments ago instead of spending the cookie again', async () => {
    saveSession('expired-token', person('user-1'))
    const stop = watchSessionOwner(() => {})
    otherTab.postMessage({ type: 'renewed', accessToken: 'other-tab-token', profile: person('user-1') })
    await delivered()
    fetchMock.mockResolvedValue(json(refreshed))
    await expect(refreshAccessToken()).resolves.toBe(true)
    stop()
    expect(fetchMock).not.toHaveBeenCalled()
    expect(accessToken()).toBe('other-tab-token')
  })

  it('does not adopt a renewal shared before this tab saved a newer session of its own', async () => {
    saveSession('w1-token', person('user-1', 'workspace-1'))
    const stop = watchSessionOwner(() => {})
    otherTab.postMessage({ type: 'renewed', accessToken: 'other-tab-w1-token', profile: person('user-1', 'workspace-1') })
    await delivered()
    saveSession('w2-token', person('user-1', 'workspace-2'))
    fetchMock.mockResolvedValue(json({ ...refreshed, accessToken: 'fresh-w2-token', workspaceId: 'workspace-2' }))
    await expect(refreshAccessToken()).resolves.toBe(true)
    stop()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(accessToken()).toBe('fresh-w2-token')
    expect(profile()?.workspaceId).toBe('workspace-2')
  })

  it('runs the refresh under the lock every tab shares, where the browser has Web Locks', async () => {
    const request = vi.fn((_name: string, work: () => Promise<boolean>) => work())
    Object.defineProperty(navigator, 'locks', { configurable: true, value: { request } })
    try {
      fetchMock.mockResolvedValue(json(refreshed))
      await expect(refreshAccessToken()).resolves.toBe(true)
      expect(request).toHaveBeenCalledWith('aiwos-refresh', expect.any(Function))
      expect(fetchMock).toHaveBeenCalledTimes(1)
    } finally {
      Reflect.deleteProperty(navigator, 'locks')
    }
  })
})

describe('signing out', () => {
  it('tells every other tab', async () => {
    saveSession('token', person('user-1'))
    clearSession()
    expect(accessToken()).toBeNull()
    await vi.waitFor(() => expect(heardByOtherTab).toContainEqual({ type: 'signed-out' }))
  })

  it('signs this tab out when another tab signs out', async () => {
    saveSession('token', person('user-1'))
    const changes: SessionOwnerChange[] = []
    const stop = watchSessionOwner((change) => changes.push(change))
    otherTab.postMessage({ type: 'signed-out' })
    await vi.waitFor(() => expect(changes).toEqual(['signed-out-elsewhere']))
    stop()
    expect(accessToken()).toBeNull()
  })

  it('keeps the #fragment when an expired session sends the person to sign in', async () => {
    saveSession('expired-token', person('user-1'))
    const location = { pathname: '/approvals', search: '?status=pending', hash: '#approval-7', href: '' }
    vi.stubGlobal('location', location)
    fetchMock.mockImplementation((path: string) =>
      Promise.resolve(path === '/api/auth/refresh' ? json({ code: 'session_expired' }, 401) : json({}, 401)),
    )

    await expect(api('/api/approvals')).rejects.toBeInstanceOf(ApiError)

    expect(location.href).toBe(`/sign-in?next=${encodeURIComponent('/approvals?status=pending#approval-7')}&expired=1`)
    expect(accessToken()).toBeNull()
  })

  it('says plainly what to do when the service cannot be reached', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    const failure = await api('/api/agents').catch((error: unknown) => error)
    expect(failure).toBeInstanceOf(ApiError)
    expect((failure as ApiError).status).toBe(0)
    expect((failure as ApiError).message).toBe(NETWORK_FAILURE)
    expect(NETWORK_FAILURE).toBe(
      'We could not reach the service. Check your connection and try again. If it keeps happening, tell your administrator.',
    )
  })
})

describe('the query cache follows the person signed in', () => {
  function seededClient() {
    const client = new QueryClient()
    client.setQueryData(['conversations', 'list', 'mine'], [{ id: 'c-1', title: 'Quarterly numbers' }])
    client.setQueryData(['runs'], [{ id: 'run-1' }])
    return client
  }

  it('is emptied when somebody else signs in, and kept through a renewal for the same person', () => {
    saveSession('token-1', person('user-1'))
    const client = seededClient()
    const changes: SessionOwnerChange[] = []
    const stop = watchSessionOwner((change) => {
      changes.push(change)
      client.clear()
    })

    saveSession('token-2', person('user-1'))
    expect(client.getQueryData(['runs'])).toEqual([{ id: 'run-1' }])

    saveSession('token-3', person('user-2'))
    stop()
    expect(changes).toEqual(['switched'])
    expect(client.getQueryData(['conversations', 'list', 'mine'])).toBeUndefined()
    expect(client.getQueryCache().getAll()).toHaveLength(0)
  })

  it('is emptied when a sign-out here is followed by a sign-in as somebody else', () => {
    saveSession('token-1', person('user-1'))
    const client = seededClient()
    const stop = watchSessionOwner(() => client.clear())

    clearSession()
    saveSession('token-2', person('viewer-1'))
    stop()
    expect(client.getQueryCache().getAll()).toHaveLength(0)
  })

  it('is emptied when the same person signs into another workspace', () => {
    saveSession('token-1', person('user-1', 'workspace-1'))
    const client = seededClient()
    const stop = watchSessionOwner(() => client.clear())

    saveSession('token-2', person('user-1', 'workspace-2'))
    stop()
    expect(client.getQueryCache().getAll()).toHaveLength(0)
  })
})
