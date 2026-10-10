// @find: tests for creating a workspace, steps, time zone, sign up, vitest, CreateWorkspace component tests, Create a workspace page
// @what: Automated tests that check the creating a workspace screen (/create-workspace) behaves as users expect.
// @flow: Renders CreateWorkspace from CreateWorkspace.tsx inside a QueryClientProvider and RouterProvider with mocked API calls
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession, takeRecentRenewal } from '../lib/session'
import { browserTimeZone } from '../lib/settingsQueries'
import { CreateWorkspace } from './CreateWorkspace'

/*
 * Creating a workspace: the assistants are offered and added after the workspace and its session
 * exist, one that cannot be added never undoes the signup, somebody who is already signed in skips
 * registering (which the platform would refuse) and gets a workspace for their own account, and
 * the last card leads to Connect your AI.
 */

type Call = { url: string; method: string; body: unknown; authorization: string | null }

let calls: Call[]
let answers: Record<string, () => Response>

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const session = (workspaceId: string | null, extra: Record<string, unknown> = {}) => ({
  accessToken: workspaceId ? `owner-token-${workspaceId}` : 'bootstrap-token',
  userId: 'user-priya',
  workspaceId,
  permissions: workspaceId ? ['workspace:update', 'provider:manage'] : [],
  displayName: 'Priya Shah',
  email: 'priya@example.test',
  role: workspaceId ? 'owner' : null,
  ...extra,
})

beforeEach(() => {
  calls = []
  answers = {
    'POST /api/auth/register': () => json(201, {}),
    'POST /api/workspaces': () => json(201, { id: 'ws-new', name: 'Acme Operations' }),
    'POST /api/agents/from-template/hr': () => json(201, { agent: { id: 'a-hr', key: 'hr' }, suggestedConnectors: [] }),
    'POST /api/agents/from-template/engineering-manager': () =>
      json(201, { agent: { id: 'a-eng', key: 'engineering-manager' }, suggestedConnectors: [] }),
    'POST /api/agents/from-template/research': () =>
      json(201, { agent: { id: 'a-res', key: 'research' }, suggestedConnectors: [] }),
    'POST /api/agents/from-template/support': () =>
      json(201, { agent: { id: 'a-sup', key: 'support' }, suggestedConnectors: [] }),
  }
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const headers = (init.headers ?? {}) as Record<string, string>
      const method = init.method ?? 'GET'
      calls.push({
        url,
        method,
        body: typeof init.body === 'string' ? JSON.parse(init.body) : null,
        authorization: headers.Authorization ?? null,
      })
      const answer = answers[`${method} ${url.split('?')[0]}`]
      if (answer) return answer()
      // Signing in answers by whether a workspace is named.
      if (method === 'POST' && url === '/api/auth/sign-in') {
        const body = JSON.parse(String(init.body)) as { workspaceId?: string }
        return json(200, session(body.workspaceId ?? null))
      }
      return json(404, { code: 'not_found', detail: 'Not here.' })
    }),
  )
})

afterEach(() => {
  clearSession()
  takeRecentRenewal()
  sessionStorage.clear()
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
})

async function open(path = '/create-workspace') {
  window.history.pushState({}, '', path)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <CreateWorkspace />
      </RouterProvider>
    </QueryClientProvider>,
  )
  await act(async () => {})
}

const urls = () => calls.map((call) => `${call.method} ${call.url.split('?')[0]}`)
const templatePosts = () => calls.filter((call) => call.url.startsWith('/api/agents/from-template/'))

function next() {
  fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
}

function fillAccount() {
  fireEvent.change(screen.getByLabelText('Your name'), { target: { value: 'Priya Shah' } })
  fireEvent.change(screen.getByLabelText('Email address'), { target: { value: 'priya@example.test' } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'a long passphrase here' } })
  next()
}

/** From the workspace step to the assistants step, with the defaults. */
function toAssistants(name = 'Acme Operations') {
  fireEvent.change(screen.getByLabelText('Workspace name'), { target: { value: name } })
  next()
  next()
}

