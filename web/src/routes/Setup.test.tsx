// @find: tests for the setup checklist, steps, free model, NVIDIA NIM, vitest, Setup component tests, Set up your workspace page
// @what: Automated tests that check the the setup checklist screen (/setup) behaves as users expect.
// @flow: Renders Setup from Setup.tsx inside a QueryClientProvider and RouterProvider with mocked API calls
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import { Setup } from './Setup'

/*
 * Set up your workspace: each step's status comes from the services, the first step needing
 * attention starts open, everything can be skipped, and an invitation is sent from the page.
 */

if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

type Call = { method: string; url: string; body: unknown }
let calls: Call[]

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const PERMISSIONS = [
  'workspace:update',
  'provider:read',
  'provider:manage',
  'knowledge:read',
  'knowledge:source_manage',
  'integration:read',
  'integration:connect',
  'member:read',
  'member:invite',
  'budget:read',
  'budget:manage',
]

beforeEach(() => {
  calls = []
  saveSession('token', {
    userId: 'user-1',
    workspaceId: 'ws-1',
    permissions: PERMISSIONS,
    displayName: 'Olivia Owner',
    email: 'olivia@example.test',
    role: 'owner',
  })
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      calls.push({ method, url, body: typeof init.body === 'string' ? JSON.parse(init.body) : null })
      const key = `${method} ${url}`
      switch (key) {
        case 'GET /api/providers':
          return json(200, [
            { id: 'nvidia', displayName: 'NVIDIA NIM', kind: 'OPENAI_COMPATIBLE', enabled: false, platformEnabled: true, credentialRef: 'provider:nvidia', credentialStatus: 'unknown', regions: [], modelCount: 2 },
            { id: 'sandbox', displayName: 'Offline sandbox', kind: 'SANDBOX', enabled: true, platformEnabled: true, credentialStatus: 'unknown', regions: [], modelCount: 1 },
          ])
        case 'GET /api/providers/models':
          return json(200, [])
        case 'GET /api/credentials':
          return json(200, [])
        case 'GET /api/model-policy':
          return json(200, { configured: false, exhaustedBehaviour: 'DEGRADE_TO_SANDBOX', maxAttemptsPerCandidate: 2, overallDeadlineSeconds: 300, compactOnOverflow: true, candidates: [{ position: 0, providerId: 'sandbox', modelId: 'sandbox-1' }] })
        case 'GET /api/knowledge/embedding':
          return json(200, { provider: 'sandbox', model: 'keyword', dimension: 0, searchMode: 'keyword', progress: { state: 'idle', sourcesTotal: 0, sourcesDone: 0, passagesTotal: 0, passagesDone: 0 } })
        case 'GET /api/integrations':
          return json(200, [
            { server: 'slack', displayName: 'Slack', status: 'disconnected', sandbox: true, reconnectRequired: false, grantedScopes: [], missingScopes: [], tools: [], liveAvailable: true, authType: 'token' },
          ])
        case 'GET /api/sources':
          return json(200, [])
        case 'GET /api/users':
          return json(200, [{ userId: 'user-1', displayName: 'Olivia Owner', email: 'olivia@example.test', role: 'owner', status: 'active' }])
        case 'GET /api/orgs/ws-1/invitations':
          return json(200, [])
        case 'GET /api/roles':
          return json(200, [
            { id: 'r1', name: 'owner', description: '', system: true, permissionVersion: 1, permissions: PERMISSIONS, holders: 1 },
            { id: 'r2', name: 'employee', description: '', system: true, permissionVersion: 1, permissions: ['member:read'], holders: 0 },
          ])
        case 'POST /api/orgs/ws-1/invitations':
          return json(201, { invitationId: 'i1', email: 'sam@example.test', roleName: 'employee', status: 'pending', expiresAt: '2026-11-01T00:00:00Z', acceptUrl: '/accept-invite?token=t' })
        case 'GET /api/orchestrator/notification-settings':
          return json(200, { webhookUrl: '', hasSecret: false, events: [], availableEvents: [] })
        case 'GET /api/orchestrator/budget':
          return json(200, { monthlyCap: null, perRunCap: null, perAgentDailyCap: null, onExhausted: 'stop', spentThisMonth: 0, remaining: null, projectedMonthEnd: null, periodStart: null })
        default:
          return json(404, { code: 'not_found', detail: `No fixture for ${key}` })
      }
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  clearSession()
})

