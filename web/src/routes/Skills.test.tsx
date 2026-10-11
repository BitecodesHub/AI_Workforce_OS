// @find: tests for the Skills page, list skills, create skill, edit skill, duplicate skill, delete skill, read-only skills, search skills, /api/skills
// @what: Tests that the Skills page lists skills, saves them with the right body, and offers changes only to a role that may make them.
// @flow: Renders routes/Skills with a mocked fetch inside the query client, router and toast providers.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import type { Skill } from '../lib/skillQueries'
import { ToastProvider } from '../lib/toast'
import { Skills } from './Skills'

// jsdom has the <dialog> element but not its modal methods.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

function skill(overrides: Partial<Skill> & Pick<Skill, 'id' | 'name'>): Skill {
  return {
    description: '',
    instructions: 'Do the thing.',
    defaultAgentId: null,
    defaultAgentName: null,
    requiredConnectors: [],
    builtIn: false,
    createdBy: 'user-1',
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  }
}

const REPLY = skill({
  id: 'sk-reply',
  name: 'Draft a customer reply',
  description: 'A friendly answer to a support email',
  defaultAgentId: 'a-support',
  defaultAgentName: 'Support',
  requiredConnectors: ['gmail'],
  builtIn: true,
})
const STATUS = skill({ id: 'sk-status', name: 'Weekly status', description: 'Summarise the week from GitHub', requiredConnectors: ['github'] })

const AGENTS = [
  { id: 'a-support', key: 'support', name: 'Support', category: 'support', status: 'active', revision: 1, tools: [] },
  { id: 'a-eng', key: 'eng', name: 'Engineer', category: 'engineering', status: 'active', revision: 1, tools: [] },
  { id: 'a-old', key: 'old', name: 'Retired one', category: 'operations', status: 'retired', revision: 1, tools: [] },
]
const INTEGRATIONS = [
  { server: 'gmail', displayName: 'Gmail', status: 'connected', sandbox: false, reconnectRequired: false, grantedScopes: [], missingScopes: [], tools: [] },
  { server: 'github', displayName: 'GitHub', status: 'connected', sandbox: false, reconnectRequired: false, grantedScopes: [], missingScopes: [], tools: [] },
]

let calls: Array<{ url: string; method: string; body: unknown }>
let skills: Skill[]

beforeEach(() => {
  calls = []
  skills = [REPLY, STATUS]
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = init?.method ?? 'GET'
      const body = init?.body ? JSON.parse(String(init.body)) : undefined
      calls.push({ url, method, body })
      if (url === '/api/skills' && method === 'GET') return json(skills)
      if (url === '/api/skills' && method === 'POST') return json(skill({ id: 'sk-new', ...body }), 201)
      if (url.startsWith('/api/skills/') && url.endsWith('/duplicate')) {
        return json(skill({ ...STATUS, id: 'sk-copy', name: 'Weekly status (copy)' }), 201)
      }
      if (url.startsWith('/api/skills/') && method === 'PUT') return json(skill({ id: url.split('/').pop()!, ...body }))
      if (url.startsWith('/api/skills/') && method === 'DELETE') return new Response(null, { status: 204 })
      if (url === '/api/agents') return json(AGENTS)
      if (url === '/api/integrations') return json(INTEGRATIONS)
      return new Response(null, { status: 404 })
    }),
  )
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
  window.history.replaceState(null, '', '/')
})

function signIn(permissions: string[]) {
  saveSession('test-token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions,
    displayName: 'Maya Manager',
    email: 'maya@demo.test',
    role: 'manager',
  })
}

function renderSkills() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <Skills />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
}

const MANAGER = ['chat:use', 'agent:read', 'agent:update', 'integration:read']

const dialog = () => screen.getByRole('dialog')

