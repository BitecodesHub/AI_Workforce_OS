import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import { Connectors } from './Connectors'

/* The Connectors page per role, and what happens around Disconnect. */

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

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

const webhook = {
  server: 'webhook',
  displayName: 'Webhook',
  status: 'connected',
  sandbox: false,
  reconnectRequired: false,
  grantedScopes: [],
  missingScopes: [],
  tools: [],
  authType: 'url',
  liveAvailable: true,
  accountLabel: 'hooks.example.test',
}

type Call = { path: string; method: string }
let calls: Call[]
let connected: boolean
/** When set, the integrations list the page receives instead of the webhook alone. */
let list: unknown[] | null

beforeEach(() => {
  calls = []
  connected = true
  list = null
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const path = url.split('?')[0]!
      const method = init?.method ?? 'GET'
      calls.push({ path, method })
      if (path === '/api/integrations/webhook/connection' && method === 'DELETE') {
        connected = false
        return new Response(null, { status: 204 })
      }
      if (path === '/api/integrations') {
        if (list) return json(200, list)
        return json(200, [connected ? webhook : { ...webhook, status: 'sandbox', sandbox: true, accountLabel: null }])
      }
      if (path === '/api/voice/status') return json(200, { keyStored: false })
      return json(200, [])
    }),
  )
})

afterEach(() => {
  clearSession()
  sessionStorage.clear()
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
})

function open(permissions: string[]) {
  saveSession('token', { userId: 'u', workspaceId: 'w', permissions, displayName: 'Olivia', email: 'o@example.test', role: 'owner' })
  window.history.pushState({}, '', '/connectors')
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <Connectors />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
}

const OWNER = ['integration:read', 'integration:connect', 'integration:disconnect', 'chat:use', 'provider:manage']

describe('Connectors: voice card per role', () => {
  it('does not ask for voice status without chat:use, and says why it is not shown', async () => {
    open(['integration:read', 'agent:read'])
    expect(await screen.findByText(/Its status is shown to people whose role can use Chat/)).toBeInTheDocument()
    expect(calls.some((c) => c.path === '/api/voice/status')).toBe(false)
    expect(screen.queryByText(/could not be loaded/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Store key|Replace key/ })).not.toBeInTheDocument()
  })

  it('reads voice status for a role with chat:use', async () => {
    open(['integration:read', 'chat:use'])
    expect(await screen.findByText("Using your browser's built-in voice until a key is stored.")).toBeInTheDocument()
    expect(calls.some((c) => c.path === '/api/voice/status')).toBe(true)
  })
})

describe('Connectors: Disconnect', () => {
  it('keeps the connection when the confirmation is cancelled', async () => {
    open(OWNER)
    fireEvent.click(await screen.findByRole('button', { name: 'Disconnect Webhook' }))
    const dialog = screen.getByRole('dialog', { name: 'Disconnect Webhook?' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Keep it connected' }))
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false)
    expect(screen.getByRole('button', { name: 'Disconnect Webhook' })).toBeInTheDocument()
  })

  it('moves focus to the card once the Disconnect button that opened the dialog is gone', async () => {
    open(OWNER)
    fireEvent.click(await screen.findByRole('button', { name: 'Disconnect Webhook' }))
    const dialog = screen.getByRole('dialog', { name: 'Disconnect Webhook?' })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Disconnect' }))
    })
    expect(calls.some((c) => c.method === 'DELETE' && c.path === '/api/integrations/webhook/connection')).toBe(true)
    const connect = await screen.findByRole('button', { name: 'Connect Webhook' })
    await waitFor(() => expect(connect).toHaveFocus())
    expect(screen.queryByRole('button', { name: 'Disconnect Webhook' })).not.toBeInTheDocument()
  })
})

const jira = { ...webhook, server: 'jira', displayName: 'Jira', status: 'sandbox', sandbox: true, accountLabel: null, authType: 'token' }
const slack = { ...webhook, server: 'slack', displayName: 'Slack', status: 'error', lastError: 'Token expired', authType: 'token' }

