// @find: tests for the agents list, suggested assistants, create agent, vitest, Agents component tests, Agents page
// @what: Automated tests that check the the agents list screen (/agents) behaves as users expect.
// @flow: Renders Agents from Agents.tsx inside a QueryClientProvider and RouterProvider with mocked API calls
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import { Agents } from './Agents'

/*
 * Agents: a new workspace holds only the General Employee, so the ready-made assistants are
 * suggested on the page and are the first choice in the Add dialog. Adding one creates it with no
 * connectors and says which ones to connect. Writing one from scratch keeps its key out of sight
 * until it needs attention.
 */

// jsdom has the <dialog> element but not its modal methods; every dialog here opens with one.
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

const GENERAL = {
  id: 'a-general',
  key: 'general-employee',
  name: 'General Employee',
  category: 'operations',
  status: 'active',
  revision: 1,
  summary: 'You help with whatever is asked.',
  tools: [],
  fallback: true,
}

const LEGAL = { ...GENERAL, id: 'a-legal', key: 'legal', name: 'Legal', fallback: false }

let calls: Call[]
let agents: Array<Record<string, unknown>>
let permissions: string[]

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  calls = []
  agents = [GENERAL]
  permissions = ['agent:read', 'agent:create', 'integration:read']
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      calls.push({ method, url, body: typeof init.body === 'string' ? JSON.parse(init.body) : null })
      if (method === 'GET' && url === '/api/agents') return json(200, agents)
      if (method === 'POST' && url === '/api/agents/from-template/hr') {
        return json(201, {
          agent: { id: 'a-hr', key: 'hr', name: 'HR', category: 'operations', status: 'active', revision: 1 },
          suggestedConnectors: ['gmail', 'calendar'],
        })
      }
      // The catalogue is not answered, so the cards come from this build's own copy.
      return json(404, { code: 'not_found', detail: 'Not here.' })
    }),
  )
})

afterEach(() => {
  clearSession()
  sessionStorage.clear()
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
})

async function open() {
  saveSession('token', {
    userId: 'user-1',
    workspaceId: 'ws-1',
    permissions,
    displayName: 'Olivia Owner',
    email: 'olivia@example.test',
    role: 'owner',
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <Agents />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
  await screen.findByRole('heading', { name: 'General Employee' })
}

describe('suggested assistants', () => {
  it('are offered while the General Employee is the only agent', async () => {
    await open()

    const suggestions = screen.getByRole('heading', { name: 'Suggested assistants' }).closest('section')!
    for (const name of ['HR', 'Engineering Manager', 'Research', 'Customer Support']) {
      expect(within(suggestions).getByRole('button', { name: `Add the ${name} assistant` })).toBeInTheDocument()
    }
    expect(within(suggestions).getByText('Works with Gmail and Google Calendar')).toBeInTheDocument()
  })

  it('are not offered once the workspace has an agent of its own', async () => {
    agents = [GENERAL, LEGAL]
    await open()

    expect(screen.queryByRole('heading', { name: 'Suggested assistants' })).not.toBeInTheDocument()
  })

  it('are not offered to somebody who cannot add agents', async () => {
    permissions = ['agent:read']
    await open()

    expect(screen.queryByRole('heading', { name: 'Suggested assistants' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add an agent' })).not.toBeInTheDocument()
  })

  it('add the assistant with no connectors, then say which to connect', async () => {
    await open()

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Add the HR assistant' }))
    })

    expect(calls.filter((call) => call.method === 'POST').map((call) => call.url)).toEqual([
      '/api/agents/from-template/hr',
    ])
    const dialog = await screen.findByRole('dialog', { name: 'HR is ready' })
    expect(within(dialog).getByRole('link', { name: 'Connect Gmail to let it act.' })).toHaveAttribute('href', '/connectors')
    expect(within(dialog).getByRole('link', { name: 'Connect Google Calendar to let it act.' })).toBeInTheDocument()
    expect(within(dialog).getByText(/cannot act in any connector yet/)).toBeInTheDocument()

    // The agents list is asked for again, so the new assistant is on the page behind the dialog.
    expect(calls.filter((call) => call.method === 'GET' && call.url === '/api/agents').length).toBeGreaterThan(1)

    fireEvent.click(within(dialog).getByRole('button', { name: 'Open HR' }))
    expect(window.location.pathname).toBe('/agents/a-hr')
  })

  it('name the connectors without a link for somebody who cannot open Connectors', async () => {
    permissions = ['agent:read', 'agent:create']
    await open()

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Add the HR assistant' }))
    })

    const dialog = await screen.findByRole('dialog', { name: 'HR is ready' })
    expect(within(dialog).getByText('Connect Gmail to let it act.')).toBeInTheDocument()
    expect(within(dialog).queryByRole('link', { name: /Connect Gmail/ })).not.toBeInTheDocument()
  })

  it('say what went wrong when one cannot be added, and leave the others to try', async () => {
    await open()
    vi.mocked(fetch).mockImplementationOnce(async () => json(500, { code: 'internal_error', detail: 'Something broke.' }))

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Add the HR assistant' }))
    })

    expect(await screen.findByRole('alert')).toHaveTextContent(/Something broke|went wrong/)
    expect(screen.getByRole('button', { name: 'Add the Research assistant' })).toBeEnabled()
  })
})

