// @find: tests for BedrockCredentialFields, region picker tests, Find my region, bedrock paste, POST /api/providers/bedrock/bedrock-regions
// @what: Tests the Bedrock region picker, Find my region and pasted-credential handling.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EMPTY_BEDROCK_FORM, type BedrockForm } from '../../lib/bedrock'
import { clearSession, saveSession } from '../../lib/session'
import { BedrockCredentialFields } from './BedrockCredentialFields'

/*
 * The Bedrock sign-in: a region picker grouped by geography and searchable, "Find my region",
 * which asks the service which regions take the key and chooses the best one, and a paste that
 * belongs to the other sign-in switching to it.
 */

type Call = { method: string; url: string; body: unknown }
let calls: Call[]
let finding: unknown

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  calls = []
  finding = {
    best: 'ap-southeast-2',
    message: 'These credentials work in 2 regions. ap-southeast-2 is chosen: a model answered there.',
    regions: [
      { region: 'ap-southeast-2', status: 'ready', modelCount: 40, message: 'Works, and a model answered.' },
      { region: 'us-west-2', status: 'accepted', modelCount: 60, message: 'Accepts the credentials.' },
      { region: 'us-east-1', status: 'refused', modelCount: null, message: 'Did not accept these credentials.' },
    ],
  }
  saveSession('token', {
    userId: 'user-1',
    workspaceId: 'ws-1',
    permissions: ['provider:read', 'provider:manage'],
    displayName: 'Olivia Owner',
    email: 'olivia@example.test',
    role: 'owner',
  })
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      calls.push({ method, url, body: typeof init.body === 'string' ? JSON.parse(init.body) : null })
      if (method === 'POST' && url === '/api/providers/bedrock/bedrock-regions') return json(200, finding)
      return json(404, { code: 'not_found', detail: 'Not found.' })
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  clearSession()
})

let latest: BedrockForm = EMPTY_BEDROCK_FORM

function Harness() {
  const [form, setForm] = useState<BedrockForm>(EMPTY_BEDROCK_FORM)
  return (
    <BedrockCredentialFields
      providerId="bedrock"
      form={form}
      onChange={(next) => {
        latest = next
        setForm(next)
      }}
    />
  )
}

function renderFields() {
  latest = EMPTY_BEDROCK_FORM
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <Harness />
    </QueryClientProvider>,
  )
}

describe('the region picker', () => {
  it('starts on US East (N. Virginia), and finds regions by city, id or geography, grouped', () => {
    renderFields()
    const region = screen.getByRole('combobox', { name: 'Region' })
    expect(region).toHaveValue('US East (N. Virginia) — us-east-1')

    fireEvent.change(region, { target: { value: 'tokyo' } })
    const list = screen.getByRole('listbox')
    expect(within(list).getByRole('group', { name: 'Asia Pacific' })).toBeInTheDocument()
    expect(within(list).getAllByRole('option')).toHaveLength(1)

    fireEvent.change(region, { target: { value: 'europe' } })
    expect(within(screen.getByRole('listbox')).getAllByRole('option').length).toBeGreaterThan(5)
    fireEvent.click(screen.getByRole('option', { name: /Europe \(Ireland\), eu-west-1/ }))
    expect(latest.region).toBe('eu-west-1')
    expect(region).toHaveValue('Europe (Ireland) — eu-west-1')
  })

  it('says so when nothing matches', () => {
    renderFields()
    fireEvent.change(screen.getByRole('combobox', { name: 'Region' }), { target: { value: 'atlantis' } })
    expect(screen.getByText(/No region matches that/)).toBeInTheDocument()
  })
})

describe('Find my region', () => {
  it('waits for the key, then asks every region and chooses the best, marking what it found', async () => {
    renderFields()
    const button = screen.getByRole('button', { name: 'Find my region' })
    expect(button).toBeDisabled()

    fireEvent.change(screen.getByLabelText('Bedrock API key'), { target: { value: '  ABSKexampleexampleexample  ' } })
    expect(button).toBeEnabled()
    await act(async () => {
      fireEvent.click(button)
    })

    const sent = calls.find((call) => call.url === '/api/providers/bedrock/bedrock-regions')
    expect(sent?.body).toEqual({ value: JSON.stringify({ type: 'api_key', apiKey: 'ABSKexampleexampleexample', region: 'us-east-1' }) })
    expect(await screen.findByText(/ap-southeast-2 is chosen/)).toBeInTheDocument()
    expect(screen.getByText(/It also works in US West \(Oregon\) — us-west-2/)).toBeInTheDocument()
    expect(latest.region).toBe('ap-southeast-2')

    const region = screen.getByRole('combobox', { name: 'Region' })
    expect(region).toHaveValue('Asia Pacific (Sydney) — ap-southeast-2')
    fireEvent.click(region)
    expect(screen.getByRole('option', { name: /us-east-1, Not accepted/ })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /ap-southeast-2, Works/ })).toBeInTheDocument()
  })

  it('keeps the chosen region and says why when no region accepts the key', async () => {
    finding = { best: null, message: 'No region accepted these credentials. Check that you copied the whole key.', regions: [] }
    renderFields()
    fireEvent.change(screen.getByLabelText('Bedrock API key'), { target: { value: 'ABSKexampleexampleexample' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Find my region' }))
    })
    expect(await screen.findByText(/No region accepted these credentials/)).toBeInTheDocument()
    expect(latest.region).toBe('us-east-1')
  })
})

describe('a paste that belongs to the other sign-in', () => {
  it('moves an access key ID pasted as an API key, and switches the sign-in', () => {
    renderFields()
    fireEvent.change(screen.getByLabelText('Bedrock API key'), { target: { value: 'AKIAIOSFODNN7EXAMPLE' } })
    expect(latest.auth).toBe('access_key')
    expect(latest.apiKey).toBe('')
    expect(screen.getByLabelText('Access key ID')).toHaveValue('AKIAIOSFODNN7EXAMPLE')
    expect(screen.getByText(/switched to AWS access key/)).toBeInTheDocument()
  })

  it('names another provider’s key instead of sending it to AWS', () => {
    renderFields()
    fireEvent.change(screen.getByLabelText('Bedrock API key'), { target: { value: 'sk-or-v1-abcdef' } })
    expect(screen.getByText(/looks like an OpenRouter key, not an AWS one/)).toBeInTheDocument()
  })

  it('asks for the session token with a temporary ASIA key', () => {
    renderFields()
    fireEvent.change(screen.getByLabelText('Sign in with'), { target: { value: 'access_key' } })
    fireEvent.change(screen.getByLabelText('Access key ID'), { target: { value: 'ASIAIOSFODNN7EXAMPLE' } })
    expect(screen.getByText(/an ASIA key is temporary and comes with a session token/)).toBeInTheDocument()
  })
})
