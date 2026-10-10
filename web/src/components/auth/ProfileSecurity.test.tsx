// @find: tests for profile security, sessions list, revoke session, change password, sign out all devices, DELETE /api/users/me/sessions, POST /api/users/me/password, POST /api/auth/sign-out
// @what: Tests the Profile security panel: listing and revoking sessions, changing password, signing out everywhere.
import axe from 'axe-core'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../../lib/router'
import { accessToken, clearSession, saveSession } from '../../lib/session'
import { Profile } from '../../routes/Profile'

/*
 * The Security section of the profile: the password form, the signed-in devices, and signing out
 * everywhere.
 */

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

const SESSIONS = [
  {
    familyId: 'family-here',
    userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 Version/17.0 Safari/605.1.15',
    ipAddress: '203.0.113.9',
    issuedAt: '2026-10-03T09:00:00Z',
    signedInAt: '2026-10-01T09:00:00Z',
    current: true,
  },
  {
    familyId: 'family-phone',
    userAgent: 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128.0 Mobile Safari/537.36',
    issuedAt: '2026-10-02T09:00:00Z',
    current: false,
  },
]

// jsdom has the <dialog> element but not its modal methods; the confirmation dialog opens with one.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

let calls: Array<{ url: string; method: string; body: unknown }>
let respond: (url: string, method: string) => Response | Promise<Response>

beforeEach(() => {
  calls = []
  respond = (url, method) => {
    if (url === '/api/roles/permissions') return json([])
    if (url === '/api/users/me/sessions' && method === 'GET') return json(SESSIONS)
    return new Response(null, { status: 204 })
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = init?.method ?? 'GET'
      calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined })
      return respond(url, method)
    }),
  )
  vi.stubGlobal('scrollTo', () => {})
  saveSession('test-token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions: ['workspace:read'],
    displayName: 'Maya Manager',
    email: 'maya@example.com',
    role: 'manager',
  })
  window.history.pushState({}, '', '/profile')
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
})

async function renderProfile() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <Profile />
      </RouterProvider>
    </QueryClientProvider>,
  )
  await screen.findByText('Safari on macOS')
  return view
}

describe('Profile security', () => {
  it('lists each device, marks this one, and signs another out', async () => {
    await renderProfile()
    const devices = within(screen.getByRole('list', { name: 'Signed-in devices' })).getAllByRole('listitem')
    expect(devices).toHaveLength(2)
    expect(devices[0]).toHaveTextContent('203.0.113.9')
    expect(within(devices[0]!).getByText('This device')).toBeInTheDocument()
    expect(within(devices[0]!).queryByRole('button')).not.toBeInTheDocument()

    await act(async () => {
      fireEvent.click(within(devices[1]!).getByRole('button', { name: 'Sign out Chrome on Android' }))
    })
    expect(calls).toContainEqual({ url: '/api/users/me/sessions/family-phone', method: 'DELETE', body: undefined })
  })

  it('checks the new password here, then changes it', async () => {
    await renderProfile()
    fireEvent.change(screen.getByLabelText('Current password'), { target: { value: 'the old passphrase' } })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'a long new passphrase' } })
    fireEvent.change(screen.getByLabelText('Type the new password again'), { target: { value: 'something else entirely' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Change password' }))
    })
    expect(screen.getByText('The two new passwords do not match.')).toBeInTheDocument()
    expect(calls.some((call) => call.url === '/api/users/me/password')).toBe(false)

    fireEvent.change(screen.getByLabelText('Type the new password again'), { target: { value: 'a long new passphrase' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Change password' }))
    })
    expect(calls).toContainEqual({
      url: '/api/users/me/password',
      method: 'PUT',
      body: { currentPassword: 'the old passphrase', newPassword: 'a long new passphrase' },
    })
    expect(await screen.findByText('Your password has been changed. Every other device has been signed out.')).toBeInTheDocument()
  })

  it('puts a wrong current password under its field', async () => {
    respond = (url, method) => {
      if (url === '/api/users/me/password') {
        return json(
          { code: 'validation_failed', detail: 'Some of the values supplied are not valid.', errors: { field: 'currentPassword', problem: 'is not correct' } },
          422,
        )
      }
      if (url === '/api/users/me/sessions' && method === 'GET') return json(SESSIONS)
      return json([])
    }
    await renderProfile()
    fireEvent.change(screen.getByLabelText('Current password'), { target: { value: 'not my password' } })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'a long new passphrase' } })
    fireEvent.change(screen.getByLabelText('Type the new password again'), { target: { value: 'a long new passphrase' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Change password' }))
    })
    expect(await screen.findByText('Your current password is not correct.')).toBeInTheDocument()
  })

  it('signs out everywhere after confirming, then leaves for the sign-in page', async () => {
    await renderProfile()
    fireEvent.click(screen.getByRole('button', { name: 'Sign out everywhere' }))
    const dialog = await screen.findByRole('dialog')
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Sign out everywhere' }))
    })
    expect(calls).toContainEqual({ url: '/api/auth/sign-out?allDevices=true', method: 'POST', body: undefined })
    await waitFor(() => expect(window.location.pathname).toBe('/sign-in'))
    expect(accessToken()).toBeNull()
  })

  it('keeps the session, and says why, when signing out everywhere cannot reach the service', async () => {
    respond = (url, method) => {
      if (url === '/api/auth/sign-out?allDevices=true') return Promise.reject(new TypeError('Failed to fetch'))
      if (url === '/api/users/me/sessions' && method === 'GET') return json(SESSIONS)
      return json([])
    }
    await renderProfile()
    fireEvent.click(screen.getByRole('button', { name: 'Sign out everywhere' }))
    const dialog = await screen.findByRole('dialog')
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Sign out everywhere' }))
    })
    expect(await within(dialog).findByText(/We could not reach the service/)).toBeInTheDocument()
    expect(accessToken()).toBe('test-token')
  })

  it('has no axe violations', async () => {
    const { container } = await renderProfile()
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations.map((violation) => violation.id)).toEqual([])
  }, 30_000)
})
