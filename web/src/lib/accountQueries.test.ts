// @find: tests for account queries, describe device, enter workspace, sign out, sign out everywhere, refresh session
// @what: Tests for device descriptions, workspace entry and sign-out behaviour.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { describeDevice, enterWorkspace, signOut } from './accountQueries'
import { accessToken, clearSession, profile, saveSession } from './session'

/* The account helpers that run outside api(): devices in words, workspace entry and sign-out. */

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
})

function signedIn() {
  saveSession('old-token', {
    userId: 'user-1',
    workspaceId: null,
    permissions: [],
    displayName: 'Maya',
    email: 'maya@example.com',
    role: null,
  })
}

describe('describeDevice', () => {
  it('names the browser and the system in plain words', () => {
    expect(
      describeDevice('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/128.0 Safari/537.36 Edg/128.0'),
    ).toBe('Edge on Windows')
    expect(describeDevice('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Version/17.0 Mobile Safari/604.1')).toBe(
      'Safari on iOS',
    )
    expect(describeDevice('Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0')).toBe('Firefox on Linux')
    expect(describeDevice('curl/8.4.0')).toBe('Unknown device')
    expect(describeDevice(null)).toBe('Unknown device')
  })
})

describe('enterWorkspace', () => {
  it('refreshes for the chosen workspace and stores the session it returns', async () => {
    signedIn()
    const fetch = vi.fn(async () =>
      new Response(
        JSON.stringify({ accessToken: 'new-token', userId: 'user-1', workspaceId: 'org-a', permissions: ['agent:read'], role: 'admin' }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetch)

    await enterWorkspace('org-a')

    expect(fetch).toHaveBeenCalledWith('/api/auth/refresh?workspaceId=org-a', expect.objectContaining({ method: 'POST', credentials: 'include' }))
    expect(accessToken()).toBe('new-token')
    expect(profile()).toMatchObject({ workspaceId: 'org-a', role: 'admin', permissions: ['agent:read'] })
  })
})

describe('signOut', () => {
  it('ends every session on the server before clearing this tab', async () => {
    signedIn()
    const fetch = vi.fn(async () => new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetch)
    await signOut(true)
    expect(fetch).toHaveBeenCalledWith('/api/auth/sign-out?allDevices=true', expect.objectContaining({ method: 'POST' }))
    expect(accessToken()).toBeNull()
  })

  it('keeps this tab signed in when everywhere could not be reached, so the screen does not claim otherwise', async () => {
    signedIn()
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))))
    await expect(signOut(true)).rejects.toMatchObject({ status: 0 })
    expect(accessToken()).toBe('old-token')
  })
})
