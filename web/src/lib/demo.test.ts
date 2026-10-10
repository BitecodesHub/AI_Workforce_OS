// @find: tests for demo accounts, parseDemoAccounts, hasDemoAccounts
// @what: Unit tests for demo account detection.
import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  DEMO_ACCOUNTS_URL,
  hasDemoAccounts,
  parseDemoAccounts,
  resetDemoAccountsForTests,
  useDemoAccounts,
} from './demo'

/*
 * Whether this site offers demo accounts: asked once, shared by every caller, and "none" for
 * every answer that is not a list of accounts, so the public pages fall back to the demos on the
 * page instead of sending a visitor to a sign-in form with nothing to sign in with.
 */

const MANAGER = {
  email: 'manager@demo.aiworkforce.os',
  displayName: 'Maya Manager',
  role: 'manager',
  describes: 'Runs agents and approves actions.',
}

function respond(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  resetDemoAccountsForTests()
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('parseDemoAccounts', () => {
  it('keeps the accounts and the published password', () => {
    expect(parseDemoAccounts({ accounts: [MANAGER], password: 'demo-workspace-2026' })).toEqual({
      status: 'ready',
      accounts: [MANAGER],
      password: 'demo-workspace-2026',
    })
  })

  it('reads an empty list, a deployed site\'s answer, as none', () => {
    expect(parseDemoAccounts({ accounts: [], password: null })).toEqual({ status: 'ready', accounts: [], password: null })
  })

  it('reads anything malformed as none rather than failing', () => {
    for (const body of [null, 'nope', 42, { accounts: 'x' }, { accounts: [{ email: 1 }] }]) {
      expect(hasDemoAccounts(parseDemoAccounts(body))).toBe(false)
    }
  })
})

describe('useDemoAccounts', () => {
  it('starts as loading with no accounts, then reports what the site offers', async () => {
    const fetchMock = vi.fn().mockResolvedValue(respond(200, { accounts: [MANAGER], password: 'demo-workspace-2026' }))
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useDemoAccounts())
    expect(result.current.status).toBe('loading')
    expect(hasDemoAccounts(result.current)).toBe(false)

    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(hasDemoAccounts(result.current)).toBe(true)
    expect(result.current.accounts.map((account) => account.role)).toEqual(['manager'])
    expect(fetchMock).toHaveBeenCalledWith(DEMO_ACCOUNTS_URL, expect.anything())
  })

  it('counts a 404, the endpoint switched off, as no demo accounts', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(404)))
    const { result } = renderHook(() => useDemoAccounts())
    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(result.current.accounts).toEqual([])
  })

  it('counts a request that never arrives as no demo accounts', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const { result } = renderHook(() => useDemoAccounts())
    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(hasDemoAccounts(result.current)).toBe(false)
  })

  it('asks once, however many components want to know', async () => {
    const fetchMock = vi.fn().mockResolvedValue(respond(200, { accounts: [MANAGER], password: 'x' }))
    vi.stubGlobal('fetch', fetchMock)

    const first = renderHook(() => useDemoAccounts())
    const second = renderHook(() => useDemoAccounts())
    await waitFor(() => expect(second.result.current.status).toBe('ready'))
    expect(first.result.current.status).toBe('ready')

    // A component mounted later reads the remembered answer straight away.
    const late = renderHook(() => useDemoAccounts())
    expect(late.result.current.status).toBe('ready')
    await act(async () => {})
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
