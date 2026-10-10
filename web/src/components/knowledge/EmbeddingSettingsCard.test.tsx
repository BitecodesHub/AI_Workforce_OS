// @find: tests for EmbeddingSettingsCard, embedding model, embeddings settings, search by meaning, vector search, choose embedding, re-index, knowledge settings, Knowledge page
// @what: Automated tests for EmbeddingSettingsCard.
// @flow: Run with the web test runner; covers EmbeddingSettingsCard.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearSession, saveSession } from '../../lib/session'
import { ToastProvider } from '../../lib/toast'
import { groupByProvider, optionValue, parseOptionValue, progressShare } from '../../lib/embeddingQueries'
import { EmbeddingSettingsCard } from './EmbeddingSettingsCard'

/*
 * Choosing the embedding model on the Knowledge page: the live list of embedding models, what is
 * sent when one is chosen, the provider's refusal shown beside the picker, and the progress of
 * re-indexing.
 */

type Call = { method: string; url: string; body: unknown }
let calls: Call[] = []
let status: object
let putAnswer: { status: number; body: unknown }

const KEYWORD = {
  provider: 'sandbox',
  model: 'sandbox-embed-1',
  dimension: 1536,
  searchMode: 'keyword',
  progress: { state: 'idle', sourcesTotal: 0, sourcesDone: 0, passagesTotal: 0, passagesDone: 0 },
}

const MODELS = {
  models: [
    {
      providerId: 'nvidia',
      providerName: 'NVIDIA NIM',
      id: 'nvidia/nemotron-3-embed-1b',
      displayName: 'Nemotron 3 Embed 1B',
      free: true,
      contextLength: 0,
      pricePerMTokIn: 0,
      pricingNote: null,
    },
    {
      providerId: 'openrouter',
      providerName: 'OpenRouter',
      id: 'openai/text-embedding-3-small',
      displayName: 'Text Embedding 3 Small',
      free: false,
      contextLength: 8191,
      pricePerMTokIn: 0.02,
      pricingNote: null,
    },
  ],
  notes: [],
}

function json(code: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status: code, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  calls = []
  status = KEYWORD
  putAnswer = { status: 200, body: KEYWORD }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      const body = typeof init.body === 'string' ? JSON.parse(init.body) : null
      calls.push({ method, url, body })
      if (method === 'GET' && url === '/api/knowledge/embedding') return json(200, status)
      if (method === 'GET' && url === '/api/providers/embedding-models') return json(200, MODELS)
      if (method === 'PUT' && url === '/api/knowledge/embedding') return json(putAnswer.status, putAnswer.body)
      return json(404, { code: 'not_found', detail: 'Not here.' })
    }),
  )
  saveSession('token', {
    userId: 'u1',
    workspaceId: 'w1',
    permissions: ['knowledge:read', 'knowledge:source_manage', 'provider:read'],
    displayName: 'Ada Admin',
    email: 'ada@example.test',
    role: 'admin',
  })
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
})

function renderCard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <EmbeddingSettingsCard />
      </ToastProvider>
    </QueryClientProvider>,
  )
}

describe('choosing the embedding model', () => {
  it('lists the live embedding models by provider and sends the chosen one', async () => {
    renderCard()
    expect(await screen.findByText(/searched by keyword only/)).toBeInTheDocument()
    const picker = await screen.findByLabelText('Embedding model')
    await screen.findByRole('option', { name: 'Nemotron 3 Embed 1B (free)' })

    fireEvent.change(picker, { target: { value: 'nvidia::nvidia/nemotron-3-embed-1b' } })
    putAnswer = {
      status: 200,
      body: {
        provider: 'nvidia',
        model: 'nvidia/nemotron-3-embed-1b',
        dimension: 2048,
        searchMode: 'keyword+meaning',
        progress: {
          state: 'running',
          sourcesTotal: 1,
          sourcesDone: 0,
          passagesTotal: 22,
          passagesDone: 0,
          message: 'Re-indexing 22 passage(s) for search by meaning.',
        },
      },
    }
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Use this model' }))
    })

    const put = calls.find((call) => call.method === 'PUT')
    expect(put?.body).toEqual({ providerId: 'nvidia', modelId: 'nvidia/nemotron-3-embed-1b' })
    expect(await screen.findByText('Re-indexing 22 passage(s) for search by meaning.')).toBeInTheDocument()
    expect(screen.getByLabelText('Passages embedded again so far')).toBeInTheDocument()
  })

  it('shows the provider refusal beside the picker and keeps the current model', async () => {
    putAnswer = {
      status: 422,
      body: {
        code: 'validation_failed',
        detail: "NVIDIA NIM does not serve nvidia/embed-qa-4 for this workspace's key. Choose another embedding model.",
      },
    }
    renderCard()
    const picker = await screen.findByLabelText('Embedding model')
    await screen.findByRole('option', { name: 'Nemotron 3 Embed 1B (free)' })
    fireEvent.change(picker, { target: { value: 'nvidia::nvidia/nemotron-3-embed-1b' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Use this model' }))
    })
    expect(await screen.findByText(/does not serve nvidia\/embed-qa-4/)).toBeInTheDocument()
    expect(screen.getByText(/searched by keyword only/)).toBeInTheDocument()
  })

  it('hides the picker from someone who cannot manage knowledge', async () => {
    saveSession('token', {
      userId: 'u2',
      workspaceId: 'w1',
      permissions: ['knowledge:read'],
      displayName: 'Eli Employee',
      email: 'eli@example.test',
      role: 'employee',
    })
    renderCard()
    expect(await screen.findByText(/searched by keyword only/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Embedding model')).toBeNull()
    expect(calls.some((call) => call.url === '/api/providers/embedding-models')).toBe(false)
  })
})

describe('embedding helpers', () => {
  it('round-trips a picker value and keeps keyword only distinct', () => {
    expect(parseOptionValue(optionValue('nvidia', 'nvidia/x::y'))).toEqual({ providerId: 'nvidia', modelId: 'nvidia/x::y' })
    expect(parseOptionValue(optionValue('sandbox', 'sandbox-embed-1'))).toEqual({ providerId: 'sandbox', modelId: null })
  })

  it('measures progress and groups models by provider', () => {
    expect(progressShare({ state: 'running', sourcesTotal: 1, sourcesDone: 0, passagesTotal: 20, passagesDone: 5 })).toBe(0.25)
    expect(progressShare({ state: 'done', sourcesTotal: 0, sourcesDone: 0, passagesTotal: 0, passagesDone: 0 })).toBe(1)
    expect(groupByProvider(MODELS.models).map((group) => group.providerName)).toEqual(['NVIDIA NIM', 'OpenRouter'])
  })
})