describe('Connectors: toolbar', () => {
  const cardNames = () => screen.getAllByRole('heading', { level: 2 }).map((heading) => heading.textContent)

  it('puts the search, two dropdowns and the live summary on one toolbar instead of a banner', async () => {
    list = [webhook, jira]
    open(OWNER)
    const toolbar = await screen.findByRole('search', { name: 'Filter connectors' })
    expect(within(toolbar).getByLabelText('Search connectors')).toHaveAttribute('placeholder', 'Search connectors')
    expect(within(toolbar).getByRole('button', { name: 'Category: All categories, 3' })).toHaveAttribute('aria-haspopup', 'listbox')
    expect(within(toolbar).getByRole('button', { name: 'Status: All statuses, 3' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Showing 3 of 3')
    // No banner, no chips and no Clear filters while nothing is filtered.
    expect(screen.queryByText(/run on practice data in a sandbox/)).not.toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Active filters' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /needs attention/ })).not.toBeInTheDocument()
  })

  it('opens the practice-data sentence from the summary pill and closes it with Escape', async () => {
    list = [webhook, jira]
    open(OWNER)
    const pill = await screen.findByRole('button', { name: /1 live · 1 practice/ })
    expect(pill).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(pill)
    expect(pill).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('note')).toHaveTextContent(
      '1 connected to a live account. The other 1 run on practice data in a sandbox, so nothing an agent does through them leaves this workspace.',
    )
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
    expect(pill).toHaveFocus()
  })

  it('filters by category from the dropdown, keeps it in the address and shows a removable chip', async () => {
    list = [webhook, jira]
    open(OWNER)
    fireEvent.click(await screen.findByRole('button', { name: /^Category:/ }))
    const listbox = screen.getByRole('listbox', { name: 'Category' })
    const options = within(listbox).getAllByRole('option')
    expect(options.map((option) => option.textContent)).toEqual(['All categories3', 'Engineering1', 'Automation1', 'Voice1'])
    expect(options[0]).toHaveAttribute('aria-selected', 'true')
    fireEvent.click(within(listbox).getByRole('option', { name: /Engineering/ }))

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(window.location.search).toBe('?category=engineering')
    expect(screen.getByRole('button', { name: 'Category: Engineering, 1' })).toHaveFocus()
    expect(cardNames()).toEqual(['Jira'])
    expect(screen.getByRole('status')).toHaveTextContent('Showing 1 of 3')

    fireEvent.click(screen.getByRole('button', { name: 'Remove filter: Engineering' }))
    expect(window.location.search).toBe('')
    expect(cardNames()).toHaveLength(3)
  })

  it('works the category listbox from the keyboard', async () => {
    list = [webhook, jira]
    open(OWNER)
    const trigger = await screen.findByRole('button', { name: /^Category:/ })
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    const listbox = screen.getByRole('listbox', { name: 'Category' })
    expect(listbox).toHaveFocus()
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    const activeLabel = () => document.getElementById(listbox.getAttribute('aria-activedescendant')!)?.textContent
    expect(activeLabel()).toBe('All categories3')
    fireEvent.keyDown(listbox, { key: 'ArrowDown' })
    fireEvent.keyDown(listbox, { key: 'ArrowDown' })
    expect(activeLabel()).toBe('Automation1')
    fireEvent.keyDown(listbox, { key: 'End' })
    expect(activeLabel()).toBe('Voice1')
    fireEvent.keyDown(listbox, { key: 'e' })
    expect(activeLabel()).toBe('Engineering1')
    fireEvent.keyDown(listbox, { key: 'Escape' })
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
    expect(window.location.search).toBe('')

    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    fireEvent.keyDown(screen.getByRole('listbox'), { key: 'End' })
    fireEvent.keyDown(screen.getByRole('listbox'), { key: 'Enter' })
    expect(window.location.search).toBe('?category=voice')
    expect(cardNames()).toEqual(['ElevenLabs'])
  })

  it('filters by status, with a count beside each choice', async () => {
    list = [webhook, jira]
    open(OWNER)
    fireEvent.click(await screen.findByRole('button', { name: /^Status:/ }))
    const listbox = screen.getByRole('listbox', { name: 'Status' })
    expect(within(listbox).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'All statuses3',
      'Connected1',
      'Sandbox1',
      'Needs attention0',
    ])
    fireEvent.click(within(listbox).getByRole('option', { name: /Connected/ }))
    expect(window.location.search).toBe('?status=connected')
    expect(cardNames()).toEqual(['Webhook'])
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(window.location.search).toBe('')
  })

  it('offers a needs-attention pill that applies, and then lifts, that filter', async () => {
    list = [webhook, jira, slack]
    open(OWNER)
    const pill = await screen.findByRole('button', { name: '1 needs attention' })
    expect(pill).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(pill)
    expect(window.location.search).toBe('?status=attention')
    expect(pill).toHaveAttribute('aria-pressed', 'true')
    expect(cardNames()).toEqual(['Slack'])
    expect(screen.getByRole('button', { name: 'Status: Needs attention, 1' })).toBeInTheDocument()
    fireEvent.click(pill)
    expect(window.location.search).toBe('')
  })

  it('clears the search with Escape', async () => {
    list = [webhook, jira]
    open(OWNER)
    const search = await screen.findByLabelText('Search connectors')
    fireEvent.change(search, { target: { value: 'jira' } })
    expect(cardNames()).toEqual(['Jira'])
    expect(screen.getByRole('button', { name: 'Remove filter: "jira"' })).toBeInTheDocument()
    fireEvent.keyDown(search, { key: 'Escape' })
    expect(search).toHaveValue('')
    expect(cardNames()).toHaveLength(3)
  })
})