function renderSetup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <Setup />
      </ToastProvider>
    </QueryClientProvider>,
  )
}

const stepNamed = (title: string) => screen.getByRole('heading', { name: title }).closest('li') as HTMLElement

describe('Set up your workspace', () => {
  it('works out each step from the services and recommends a free model to start', async () => {
    renderSetup()
    expect(await screen.findByText(/steps need attention/)).toBeInTheDocument()

    expect(within(stepNamed('Connect an AI model')).getByText('Needs attention')).toBeInTheDocument()
    expect(within(stepNamed('Connect an AI model')).getByRole('link', { name: 'get a free NVIDIA key' })).toHaveAttribute(
      'href',
      expect.stringContaining('build.nvidia.com'),
    )
    expect(within(stepNamed('Search documents by meaning')).getByText('Optional')).toBeInTheDocument()
    expect(within(stepNamed('Invite your team')).getByText('Needs attention')).toBeInTheDocument()
    expect(within(stepNamed('Set a budget')).getByText('Optional')).toBeInTheDocument()

    // Nothing about setup progress is kept in the browser.
    const stored: string[] = []
    for (let index = 0; index < (globalThis.localStorage?.length ?? 0); index++) stored.push(globalThis.localStorage.key(index) ?? '')
    expect(stored.filter((key) => key.includes('setup'))).toEqual([])
  })

  it('opens the Connect your AI dialog on NVIDIA NIM', async () => {
    renderSetup()
    await screen.findByText(/steps need attention/)
    fireEvent.click(within(stepNamed('Connect an AI model')).getByRole('button', { name: 'Connect a model' }))
    expect(await screen.findByRole('heading', { name: 'Connect a live AI model' })).toBeInTheDocument()
    expect(await screen.findByLabelText('Provider')).toHaveValue('nvidia')
  })

  it('lets a step be skipped for now without changing what is true', async () => {
    renderSetup()
    await screen.findByText(/steps need attention/)
    const tools = stepNamed('Connect your tools')
    fireEvent.click(within(tools).getByRole('button', { name: 'Skip for now: Connect your tools' }))
    expect(within(tools).getByText('Skipped for now')).toBeInTheDocument()
    expect(within(tools).getByRole('button', { name: 'Connect a tool: Connect your tools' })).toBeInTheDocument()
  })

  it('invites someone by email and role from the page', async () => {
    renderSetup()
    await screen.findByText(/steps need attention/)
    const team = stepNamed('Invite your team')
    fireEvent.click(within(team).getByRole('button', { name: 'Invite someone: Invite your team' }))
    const email = await within(team).findByLabelText('Email')
    await within(team).findByRole('option', { name: 'Employee' })

    fireEvent.change(email, { target: { value: 'not-an-email' } })
    fireEvent.click(within(team).getByRole('button', { name: 'Invite' }))
    expect(within(team).getByText(/Enter an email address/)).toBeInTheDocument()

    fireEvent.change(email, { target: { value: 'sam@example.test' } })
    await act(async () => {
      fireEvent.click(within(team).getByRole('button', { name: 'Invite' }))
    })
    expect(calls.find((call) => call.method === 'POST' && call.url === '/api/orgs/ws-1/invitations')?.body).toEqual({
      email: 'sam@example.test',
      roleName: 'employee',
    })
    expect(await within(team).findByText(/sam@example.test is invited as Employee/)).toBeInTheDocument()
  })
})
