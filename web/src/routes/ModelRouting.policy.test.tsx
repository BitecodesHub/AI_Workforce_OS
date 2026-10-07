import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import { ModelRouting } from './ModelRouting'

/*
 * Model routing's own routing policy card is the shared candidate-chain editor (the same one an
 * agent's page uses), saving the workspace's chain and offering nothing that belongs to an agent.
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

const PROVIDER = {
  id: 'groq',
  displayName: 'Groq',
  kind: 'OPENAI_COMPATIBLE',
  enabled: true,
  platformEnabled: true,
  credentialRef: 'provider:groq',
  credentialStatus: 'unknown',
  circuitState: 'CLOSED',
  regions: [],
  modelCount: 1,
}
const MODEL = {
  providerId: 'groq',
  modelId: 'llama-fast',
  displayName: 'Llama Fast',
  contextWindow: 128000,
  maxOutputTokens: 4096,
  supportsTools: true,
  supportsJsonMode: true,
  supportsStreaming: true,
  inputCostPerMillion: 1,
  outputCostPerMillion: 1,
  enabled: true,
}

const CATALOGUE = {
  providerId: 'groq',
  providerName: 'Groq',
  source: 'live',
  fetchedAt: '2026-10-06T00:00:00Z',
  total: 2,
  freeCount: 1,
  models: [
    { id: 'openai/gpt-oss-120b', displayName: 'GPT OSS 120B', free: false, toolCalling: true, vision: false, contextLength: 131072, pricePerMTokIn: 0.15, pricePerMTokOut: 0.75 },
    { id: 'qwen/qwen3-32b', displayName: 'Qwen3 32B', free: true, toolCalling: true, vision: false, contextLength: 131072 },
  ],
}

let calls: Call[]
let catalogueFails = false

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  calls = []
  catalogueFails = false
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      calls.push({ method, url, body: typeof init.body === 'string' ? JSON.parse(init.body) : null })
      const path = url.split('?')[0]
      if (method === 'GET' && path === '/api/providers') return json(200, [PROVIDER])
      if (method === 'GET' && path === '/api/providers/models') return json(200, [MODEL])
      if (method === 'GET' && path === '/api/credentials') return json(200, [{ ref: 'provider:groq', kind: 'api_key', present: true }])
      if (method === 'GET' && path === '/api/model-policy') {
        return json(200, { configured: false, exhaustedBehaviour: 'FAIL_CLOSED', candidates: [] })
      }
      if (method === 'PUT' && path === '/api/model-policy') return json(200, { configured: true, candidates: [] })
      if (method === 'GET' && path === '/api/providers/groq/models') {
        if (catalogueFails) return json(500, { message: 'down' })
        return json(200, CATALOGUE)
      }
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
          <ModelRouting />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
}

describe('the routing policy on Model routing', () => {
  it('is the shared editor, saving the workspace chain with PUT /api/model-policy', async () => {
    open(['provider:read', 'provider:manage'])

    fireEvent.click(await screen.findByRole('button', { name: 'Add a candidate' }))
    expect(screen.getByLabelText('Candidate 1 provider')).toHaveValue('groq')
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save routing policy' }))
    })

    const put = calls.find((call) => call.method === 'PUT')
    expect(put?.url).toBe('/api/model-policy')
    expect(put?.body).toEqual({
      candidates: [{ providerId: 'groq', modelId: 'llama-fast', temperature: null, maxOutputTokens: null }],
    })
    expect(await screen.findByText('Routing policy saved.')).toBeInTheDocument()
  })

  it('offers nothing that belongs to an agent', async () => {
    open(['provider:read', 'provider:manage'])

    await screen.findByRole('button', { name: 'Add a candidate' })
    expect(screen.queryByRole('button', { name: 'Use the workspace policy' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save these models' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Routing policy' })).toBeInTheDocument()
  })

  it('is read-only without provider:manage', async () => {
    open(['provider:read'])

    await screen.findByRole('heading', { name: 'Routing policy' })
    expect(screen.queryByRole('button', { name: 'Add a candidate' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save routing policy' })).not.toBeInTheDocument()
  })

  it("offers every model the provider lists, free first, with the saved ones folded in", async () => {
    open(['provider:read', 'provider:manage'])

    fireEvent.click(await screen.findByRole('button', { name: 'Add a candidate' }))
    expect(await screen.findByText('2 models that can use tools, 1 free.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('combobox', { name: 'Candidate 1 model' }))

    const list = screen.getByRole('listbox')
    expect(within(list).getAllByRole('option').map((node) => node.getAttribute('data-model-id'))).toEqual([
      'qwen/qwen3-32b',
      'openai/gpt-oss-120b',
      'llama-fast',
    ])
    fireEvent.click(within(list).getByRole('option', { name: /GPT OSS 120B/ }))
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save routing policy' }))
    })

    expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({
      candidates: [{ providerId: 'groq', modelId: 'openai/gpt-oss-120b', temperature: null, maxOutputTokens: null }],
    })
  })

  it('says so in plain words when the list cannot be loaded, and still offers the saved models', async () => {
    catalogueFails = true
    open(['provider:read', 'provider:manage'])

    fireEvent.click(await screen.findByRole('button', { name: 'Add a candidate' }))

    expect(await screen.findByText("Couldn't load the list from Groq; showing saved models.")).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Candidate 1 model' })).toHaveValue('Llama Fast')
    expect(screen.getByRole('button', { name: 'Refresh the model list from Groq' })).toBeInTheDocument()
  })

  it('asks the provider again with Refresh list', async () => {
    open(['provider:read', 'provider:manage'])

    fireEvent.click(await screen.findByRole('button', { name: 'Add a candidate' }))
    await screen.findByText('2 models that can use tools, 1 free.')
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Refresh the model list from Groq' }))
    })

    expect(calls.some((call) => call.url === '/api/providers/groq/models?refresh=true')).toBe(true)
  })
})