describe('Skills page', () => {
  it('lists every skill with its agent, connectors, ready-made tag and a link to use it in chat', async () => {
    signIn(MANAGER)
    renderSkills()
    const reply = (await screen.findByRole('heading', { name: 'Draft a customer reply' })).closest('article')!
    expect(within(reply).getByText('Ready-made')).toBeInTheDocument()
    expect(within(reply).getByText('Support')).toBeInTheDocument()
    expect(within(reply).getByText('Gmail')).toBeInTheDocument()
    expect(within(reply).getByRole('link', { name: 'Use Draft a customer reply in chat' })).toHaveAttribute(
      'href',
      '/chat?skill=sk-reply',
    )
    const status = screen.getByRole('heading', { name: 'Weekly status' }).closest('article')!
    expect(within(status).getByText('Routed as usual')).toBeInTheDocument()
    expect(within(status).queryByText('Ready-made')).not.toBeInTheDocument()
  })

  it('creates a skill through the dialog with the body the API expects', async () => {
    signIn(MANAGER)
    renderSkills()
    await screen.findByRole('heading', { name: 'Weekly status' })
    fireEvent.click(screen.getAllByRole('button', { name: 'New skill' })[0]!)

    fireEvent.change(within(dialog()).getByLabelText('Name'), { target: { value: '  Triage a bug  ' } })
    fireEvent.change(within(dialog()).getByLabelText(/Description/), { target: { value: 'Sort a new issue' } })
    fireEvent.change(within(dialog()).getByLabelText('Instructions'), { target: { value: 'Read the issue and label it.' } })
    await within(dialog()).findByRole('option', { name: 'Engineer' })
    expect(within(dialog()).queryByRole('option', { name: /Retired one/ })).not.toBeInTheDocument()
    fireEvent.change(within(dialog()).getByLabelText('Default agent'), { target: { value: 'a-eng' } })
    fireEvent.click(await within(dialog()).findByRole('button', { name: 'GitHub' }))
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Create skill' }))

    await waitFor(() => expect(calls.some((call) => call.method === 'POST' && call.url === '/api/skills')).toBe(true))
    expect(calls.find((call) => call.method === 'POST')!.body).toEqual({
      name: 'Triage a bug',
      description: 'Sort a new issue',
      instructions: 'Read the issue and label it.',
      defaultAgentId: 'a-eng',
      requiredConnectors: ['github'],
    })
  })

  it('edits a skill, starting from what it holds', async () => {
    signIn(MANAGER)
    renderSkills()
    fireEvent.click(await screen.findByRole('button', { name: 'Edit Draft a customer reply' }))
    expect(within(dialog()).getByLabelText('Name')).toHaveValue('Draft a customer reply')
    expect(await within(dialog()).findByRole('button', { name: 'Gmail' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.change(within(dialog()).getByLabelText('Default agent'), { target: { value: '' } })
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Save skill' }))

    await waitFor(() => expect(calls.some((call) => call.method === 'PUT')).toBe(true))
    const put = calls.find((call) => call.method === 'PUT')!
    expect(put.url).toBe('/api/skills/sk-reply')
    expect(put.body).toMatchObject({ name: 'Draft a customer reply', defaultAgentId: null, requiredConnectors: ['gmail'] })
  })

  it('duplicates a skill and opens the copy', async () => {
    signIn(MANAGER)
    renderSkills()
    fireEvent.click(await screen.findByRole('button', { name: 'Duplicate Weekly status' }))
    await waitFor(() =>
      expect(calls.some((call) => call.method === 'POST' && call.url === '/api/skills/sk-status/duplicate')).toBe(true),
    )
    expect(await screen.findByRole('heading', { name: 'Change “Weekly status (copy)”' })).toBeInTheDocument()
  })

  it('deletes a skill only after it is confirmed', async () => {
    signIn(MANAGER)
    renderSkills()
    fireEvent.click(await screen.findByRole('button', { name: 'Delete Weekly status' }))
    expect(calls.some((call) => call.method === 'DELETE')).toBe(false)
    expect(screen.getByRole('heading', { name: 'Delete “Weekly status”?' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Delete skill' }))
    await waitFor(() =>
      expect(calls.some((call) => call.method === 'DELETE' && call.url === '/api/skills/sk-status')).toBe(true),
    )
  })

  it('is read-only for a role without agent:update', async () => {
    signIn(['chat:use'])
    renderSkills()
    await screen.findByRole('heading', { name: 'Weekly status' })
    expect(screen.queryByRole('button', { name: 'New skill' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Edit / })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Duplicate / })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Delete / })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Use Weekly status in chat' })).toBeInTheDocument()
    // Without integration:read the connector id stands in for its name.
    expect(screen.getByText('github')).toBeInTheDocument()
  })

  it('filters the list by the search', async () => {
    signIn(MANAGER)
    renderSkills()
    await screen.findByRole('heading', { name: 'Weekly status' })
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search skills' }), { target: { value: 'GitHub' } })
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Draft a customer reply' })).not.toBeInTheDocument())
    expect(screen.getByRole('heading', { name: 'Weekly status' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Showing 1 of 2.')
  })

  it('offers a first skill when there are none', async () => {
    skills = []
    signIn(MANAGER)
    renderSkills()
    expect(await screen.findByText('No skills yet')).toBeInTheDocument()
  })
})