describe('the Add an agent dialog', () => {
  async function openDialog() {
    await open()
    fireEvent.click(screen.getByRole('button', { name: 'Add an agent' }))
    return screen.findByRole('dialog', { name: 'Add an agent' })
  }

  it('opens on ready-made assistants, with Start from scratch beside them', async () => {
    const dialog = await openDialog()

    expect(within(dialog).getByRole('heading', { name: 'Start from a ready-made assistant' })).toBeInTheDocument()
    const list = within(dialog).getByRole('list', { name: 'Ready-made assistants' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(4)
    expect(within(dialog).getByRole('button', { name: 'Start from scratch' })).toBeInTheDocument()
    expect(within(dialog).queryByLabelText('Instructions')).not.toBeInTheDocument()
  })

  it('adds a ready-made assistant from the dialog, closes it, and shows what is still to do', async () => {
    const dialog = await openDialog()

    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Add the HR assistant' }))
    })

    expect(await screen.findByRole('dialog', { name: 'HR is ready' })).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Add an agent' })).not.toBeInTheDocument()
  })

  it('keeps the key under Advanced, filled in from the name, and opens it when it needs attention', async () => {
    const dialog = await openDialog()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Start from scratch' }))

    const details = dialog.querySelector('details')!
    expect(within(dialog).getByText('Advanced')).toBeInTheDocument()
    expect(details.open).toBe(false)

    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'People operations' } })
    expect(within(dialog).getByLabelText('Key')).toHaveValue('people-operations')
    expect(details.open).toBe(false)

    // A name that cannot make a key brings the key into view with the reason.
    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: '???' } })
    expect(details.open).toBe(true)
    expect(within(dialog).getByText('Use letters or numbers in the name, or write a key here.')).toBeInTheDocument()
  })

  it('can go back from the form to the ready-made assistants', async () => {
    const dialog = await openDialog()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Start from scratch' }))

    fireEvent.click(within(dialog).getByRole('button', { name: 'Back to ready-made assistants' }))

    expect(within(dialog).getByRole('list', { name: 'Ready-made assistants' })).toBeInTheDocument()
  })
})

describe('agent cards', () => {
  it('say what the agent does in its own description, not a quote of its instructions', async () => {
    agents = [GENERAL, { ...LEGAL, description: 'Reviews contracts and flags risky clauses.' }]
    await open()
    expect(screen.getByText('Reviews contracts and flags risky clauses.')).toBeInTheDocument()
    expect(screen.queryByText('From its instructions')).not.toBeInTheDocument()
    expect(screen.queryByText(/“You help/)).not.toBeInTheDocument()
  })

  it('fall back to a line written about the agent when it has no description', async () => {
    agents = [GENERAL]
    await open()
    expect(screen.getByText('Helps with whatever is asked.')).toBeInTheDocument()
  })

  it('are links with a short readable name, described by what the agent does', async () => {
    agents = [GENERAL, { ...LEGAL, description: 'Reviews contracts.' }]
    await open()
    const link = screen.getByRole('link', { name: 'Open Legal, Active' })
    expect(link).toHaveAttribute('href', '/agents/a-legal')
    expect(link).toHaveAccessibleDescription('Reviews contracts.')
  })
})

describe('writing an agent from scratch', () => {
  it('sends the optional one-line description with the new agent', async () => {
    permissions = ['agent:read', 'agent:create']
    await open()
    fireEvent.click(screen.getAllByRole('button', { name: 'Add an agent' })[0]!)
    const dialog = await screen.findByRole('dialog', { name: 'Add an agent' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Start from scratch' }))
    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'Legal' } })
    fireEvent.change(within(dialog).getByLabelText(/What it does/), { target: { value: '  Reviews contracts.  ' } })
    fireEvent.change(within(dialog).getByLabelText(/Instructions/), { target: { value: 'You review contracts.' } })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Add agent' }))
    })
    const created = calls.find((call) => call.method === 'POST' && call.url === '/api/agents')
    expect(created?.body).toEqual({
      key: 'legal',
      name: 'Legal',
      category: 'operations',
      systemPrompt: 'You review contracts.',
      description: 'Reviews contracts.',
    })
  })
})