describe('the steps', () => {
  it('asks for a time zone, not working hours, from the browser’s own list, and shows no web address', async () => {
    await open()
    fillAccount()

    expect(screen.getByLabelText('Workspace name')).toHaveAttribute('placeholder', 'Acme Operations')
    expect(screen.queryByLabelText('Web address')).not.toBeInTheDocument()
    next()
    // Blank name is refused first.
    fireEvent.change(screen.getByLabelText('Workspace name'), { target: { value: 'Acme Operations' } })
    next()

    expect(screen.getByRole('heading', { name: 'Choose a time zone' })).toBeInTheDocument()
    expect(screen.getByLabelText('Time zone')).toHaveValue(browserTimeZone())
    expect(screen.getByText('Time zone', { selector: '.auth-step-label' })).toBeInTheDocument()
    expect(screen.queryByText(/Working hours/)).not.toBeInTheDocument()
    expect(document.querySelectorAll('datalist option').length).toBeGreaterThan(100)
  })

  it('refuses a time zone that is not in the list', async () => {
    await open()
    fillAccount()
    fireEvent.change(screen.getByLabelText('Workspace name'), { target: { value: 'Acme Operations' } })
    next()

    fireEvent.change(screen.getByLabelText('Time zone'), { target: { value: 'Nowhere/Land' } })
    next()

    expect(await screen.findByText('Choose a time zone from the list, such as Australia/Melbourne.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Choose a time zone' })).toBeInTheDocument()
  })

  it('offers all four assistants, ticked', async () => {
    await open()
    fillAccount()
    toAssistants()

    expect(screen.getByRole('heading', { name: 'Which assistants do you want?' })).toBeInTheDocument()
    for (const name of ['HR', 'Engineering Manager', 'Research', 'Customer Support']) {
      expect(screen.getByRole('checkbox', { name: new RegExp(`^${name}`) })).toBeChecked()
    }
  })
})

