// @find: tests for the command map connect notice, sandbox notice, connect your AI, vitest, CommandMap component tests, Command Map page
// @what: Automated tests that check the the command map connect notice screen (/) behaves as users expect.
// @flow: Renders CommandMap from CommandMap.tsx inside a QueryClientProvider and RouterProvider with mocked API calls
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import { CommandMap } from './CommandMap'

/*
 * The Command Map offers to connect a live AI wherever it says runs are answering on the offline
 * sandbox model: in the notice at the top and in the getting-started guide. Both open the same
 * dialog in place, and neither is offered to somebody who cannot manage providers.
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

const provider = (id: string, kind: string, name: string) => ({
  id,
  displayName: name,
  kind,
  enabled: true,
  platformEnabled: true,
  credentialRef: kind === 'SANDBOX' ? undefined : `provider:${id}`,
  credentialStatus: 'unknown',
  circuitState: 'CLOSED',
  regions: [],
  modelCount: 2,
})

let policy: Record<string, unknown>
let credentials: Array<Record<string, unknown>>

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  policy = { configured: false, exhaustedBehaviour: 'FAIL_CLOSED', candidates: [] }
  credentials = []
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      const path = url.split('?')[0]
      if (path === '/api/model-policy') return json(200, policy)
      if (path === '/api/credentials') return json(200, credentials)
      if (path === '/api/providers') {
        return json(200, [provider('openrouter', 'OPENAI_COMPATIBLE', 'OpenRouter'), provider('sandbox', 'SANDBOX', 'Offline sandbox')])
      }
      if (path === '/api/providers/models') return json(200, [])
      return json(200, [])
    }),
  )
})

afterEach(() => {
  clearSession()
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

async function open(permissions: string[]) {
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
          <CommandMap />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
  await screen.findByText(/No AI model is set for the workspace/)
}

const MANAGER = ['provider:read', 'provider:manage', 'run:read', 'agent:read']

describe('the sandbox notice', () => {
  it('offers Connect your AI beside it to somebody who can manage providers, and opens the dialog in place', async () => {
    await open(MANAGER)

    const notice = screen.getByText(/No AI model is set for the workspace/).closest('.notice') as HTMLElement
    const connect = within(notice).getByRole('button', { name: 'Connect your AI' })
    expect(within(notice).getByRole('link', { name: 'Set up model routing' })).toHaveAttribute('href', '/routing')

    await act(async () => {
      fireEvent.click(connect)
    })

    expect(await screen.findByRole('dialog', { name: 'Connect a live AI model' })).toBeInTheDocument()
    expect(window.location.pathname).not.toBe('/routing')
  })

  it('offers nothing to connect to somebody who can read routing but not manage it', async () => {
    await open(['provider:read', 'run:read'])

    const notice = screen.getByText(/No AI model is set for the workspace/).closest('.notice') as HTMLElement
    expect(within(notice).queryByRole('button', { name: 'Connect your AI' })).not.toBeInTheDocument()
    expect(within(notice).getByText(/An owner or admin can connect a live model/)).toBeInTheDocument()
  })
})

describe('the getting-started guide', () => {
  it('makes the live-model step open the same dialog, while no live model is connected', async () => {
    await open(MANAGER)

    const step = await screen.findByRole('button', { name: 'Connect a live model' })
    await act(async () => {
      fireEvent.click(step)
    })

    expect(await screen.findByRole('dialog', { name: 'Connect a live AI model' })).toBeInTheDocument()
  })

  it('says what is true once a live model is connected, instead of offering to connect one', async () => {
    policy = {
      configured: true,
      exhaustedBehaviour: 'FAIL_CLOSED',
      candidates: [{ position: 0, providerId: 'openrouter', modelId: 'meta-llama/llama-3.3-70b-instruct' }],
    }
    credentials = [{ ref: 'provider:openrouter', kind: 'api_key', present: true }]
    saveSession('token', {
      userId: 'user-1',
      workspaceId: 'ws-1',
      permissions: MANAGER,
      displayName: 'Olivia Owner',
      email: 'olivia@example.test',
      role: 'owner',
    })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <RouterProvider>
          <ToastProvider>
            <CommandMap />
          </ToastProvider>
        </RouterProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByText(/Agents answer with OpenRouter\./)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Connect a live model' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Connect your AI' })).not.toBeInTheDocument()
  })
})

describe('the routing sentence', () => {
  it('is not shown, nor requested, for a role without provider:read', async () => {
    policy = {
      configured: true,
      exhaustedBehaviour: 'FAIL_CLOSED',
      candidates: [{ position: 0, providerId: 'openrouter', modelId: 'meta-llama/llama-3.3-70b-instruct' }],
    }
    credentials = [{ ref: 'provider:openrouter', kind: 'api_key', present: true }]
    saveSession('token', {
      userId: 'user-1',
      workspaceId: 'ws-1',
      permissions: ['run:read', 'agent:read', 'task:create'],
      displayName: 'Eli Employee',
      email: 'eli@example.test',
      role: 'employee',
    })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <RouterProvider>
          <ToastProvider>
            <CommandMap />
          </ToastProvider>
        </RouterProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('Command Map')).toBeInTheDocument()
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 20))
    })
    expect(screen.queryByText(/Agents answer with/)).not.toBeInTheDocument()
    expect(screen.queryByText(/meta-llama/)).not.toBeInTheDocument()
    const calls = (fetch as unknown as { mock: { calls: [string][] } }).mock.calls.map(([url]) => url.split('?')[0])
    expect(calls).not.toContain('/api/model-policy')
  })
})
