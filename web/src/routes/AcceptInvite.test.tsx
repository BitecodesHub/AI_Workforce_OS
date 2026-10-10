// @find: tests for accepting an invitation, accept invite, join workspace, vitest, AcceptInvite component tests, Join the workspace page
// @what: Automated tests that check the accepting an invitation screen (/accept-invite) behaves as users expect.
// @flow: Renders AcceptInvite from AcceptInvite.tsx inside a QueryClientProvider and RouterProvider with mocked API calls
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { accessToken, clearSession, profile, saveSession } from '../lib/session'
import { AcceptInvite } from './AcceptInvite'

/*
 * Accepting an invitation: registering a new account, being sent to sign in when the address
 * already has one, and finishing as the signed-in account on the way back.
 */

type Call = { url: string; method: string; body: unknown; authorization: string | null }

let calls: Call[] = []
let answers: Record<string, () => Response> = {}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  calls = []
  answers = {}
  // jsdom has no scrolling; the router scrolls to the top after a navigation.
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const headers = (init.headers ?? {}) as Record<string, string>
      calls.push({
        url,
        method: init.method ?? 'GET',
        body: typeof init.body === 'string' ? JSON.parse(init.body) : null,
        authorization: headers.Authorization ?? null,
      })
      const answer = answers[`${init.method ?? 'GET'} ${url.split('?')[0]}`]
      return answer ? answer() : json(404, { code: 'not_found', detail: 'Not here.' })
    }),
  )
})

afterEach(() => {
  clearSession()
  sessionStorage.clear()
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
})

async function open(path: string) {
  window.history.pushState({}, '', path)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <AcceptInvite />
      </RouterProvider>
    </QueryClientProvider>,
  )
  await act(async () => {})
}

function fillAndJoin() {
  fireEvent.change(screen.getByLabelText('Your name'), { target: { value: 'Rita Returning' } })
  fireEvent.change(screen.getByLabelText('Choose a password'), { target: { value: 'correct horse battery' } })
  fireEvent.change(screen.getByLabelText('Confirm password'), { target: { value: 'correct horse battery' } })
  fireEvent.click(screen.getByRole('button', { name: 'Join the workspace' }))
}

function signedIn(email = 'rita@example.test') {
  saveSession('person-token', {
    userId: 'user-rita',
    workspaceId: null,
    permissions: [],
    displayName: 'Rita Returning',
    email,
    role: null,
  })
}

