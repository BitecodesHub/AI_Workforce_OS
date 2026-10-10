// @find: tests for model routing actions, test now, no circuit state, vitest, ModelRouting component tests, Model routing page
// @what: Automated tests that check the model routing actions screen (/routing) behaves as users expect.
// @flow: Renders ModelRouting from ModelRouting.tsx inside a QueryClientProvider and RouterProvider with mocked API calls
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import { ModelRouting } from './ModelRouting'

/*
 * Model routing's direct actions: Test now for a provider or a model, removing a provider or a
 * model from all routing after showing who uses it, and the warning after turning a provider off.
 * Nothing on the page says a model or provider is set aside or paused after failures.
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


const POLICY = {
  configured: true,
  exhaustedBehaviour: 'FAIL_CLOSED',
  maxAttemptsPerCandidate: 2,
  overallDeadlineSeconds: 300,
  compactOnOverflow: true,
  candidates: [{ position: 0, providerId: 'groq', modelId: 'meta/llama-3.3:free' }],
}

let calls: Call[]
let provider: Record<string, unknown>
let model: Record<string, unknown>
let checkResponse: () => Response | Promise<Response>

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const OK = {
  providerId: 'groq',
  modelId: 'llama-fast',
  modelName: 'Llama Fast',
  result: 'ok',
  message: 'Groq · Llama Fast answered.',
  latencyMs: 840,
  checkedAt: '2026-10-08T00:00:00Z',
}

beforeEach(() => {
  calls = []
  provider = PROVIDER
  model = MODEL
  checkResponse = () => json(200, OK)
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      calls.push({ method, url, body: typeof init.body === 'string' ? JSON.parse(init.body) : null })
      const path = url.split('?')[0]
      if (method === 'GET' && path === '/api/providers') return json(200, [provider])
      if (method === 'GET' && path === '/api/providers/models') return json(200, [model])
      if (method === 'GET' && path === '/api/credentials') return json(200, [{ ref: 'provider:groq', kind: 'api_key', present: true }])
      if (method === 'GET' && path === '/api/model-policy') return json(200, POLICY)
      if (method === 'GET' && path === '/api/providers/groq/models') return json(500, { message: 'down' })
      if (method === 'POST' && path === '/api/providers/groq/check') return checkResponse()
      if (method === 'GET' && path === '/api/model-policy/usage') {
        return json(200, { workspace: true, agents: [{ id: 'a1', name: 'Researcher' }, { id: 'a2', name: 'Legal' }] })
      }
      if (method === 'POST' && path === '/api/model-policy/remove') {
        return json(200, {
          workspace: true,
          agents: [
            { id: 'a1', name: 'Researcher', nowEmpty: true },
            { id: 'a2', name: 'Legal', nowEmpty: false },
          ],
          warning: 'Researcher now uses the workspace default, which has no models.',
        })
      }
      if (method === 'POST' && path === '/api/providers/groq/disable') {
        return json(200, { ...PROVIDER, enabled: false, warning: 'The workspace routing has no provider switched on.' })
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

const MANAGE = ['provider:read', 'provider:manage']

describe('no model or provider is ever shown as set aside', () => {
  it('shows no circuit, pause or "back at" state, whatever an older service sends', async () => {
    provider = { ...PROVIDER, circuitState: 'OPEN' }
    model = { ...MODEL, unavailableUntil: '2099-01-01T00:00:00Z', unavailableReason: 'Out of credit' }
    open(MANAGE)

    await screen.findByRole('columnheader', { name: 'Provider' })
    expect(screen.queryByRole('columnheader', { name: 'Circuit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: 'Availability' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Paused/)).not.toBeInTheDocument()
    expect(screen.queryByText(/^Back /)).not.toBeInTheDocument()
    expect(screen.queryByText('Out of credit')).not.toBeInTheDocument()
  })
})

describe('Test now', () => {
  it('tests a provider, showing a pending state and then the result in plain words', async () => {
    let resolve: (response: Response) => void = () => undefined
    checkResponse = () => new Promise<Response>((done) => (resolve = done))
    open(MANAGE)

    fireEvent.click(await screen.findByRole('button', { name: 'Test Groq now' }))
    expect(await screen.findByText('Testing Groq…')).toBeInTheDocument()
    expect(screen.getByText('Testing Groq…')).toHaveAttribute('aria-live', 'polite')

    await act(async () => resolve(json(200, OK)))
    expect(await screen.findByText('Groq · Llama Fast answered. (840 ms)')).toBeInTheDocument()
    const post = calls.find((call) => call.method === 'POST' && call.url === '/api/providers/groq/check')
    expect(post?.body).toEqual({})
  })

  it('tests one model from the catalogue', async () => {
    checkResponse = () =>
      json(200, { ...OK, result: 'no_credit', message: 'Groq · Llama Fast: out of credit.', latencyMs: 1200 })
    open(MANAGE)

    await act(async () => {
      fireEvent.click(await screen.findByRole('button', { name: 'Test Llama Fast now' }))
    })
    expect(await screen.findByText('Groq · Llama Fast: out of credit. (1.2 s)')).toBeInTheDocument()
    expect(calls.find((call) => call.url === '/api/providers/groq/check')?.body).toEqual({ modelId: 'llama-fast' })
  })

  it('tests a model in the workspace chain', async () => {
    open(MANAGE)

    await act(async () => {
      fireEvent.click(await screen.findByRole('button', { name: 'Test meta/llama-3.3:free now' }))
    })
    expect(calls.find((call) => call.url === '/api/providers/groq/check')?.body).toEqual({ modelId: 'meta/llama-3.3:free' })
  })

  it('says to wait when too many tests were run in the last minute', async () => {
    checkResponse = () => json(429, { code: 'RATE_LIMITED', detail: 'Slow down.', status: 429 })
    open(MANAGE)

    await act(async () => {
      fireEvent.click(await screen.findByRole('button', { name: 'Test Groq now' }))
    })
    expect(await screen.findByText('Too many tests in the last minute. Wait a moment, then try again.')).toBeInTheDocument()
  })

  it('is not offered to somebody who cannot manage providers', async () => {
    open(['provider:read'])

    await screen.findByRole('columnheader', { name: 'Provider' })
    expect(screen.queryByRole('button', { name: /^Test .* now$/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /from all routing/ })).not.toBeInTheDocument()
  })
})

describe('Remove from all routing', () => {
  it('lists who uses a provider before removing it, then shows the warning', async () => {
    open(MANAGE)

    await act(async () => {
      fireEvent.click(await screen.findByRole('button', { name: 'Remove every Groq model from all routing' }))
    })
    const dialog = await screen.findByRole('dialog', { name: 'Remove Groq from all routing?' })
    expect(calls.some((call) => call.method === 'GET' && call.url === '/api/model-policy/usage?providerId=groq')).toBe(true)
    expect(within(dialog).getByText(/These agents list Groq models and will stop using it: Researcher and Legal\./)).toBeInTheDocument()
    expect(calls.some((call) => call.url === '/api/model-policy/remove')).toBe(false)

    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Remove from all routing' }))
    })
    expect(calls.find((call) => call.url === '/api/model-policy/remove')?.body).toEqual({ providerId: 'groq' })
    expect(await screen.findByText('Researcher now uses the workspace default, which has no models.')).toBeInTheDocument()
    expect(screen.getByText('Groq was removed from all routing.')).toBeInTheDocument()
  })

  it('removes one model of the workspace chain, its id encoded in the usage query', async () => {
    open(MANAGE)

    await act(async () => {
      fireEvent.click(await screen.findByRole('button', { name: 'Remove meta/llama-3.3:free from all routing' }))
    })
    const dialog = await screen.findByRole('dialog', { name: 'Remove meta/llama-3.3:free from all routing?' })
    expect(
      calls.some((call) => call.url === '/api/model-policy/usage?providerId=groq&modelId=meta%2Fllama-3.3%3Afree'),
    ).toBe(true)
    expect(within(dialog).getByText(/The workspace routing lists this model/)).toBeInTheDocument()

    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Remove from all routing' }))
    })
    expect(calls.find((call) => call.url === '/api/model-policy/remove')?.body).toEqual({
      providerId: 'groq',
      modelId: 'meta/llama-3.3:free',
    })
  })
})

describe('turning a provider off', () => {
  it('is never refused, and shows which routing it leaves with nothing switched on', async () => {
    open(MANAGE)

    await act(async () => {
      fireEvent.click(await screen.findByRole('button', { name: 'Turn off Groq for this workspace' }))
    })
    expect(await screen.findByText('The workspace routing has no provider switched on.')).toBeInTheDocument()
  })
})