describe('creating the workspace', () => {
  it('adds the ticked assistants only after the workspace and its owner session exist, then offers Connect your AI', async () => {
    await open()
    fillAccount()
    toAssistants()
    fireEvent.click(screen.getByRole('checkbox', { name: /^Research/ }))

    fireEvent.click(screen.getByRole('button', { name: 'Create workspace' }))

    expect(await screen.findByRole('heading', { name: 'Your workspace is ready' })).toBeInTheDocument()
    expect(urls()).toEqual([
      'POST /api/auth/register',
      'POST /api/auth/sign-in',
      'POST /api/workspaces',
      'POST /api/auth/sign-in',
      'POST /api/agents/from-template/hr',
      'POST /api/agents/from-template/engineering-manager',
      'POST /api/agents/from-template/support',
    ])
    // The assistants are made as the workspace's owner, not on the bootstrap token.
    expect(templatePosts().every((call) => call.authorization === 'Bearer owner-token-ws-new')).toBe(true)

    expect(screen.getByRole('link', { name: 'Connect your AI' })).toHaveAttribute('href', '/routing?connect=1')
    expect(screen.getByRole('link', { name: 'Skip for now' })).toHaveAttribute('href', '/')
    expect(screen.getByText(/Added 3 assistants: HR, Engineering Manager and Customer Support/)).toBeInTheDocument()
    expect(
      screen.getByText(/connect Gmail, Google Calendar, GitHub, Jira, Slack and Google Drive on the Connectors page/),
    ).toBeInTheDocument()
  })

  it('adds no assistant when none is ticked, and still finishes', async () => {
    await open()
    fillAccount()
    toAssistants()
    for (const name of ['HR', 'Engineering Manager', 'Research', 'Customer Support']) {
      fireEvent.click(screen.getByRole('checkbox', { name: new RegExp(`^${name}`) }))
    }

    fireEvent.click(screen.getByRole('button', { name: 'Create workspace' }))

    expect(await screen.findByRole('heading', { name: 'Your workspace is ready' })).toBeInTheDocument()
    expect(templatePosts()).toHaveLength(0)
    expect(screen.queryByText(/Added/)).not.toBeInTheDocument()
  })

  it('never undoes the signup when an assistant cannot be added: the rest are still added, and the card says which', async () => {
    answers['POST /api/agents/from-template/engineering-manager'] = () =>
      json(500, { code: 'internal_error', detail: 'Something broke.' })
    await open()
    fillAccount()
    toAssistants()

    fireEvent.click(screen.getByRole('button', { name: 'Create workspace' }))

    expect(await screen.findByRole('heading', { name: 'Your workspace is ready' })).toBeInTheDocument()
    expect(templatePosts()).toHaveLength(4)
    expect(screen.getByText(/Added 3 assistants/)).toBeInTheDocument()
    expect(screen.getByText(/Engineering Manager could not be added just now/)).toBeInTheDocument()
    // Exactly one workspace, one register: nothing was retried or rolled back.
    expect(calls.filter((call) => call.url === '/api/workspaces')).toHaveLength(1)
    expect(calls.filter((call) => call.url === '/api/auth/register')).toHaveLength(1)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('does not add assistants, or move on, when the workspace itself cannot be created', async () => {
    answers['POST /api/workspaces'] = () => json(502, { code: 'upstream_error', detail: 'Nothing was created; try again.' })
    await open()
    fillAccount()
    toAssistants()

    fireEvent.click(screen.getByRole('button', { name: 'Create workspace' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Nothing was created; try again.')
    expect(templatePosts()).toHaveLength(0)
    expect(screen.getByRole('heading', { name: 'Which assistants do you want?' })).toBeInTheDocument()
  })
})

describe('signed in already', () => {
  it('skips the account step, and makes a workspace for the account without registering it again', async () => {
    // An account that has signed in and belongs to no workspace yet.
    saveSession('person-token', {
      userId: 'user-priya',
      workspaceId: null,
      permissions: [],
      displayName: 'Priya Shah',
      email: 'priya@example.test',
      role: null,
    })
    answers['POST /api/auth/refresh'] = () => json(200, session('ws-new'))
    await open('/create-workspace?signedIn=1')

    // Starts at the workspace step, with no password to type.
    expect(screen.getByRole('heading', { name: 'Name your workspace' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument()
    expect(screen.getByText(/You are signed in as priya@example.test/)).toBeInTheDocument()

    toAssistants()
    fireEvent.click(screen.getByRole('button', { name: 'Create workspace' }))

    expect(await screen.findByRole('heading', { name: 'Your workspace is ready' })).toBeInTheDocument()
    expect(urls()).toEqual([
      'POST /api/workspaces',
      // The session is re-issued for the new workspace: the same refresh the workspace picker uses.
      'POST /api/auth/refresh',
      'POST /api/agents/from-template/hr',
      'POST /api/agents/from-template/engineering-manager',
      'POST /api/agents/from-template/research',
      'POST /api/agents/from-template/support',
    ])
    expect(calls[0]?.authorization).toBe('Bearer person-token')
    expect(calls[0]?.body).toEqual({ name: 'Acme Operations', timezone: browserTimeZone() })
    expect(calls[1]?.url).toBe('/api/auth/refresh?workspaceId=ws-new')
    expect(urls()).not.toContain('POST /api/auth/register')
    expect(urls()).not.toContain('POST /api/auth/sign-in')
  })

  it('counts its steps without the account step', async () => {
    saveSession('person-token', { userId: 'u', workspaceId: null, permissions: [], displayName: 'P', email: 'p@e.test', role: null })
    await open('/create-workspace?signedIn=1')

    expect(screen.getByText(/step 1 of 4/)).toBeInTheDocument()
    expect(screen.queryByText('You', { selector: '.auth-step-label' })).not.toBeInTheDocument()
  })

  it('falls back to the ordinary flow when a link says signed in but there is no session to restore', async () => {
    answers['POST /api/auth/refresh'] = () => json(401, { code: 'token_invalid', detail: 'No session.' })
    await open('/create-workspace?signedIn=1')

    expect(await screen.findByRole('heading', { name: 'Create your account' })).toBeInTheDocument()
    expect(urls()).toContain('POST /api/auth/refresh')
  })

  it('offers a different account, which signs out and starts at the account step', async () => {
    saveSession('person-token', { userId: 'u', workspaceId: null, permissions: [], displayName: 'P', email: 'p@e.test', role: null })
    answers['POST /api/auth/sign-out'] = () => json(204, null)
    await open('/create-workspace?signedIn=1')

    fireEvent.click(screen.getByRole('button', { name: 'Use a different account' }))

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Create your account' })).toBeInTheDocument())
    expect(urls()).toContain('POST /api/auth/sign-out')
  })
})