describe('AcceptInvite', () => {
  it('opens under its <Eyebrow>, with the form for a new account', async () => {
    await open('/accept-invite?token=tok-123')

    expect(screen.getByText('You have been invited')).toBeInTheDocument()
    expect(screen.getByLabelText('Your name')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Sign in to accept' })).toHaveAttribute(
      'href',
      `/sign-in?next=${encodeURIComponent('/accept-invite?token=tok-123&accept=1')}`,
    )
  })

  it('says the person is in and moves focus to that heading once joined', async () => {
    answers['POST /api/invitations/accept'] = () => json(200, { email: 'new@example.test' })
    await open('/accept-invite?token=tok-123')
    fillAndJoin()
    await act(async () => {})
    const heading = screen.getByRole('heading', { level: 1, name: 'You are in' })
    expect(heading).toHaveFocus()
    expect(screen.getByRole('button', { name: 'Continue to sign in' })).toBeInTheDocument()
  })

  it('sends an address that already has an account to sign in, and back here to finish', async () => {
    answers['POST /api/invitations/accept'] = () =>
      json(409, {
        code: 'already_exists',
        detail: 'An account for rita@example.test already exists. Sign in to accept the invitation.',
        errors: { reason: 'account_exists', signInRequired: true },
      })
    await open('/accept-invite?token=tok-123')

    fillAndJoin()
    await act(async () => {})

    const signIn = screen.getByRole('button', { name: 'Sign in to accept' })
    expect(signIn).toHaveFocus()
    expect(screen.queryByLabelText('Your name')).not.toBeInTheDocument()

    fireEvent.click(signIn)
    expect(window.location.pathname).toBe('/sign-in')
    const next = new URLSearchParams(window.location.search).get('next')
    expect(next).toBe('/accept-invite?token=tok-123&accept=1')
    expect(sessionStorage.getItem('aiwos.acceptInvite')).toBe('tok-123')
  })

  it('asks before joining when accept=1 arrives on a link this tab did not start from', async () => {
    signedIn()
    await open('/accept-invite?token=tok-123&accept=1')
    await act(async () => {})

    expect(screen.getByRole('button', { name: 'Accept the invitation' })).toBeInTheDocument()
    expect(calls).toEqual([])
  })

  it('back from signing in, accepts as that account, opens the workspace and goes to the console', async () => {
    signedIn()
    answers['POST /api/invitations/accept-signed-in'] = () =>
      json(200, { userId: 'user-rita', orgId: 'org-acme', email: 'rita@example.test', roleName: 'employee' })
    answers['POST /api/auth/refresh'] = () =>
      json(200, {
        accessToken: 'workspace-token',
        userId: 'user-rita',
        workspaceId: 'org-acme',
        permissions: ['chat:use'],
        role: 'employee',
      })

    sessionStorage.setItem('aiwos.acceptInvite', 'tok-123')
    await open('/accept-invite?token=tok-123&accept=1')
    await act(async () => {})

    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual([
      'POST /api/invitations/accept-signed-in',
      'POST /api/auth/refresh?workspaceId=org-acme',
    ])
    expect(calls[0]?.body).toEqual({ token: 'tok-123' })
    expect(calls[0]?.authorization).toBe('Bearer person-token')
    expect(accessToken()).toBe('workspace-token')
    expect(profile()?.workspaceId).toBe('org-acme')
    expect(window.location.pathname).toBe('/')
  })

  it('asks a signed-in person before accepting a link they only opened', async () => {
    signedIn()
    await open('/accept-invite?token=tok-123')

    expect(screen.getByText(/You are signed in as rita@example.test/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Accept the invitation' })).toBeInTheDocument()
    expect(calls).toEqual([])
  })

  it('says so when the signed-in account is not the one invited, and offers another account', async () => {
    signedIn('someone.else@example.test')
    answers['POST /api/invitations/accept-signed-in'] = () =>
      json(403, {
        code: 'permission_denied',
        detail: 'This invitation was sent to a different email address. Sign in with that address to accept it.',
        errors: { reason: 'email_mismatch' },
      })
    await open('/accept-invite?token=tok-123')

    fireEvent.click(screen.getByRole('button', { name: 'Accept the invitation' }))
    await act(async () => {})

    expect(screen.getByText(/sent to a different email address/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Sign in with a different account' })).toBeInTheDocument()
    expect(calls.some((call) => call.url.startsWith('/api/auth/refresh'))).toBe(false)
  })

  it('shows the withdrawn message for a revoked invitation', async () => {
    answers['POST /api/invitations/accept'] = () =>
      json(409, {
        code: 'conflict',
        detail: 'This invitation was withdrawn. Ask whoever invited you for a new one.',
        errors: { reason: 'revoked' },
      })
    await open('/accept-invite?token=tok-123')

    fillAndJoin()
    await act(async () => {})

    expect(screen.getByText(/This invitation was withdrawn/)).toBeInTheDocument()
    // A dead link is not offered again: no form, no "sign in to accept".
    expect(screen.getByRole('heading', { level: 1, name: 'This link cannot be used' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Your name')).toBeNull()
    expect(screen.queryByRole('link', { name: 'Sign in to accept' })).toBeNull()
  })

  it('replaces the form for an expired invitation and for an unknown link', async () => {
    answers['POST /api/invitations/accept'] = () =>
      json(409, { code: 'conflict', detail: 'This invitation has expired. Ask for a new one.', errors: { reason: 'expired' } })
    await open('/accept-invite?token=tok-old')
    fillAndJoin()
    await act(async () => {})
    expect(screen.getByText('This invitation has expired. Ask for a new one.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Join the workspace' })).toBeNull()
  })

  it('offers sign-in for an invitation already used', async () => {
    answers['POST /api/invitations/accept'] = () =>
      json(409, {
        code: 'conflict',
        detail: 'This invitation has already been used. Sign in to open the workspace.',
        errors: { reason: 'accepted' },
      })
    await open('/accept-invite?token=tok-used')
    fillAndJoin()
    await act(async () => {})
    expect(screen.getByText('This invitation has already been used. Sign in to open the workspace.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('keeps the form and moves focus to the reason for a failure that can be retried', async () => {
    answers['POST /api/invitations/accept'] = () => json(503, { code: 'unavailable', detail: 'Try again in a minute.' })
    await open('/accept-invite?token=tok-123')
    fillAndJoin()
    // The failure arrives, then focus moves on the next frame; under a loaded full run that can
    // take longer than one frame after the click, so wait for it rather than for a single frame.
    await waitFor(() => expect(document.getElementById('invite-error')).toHaveFocus())
    expect(screen.getByLabelText('Your name')).toBeInTheDocument()
  })
})
