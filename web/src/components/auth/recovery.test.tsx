import axe from 'axe-core'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../../lib/router'
import { accessToken, clearSession, profile } from '../../lib/session'
import { SignIn } from '../../routes/SignIn'

/*
 * The ways back in and the step after signing in: a reset link, the honest sign-in page when
 * there are no demo accounts, and choosing a workspace when the account has several or none.
 */

type Route = (init: RequestInit | undefined, url: string) => Response | Promise<Response>

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

const SIGNED_IN = {
  accessToken: 'token-without-workspace',
  userId: 'user-1',
  permissions: [],
  displayName: 'Maya',
  email: 'maya@example.com',
}

let routes: Record<string, Route>
let calls: Array<{ url: string; init: RequestInit | undefined }>

function serve(extra: Record<string, Route>) {
  routes = { '/api/auth/demo-accounts': () => json({ accounts: [], password: null }), ...extra }
}

beforeEach(() => {
  calls = []
  serve({})
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      calls.push({ url, init })
      const route = routes[url]
      if (!route) return json({ detail: `no route for ${url}` }, 404)
      return route(init, url)
    }),
  )
  vi.stubGlobal('scrollTo', () => {})
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
})

async function renderAt(path: string) {
  window.history.pushState({}, '', path)
  const view = render(
    <RouterProvider>
      <SignIn />
    </RouterProvider>,
  )
  await act(async () => {})
  return view
}

async function submitSignIn() {
  fireEvent.change(screen.getByLabelText('Email address'), { target: { value: 'maya@example.com' } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'correct horse battery' } })
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
  })
}

describe('SignIn without demo accounts', () => {
  it('drops the demo sentence and says how to get back in', async () => {
    await renderAt('/sign-in')
    expect(screen.getByText('Use your workspace account.')).toBeInTheDocument()
    expect(screen.queryByText(/explore with a demo role/)).not.toBeInTheDocument()
    expect(screen.getByText('Forgot your password? Ask your workspace administrator for a reset link.')).toBeInTheDocument()
  })

  it('keeps the demo sentence where demo accounts exist', async () => {
    serve({
      '/api/auth/demo-accounts': () =>
        json({
          accounts: [{ email: 'm@demo.test', displayName: 'Maya Manager', role: 'manager', describes: 'Approves' }],
          password: 'demo',
        }),
    })
    await renderAt('/sign-in')
    expect(screen.getByText('Use your workspace account, or explore with a demo role below.')).toBeInTheDocument()
  })

  it('explains an unreachable service in plain words', async () => {
    serve({ '/api/auth/sign-in': () => Promise.reject(new TypeError('Failed to fetch')) })
    await renderAt('/sign-in')
    await submitSignIn()
    expect(screen.getByRole('alert')).toHaveTextContent(
      'We could not reach the service. Check your connection and try again. If it keeps happening, tell your administrator.',
    )
  })
})

describe('Reset link', () => {
  it('checks the new password here, then sets it and returns to sign in with a notice', async () => {
    serve({ '/api/auth/password-reset': () => new Response(null, { status: 204 }) })
    await renderAt('/sign-in?reset=raw-token')

    expect(screen.getByRole('heading', { level: 1, name: 'Choose a new password' })).toHaveFocus()
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'too short' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save the new password' }))
    })
    expect(screen.getByText('Use at least 12 characters.')).toBeInTheDocument()
    expect(calls.some((call) => call.url === '/api/auth/password-reset')).toBe(false)

    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'a long new passphrase' } })
    fireEvent.change(screen.getByLabelText('Type the new password again'), { target: { value: 'a long new passphrase' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save the new password' }))
    })

    const reset = calls.find((call) => call.url === '/api/auth/password-reset')
    expect(JSON.parse(String(reset?.init?.body))).toEqual({ token: 'raw-token', newPassword: 'a long new passphrase' })
    expect(window.location.search).toBe('?passwordReset=1')
    expect(screen.getByText('Your password has been changed. Sign in with the new one.')).toBeInTheDocument()
  })

  it('shows the service sentence when the link no longer works', async () => {
    const sentence = 'This reset link has expired or has already been used. Ask your workspace administrator for a new one.'
    serve({ '/api/auth/password-reset': () => json({ code: 'conflict', detail: sentence }, 409) })
    await renderAt('/sign-in?reset=used-token')
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'a long new passphrase' } })
    fireEvent.change(screen.getByLabelText('Type the new password again'), { target: { value: 'a long new passphrase' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save the new password' }))
    })
    expect(screen.getByRole('alert')).toHaveTextContent(sentence)
  })

  it('has no axe violations', async () => {
    const { container } = await renderAt('/sign-in?reset=raw-token')
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations.map((violation) => violation.id)).toEqual([])
  }, 30_000)
})

describe('Choosing a workspace after sign-in', () => {
  const entered = (orgId: string) => () =>
    json({ ...SIGNED_IN, accessToken: `token-for-${orgId}`, workspaceId: orgId, permissions: ['agent:read'], role: 'admin' })

  it('says so, and offers to create one, when the account has no workspace', async () => {
    serve({
      '/api/auth/sign-in': () => json(SIGNED_IN),
      '/api/users/me/workspaces': () => json([]),
    })
    await renderAt('/sign-in')
    await submitSignIn()

    expect(screen.getByRole('heading', { level: 1, name: 'You are not a member of a workspace yet' })).toHaveFocus()
    expect(screen.getByRole('link', { name: 'Create a workspace' })).toHaveAttribute('href', '/create-workspace?signedIn=1')
    expect(screen.getByText(/ask its administrator to invite you/)).toBeInTheDocument()
  })

  it('returns to the invitation, instead of the empty picker, when signing in to accept one', async () => {
    serve({
      '/api/auth/sign-in': () => json(SIGNED_IN),
      '/api/users/me/workspaces': () => json([]),
    })
    await renderAt('/sign-in?next=' + encodeURIComponent('/accept-invite?token=t1&accept=1'))
    await submitSignIn()

    await waitFor(() => expect(window.location.pathname).toBe('/accept-invite'))
    expect(window.location.search).toBe('?token=t1&accept=1')
    expect(screen.queryByRole('heading', { name: 'You are not a member of a workspace yet' })).toBeNull()
  })

  it('opens the only workspace straight away', async () => {
    serve({
      '/api/auth/sign-in': () => json(SIGNED_IN),
      '/api/users/me/workspaces': () => json([{ orgId: 'org-a', name: 'Acme', role: 'admin' }]),
      '/api/auth/refresh?workspaceId=org-a': entered('org-a'),
    })
    await renderAt('/sign-in?next=/approvals')
    await submitSignIn()

    await waitFor(() => expect(window.location.pathname).toBe('/approvals'))
    expect(accessToken()).toBe('token-for-org-a')
    expect(profile()?.workspaceId).toBe('org-a')
  })

  it('asks which one when there are several, then opens the choice', async () => {
    serve({
      '/api/auth/sign-in': () => json(SIGNED_IN),
      '/api/users/me/workspaces': () =>
        json([
          { orgId: 'org-a', name: 'Acme', role: 'admin' },
          { orgId: 'org-b', name: null, role: 'viewer' },
        ]),
      '/api/auth/refresh?workspaceId=org-b': entered('org-b'),
    })
    const { container } = await renderAt('/sign-in')
    await submitSignIn()

    expect(screen.getByRole('heading', { level: 1, name: 'Choose a workspace' })).toHaveFocus()
    expect(screen.getByRole('button', { name: 'Open Acme, as Admin' })).toBeInTheDocument()
    const unnamed = screen.getByRole('button', { name: /Open Unnamed workspace/ })
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations.map((violation) => violation.id)).toEqual([])

    await act(async () => {
      fireEvent.click(unnamed)
    })
    await waitFor(() => expect(window.location.pathname).toBe('/'))
    expect(accessToken()).toBe('token-for-org-b')
  }, 30_000)

  it('stays on the picker with the reason when a workspace cannot be opened', async () => {
    serve({
      '/api/auth/sign-in': () => json(SIGNED_IN),
      '/api/users/me/workspaces': () => json([{ orgId: 'org-a', name: 'Acme', role: 'admin' }]),
      '/api/auth/refresh?workspaceId=org-a': () =>
        json({ code: 'membership_inactive', detail: 'This account is no longer a member of the workspace.' }, 403),
    })
    await renderAt('/sign-in')
    await submitSignIn()

    expect(screen.getByRole('alert')).toHaveTextContent('This account is no longer a member of the workspace.')
    expect(screen.getByRole('button', { name: 'Open Acme, as Admin' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/sign-in')
  })
})
