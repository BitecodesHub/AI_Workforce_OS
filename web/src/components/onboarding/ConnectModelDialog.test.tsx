// @find: tests for ConnectModelDialog, connect model tests, openrouter, groq, bedrock key test, store credential, enable provider, model policy, /api/providers, /api/credentials, /api/model-policy
// @what: Tests the Connect a model dialog flow against mocked provider, credential and policy endpoints.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearSession, saveSession } from '../../lib/session'
import { ConnectModelDialog } from './ConnectModelDialog'

/*
 * Connect your AI: a key is checked before anything is saved, a refused key leaves everything as
 * it was, and a good one is stored, switched on and put in the routing policy - asking first when
 * the workspace already has a policy - with a closing sentence that says whether the offline
 * sandbox model is still a fallback.
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

const provider = (id: string, kind: string, extra: Record<string, unknown> = {}) => ({
  id,
  displayName: { openrouter: 'OpenRouter', groq: 'Groq', bedrock: 'AWS Bedrock', sandbox: 'Offline sandbox' }[id] ?? id,
  kind,
  enabled: true,
  platformEnabled: true,
  credentialRef: kind === 'SANDBOX' ? undefined : `provider:${id}`,
  credentialStatus: 'unknown',
  circuitState: 'CLOSED',
  regions: [],
  modelCount: 3,
  ...extra,
})

const model = (providerId: string, modelId: string, displayName: string, input: number, output: number, extra = {}) => ({
  providerId,
  modelId,
  displayName,
  contextWindow: 128_000,
  maxOutputTokens: 4096,
  supportsTools: true,
  supportsJsonMode: true,
  supportsStreaming: true,
  inputCostPerMillion: input,
  outputCostPerMillion: output,
  enabled: true,
  ...extra,
})

let calls: Call[]
let providers: ReturnType<typeof provider>[]
let policy: Record<string, unknown>
let testAnswer: { result: string; message: string }
let failNext: Set<string>
let bedrockRefusal: { field: string; problem: string } | null

const UNSET_POLICY = {
  configured: false,
  exhaustedBehaviour: 'FAIL_CLOSED',
  maxAttemptsPerCandidate: 2,
  overallDeadlineSeconds: 300,
  compactOnOverflow: true,
  candidates: [],
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  calls = []
  failNext = new Set()
  bedrockRefusal = null
  providers = [
    provider('openrouter', 'OPENAI_COMPATIBLE', { enabled: false }),
    provider('groq', 'OPENAI_COMPATIBLE'),
    provider('bedrock', 'BEDROCK'),
    provider('sandbox', 'SANDBOX'),
  ]
  policy = UNSET_POLICY
  testAnswer = { result: 'valid', message: 'OpenRouter accepted the key.' }
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
      const key = `${method} ${url}`
      if (failNext.delete(key)) return json(500, { code: 'internal_error', detail: 'Something broke on our side.' })
      if (key === 'GET /api/providers') return json(200, providers)
      if (key === 'GET /api/providers/models') {
        return json(200, [
          model('openrouter', 'anthropic/claude-sonnet-4', 'Claude Sonnet 4', 3, 15),
          model('openrouter', 'meta-llama/llama-3.3-70b-instruct', 'Llama 3.3 70B', 0.12, 0.3),
          model('openrouter', 'embedder', 'Embedder', 0.02, 0, { maxOutputTokens: 1 }),
          model('groq', 'llama-3.1-8b-instant', 'Llama 3.1 8B', 0.05, 0.08),
          model('bedrock', 'amazon.nova-micro-v1:0', 'Nova Micro', 0.035, 0.14),
          model('sandbox', 'sandbox-1', 'Sandbox', 0, 0),
        ])
      }
      if (key === 'GET /api/model-policy') return json(200, policy)
      if (key === 'GET /api/credentials') return json(200, [])
      if (key === 'POST /api/providers/openrouter/test') return json(200, testAnswer)
      if (key === 'PUT /api/credentials/provider:openrouter') {
        return json(200, { ref: 'provider:openrouter', kind: 'api_key', present: true, fingerprint: 'ab12' })
      }
      if (key === 'POST /api/providers/openrouter/enable') return json(200, { ...providers[0], enabled: true })
      if (key === 'POST /api/providers/groq/test') return json(200, { result: 'valid', message: 'Groq accepted the key.' })
      if (key === 'PUT /api/credentials/provider:groq') {
        return json(200, { ref: 'provider:groq', kind: 'api_key', present: true, fingerprint: 'cd34' })
      }
      if (key === 'POST /api/providers/bedrock/test') {
        if (bedrockRefusal) {
          return json(400, { code: 'validation_failed', detail: 'Some of the values supplied are not valid.', errors: bedrockRefusal })
        }
        return json(200, { result: 'valid', message: 'AWS Bedrock accepted the credentials, and Nova Micro answered.' })
      }
      if (key === 'PUT /api/credentials/provider:bedrock') {
        return json(200, { ref: 'provider:bedrock', kind: 'aws_bedrock', present: true, fingerprint: 'ef56' })
      }
      if (key === 'PUT /api/model-policy') {
        policy = { ...(policy as object), configured: true, ...(init.body ? JSON.parse(String(init.body)) : {}) }
        return json(200, policy)
      }
      return json(404, { code: 'not_found', detail: 'Not here.' })
    }),
  )
})

afterEach(() => {
  clearSession()
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

const sent = (method: string, url: string) => calls.filter((call) => call.method === method && call.url === url)
const wrote = () => calls.filter((call) => call.method !== 'GET').map((call) => `${call.method} ${call.url}`)

function Harness({ onClose = () => {} }: { onClose?: () => void }) {
  const [open, setOpen] = useState(true)
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Reopen
      </button>
      <ConnectModelDialog
        open={open}
        onClose={() => {
          setOpen(false)
          onClose()
        }}
      />
    </>
  )
}

async function open() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const view = render(
    <QueryClientProvider client={client}>
      <Harness />
    </QueryClientProvider>,
  )
  await screen.findByLabelText('API key')
  // The provider list has loaded once OpenRouter is a choice.
  await screen.findByRole('option', { name: /^OpenRouter/ })
  return view
}

async function paste(key: string, providerName = 'OpenRouter') {
  fireEvent.change(screen.getByLabelText('Provider'), {
    target: { value: providers.find((entry) => entry.displayName === providerName)?.id },
  })
  fireEvent.change(screen.getByLabelText('API key'), { target: { value: key } })
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: 'Verify and connect' }))
  })
}

describe('which providers it offers', () => {
  it('offers every live provider, Bedrock included, but not the offline sandbox', async () => {
    await open()

    const options = within(screen.getByLabelText('Provider')).getAllByRole('option').map((option) => option.textContent)
    // Providers with a free way in say so, so a first-time setup can start without paying.
    expect([...options].sort()).toEqual(['AWS Bedrock', 'Groq (free to start)', 'OpenRouter (free to start)'])
  })
})

describe('Amazon Bedrock', () => {
  function chooseBedrock() {
    fireEvent.change(screen.getByLabelText('Provider'), { target: { value: 'bedrock' } })
  }

  it('asks for a Bedrock API key and a region, with the setup steps, instead of one API key', async () => {
    await open()
    chooseBedrock()

    expect(screen.queryByLabelText('API key')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Sign in with')).toHaveValue('api_key')
    expect(screen.getByLabelText('Bedrock API key')).toHaveAttribute('type', 'password')
    expect(screen.getByRole('combobox', { name: 'Region' })).toHaveValue('US East (N. Virginia) — us-east-1')
    expect(screen.getByText('How to set up Bedrock')).toBeInTheDocument()
    expect(screen.getByText('bedrock:ListInferenceProfiles')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'AWS guide to getting started with Bedrock' })).toHaveAttribute(
      'href',
      expect.stringContaining('docs.aws.amazon.com/bedrock'),
    )
    // Nothing to send until the key is there.
    expect(screen.getByRole('button', { name: 'Verify and connect' })).toBeDisabled()
  })

  it('checks an access key, session token and region as one value, then stores it encrypted as one credential', async () => {
    await open()
    chooseBedrock()
    fireEvent.change(screen.getByLabelText('Sign in with'), { target: { value: 'access_key' } })
    expect(screen.queryByLabelText('Bedrock API key')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Access key ID'), { target: { value: ' ASIAIOSFODNN7EXAMPLE ' } })
    fireEvent.change(screen.getByLabelText('Secret access key'), { target: { value: 'wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY' } })
    fireEvent.change(screen.getByLabelText(/Session token/), { target: { value: 'FwoGZXIvYXdzEXAMPLE' } })
    // The region is searched by id, name or geography, and chosen from the list.
    const region = screen.getByRole('combobox', { name: 'Region' })
    fireEvent.change(region, { target: { value: 'eu-west-1' } })
    fireEvent.keyDown(region, { key: 'Enter' })
    expect(region).toHaveValue('Europe (Ireland) — eu-west-1')
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Verify and connect' }))
    })

    const value = JSON.stringify({
      type: 'access_key',
      accessKeyId: 'ASIAIOSFODNN7EXAMPLE',
      secretAccessKey: 'wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY',
      sessionToken: 'FwoGZXIvYXdzEXAMPLE',
      region: 'eu-west-1',
    })
    expect(sent('POST', '/api/providers/bedrock/test')[0]?.body).toEqual({ value })
    expect(await screen.findByText(/AWS Bedrock is connected. Runs now use Nova Micro/)).toBeInTheDocument()
    expect(sent('PUT', '/api/credentials/provider:bedrock')[0]?.body).toEqual({ kind: 'aws_bedrock', value })
  })

  it('shows which field the service refused, in the form’s own words, and saves nothing', async () => {
    bedrockRefusal = { field: 'secretAccessKey', problem: 'Enter the secret access key that came with the access key ID.' }
    await open()
    chooseBedrock()
    fireEvent.change(screen.getByLabelText('Bedrock API key'), { target: { value: 'ABSKexampleexampleexample' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Verify and connect' }))
    })

    expect(await screen.findAllByText(/Enter the secret access key/)).not.toHaveLength(0)
    expect(wrote()).toEqual(['POST /api/providers/bedrock/test'])
  })
})

describe('a key that does not work', () => {
  it('reports key refused in words and saves nothing', async () => {
    testAnswer = {
      result: 'rejected',
      message: 'Key refused. OpenRouter did not accept this key. Check that you copied all of it.',
    }
    await open()

    await paste('sk-wrong')

    expect(await screen.findByText(/^Key refused\./)).toBeInTheDocument()
    expect(sent('POST', '/api/providers/openrouter/test')[0]?.body).toEqual({ value: 'sk-wrong' })
    // Verified first, and then nothing else was written: no key, no switch, no policy.
    expect(wrote()).toEqual(['POST /api/providers/openrouter/test'])
    // Still the form, with the key there to correct.
    expect(screen.getByLabelText('API key')).toHaveValue('sk-wrong')
    expect(screen.getByRole('button', { name: 'Verify and connect' })).toBeInTheDocument()
  })

  it('says an account with no credit is not the same as a refused key, and saves nothing', async () => {
    testAnswer = { result: 'no_credit', message: 'OpenRouter accepted the key, but the account has no credit left.' }
    await open()

    await paste('sk-empty-account')

    expect(await screen.findByText(/no credit left/)).toBeInTheDocument()
    expect(wrote()).toEqual(['POST /api/providers/openrouter/test'])
  })

  it('says so when the provider could not be reached, and saves nothing', async () => {
    testAnswer = { result: 'network_error', message: 'We could not reach OpenRouter to check the key, so nothing was saved.' }
    await open()

    await paste('sk-whatever')

    expect(await screen.findByText(/nothing was saved/)).toBeInTheDocument()
    expect(wrote()).toEqual(['POST /api/providers/openrouter/test'])
  })

  it('shows the service’s own sentence when the check itself is refused, such as the rate limit', async () => {
    failNext.add('POST /api/providers/openrouter/test')
    await open()

    await paste('sk-whatever')

    expect(await screen.findByRole('alert')).toHaveTextContent(/Something broke on our side/)
    expect(wrote()).toEqual(['POST /api/providers/openrouter/test'])
  })
})

describe('a key that works', () => {
  it('stores it, turns the provider on, and writes a policy that stops rather than falling back to the sandbox', async () => {
    await open()

    await paste('sk-good')

    expect(await screen.findByText(/OpenRouter is connected/)).toBeInTheDocument()
    // In this order: checked, stored, switched on, then the policy.
    expect(wrote()).toEqual([
      'POST /api/providers/openrouter/test',
      'PUT /api/credentials/provider:openrouter',
      'POST /api/providers/openrouter/enable',
      'PUT /api/model-policy',
    ])
    expect(sent('PUT', '/api/credentials/provider:openrouter')[0]?.body).toEqual({ kind: 'api_key', value: 'sk-good' })
    // The cheapest model that can use tools, and not the embedding model.
    expect(sent('PUT', '/api/model-policy')[0]?.body).toEqual({
      candidates: [{ providerId: 'openrouter', modelId: 'meta-llama/llama-3.3-70b-instruct' }],
      exhaustedBehaviour: 'FAIL_CLOSED',
    })
    expect(screen.getByText(/Llama 3.3 70B/)).toBeInTheDocument()
    expect(screen.getByText(/not a fallback/)).toBeInTheDocument()
    expect(screen.getByText(/stop with an error/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open Model routing' })).toHaveAttribute('href', '/routing')
  })

  it('does not switch on a provider that is already on', async () => {
    await open()

    await paste('sk-groq', 'Groq')

    expect(await screen.findByText(/Groq is connected/)).toBeInTheDocument()
    // Groq is already on in this workspace: checked, stored, and into the policy, with no switch.
    expect(wrote()).toEqual([
      'POST /api/providers/groq/test',
      'PUT /api/credentials/provider:groq',
      'PUT /api/model-policy',
    ])
    expect(sent('PUT', '/api/model-policy')[0]?.body).toMatchObject({
      candidates: [{ providerId: 'groq', modelId: 'llama-3.1-8b-instant' }],
    })
  })
})

describe('a workspace that already has a routing policy', () => {
  beforeEach(() => {
    policy = {
      ...UNSET_POLICY,
      configured: true,
      candidates: [
        { position: 0, providerId: 'groq', modelId: 'llama-3.1-8b-instant' },
        { position: 1, providerId: 'sandbox', modelId: 'sandbox-1' },
      ],
    }
  })

  it('asks first, then puts the model ahead of the sandbox and keeps the policy’s own behaviour', async () => {
    await open()

    await paste('sk-good')

    expect(await screen.findByText(/This workspace already has a routing policy/)).toBeInTheDocument()
    // Nothing is written to the policy until the person agrees.
    expect(sent('PUT', '/api/model-policy')).toHaveLength(0)
    expect(sent('PUT', '/api/credentials/provider:openrouter')).toHaveLength(1)

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Add it to the routing policy' }))
    })

    expect(await screen.findByText(/OpenRouter is connected and Llama 3.3 70B is in the routing policy/)).toBeInTheDocument()
    expect(sent('PUT', '/api/model-policy')[0]?.body).toEqual({
      candidates: [
        { providerId: 'groq', modelId: 'llama-3.1-8b-instant' },
        { providerId: 'openrouter', modelId: 'meta-llama/llama-3.3-70b-instruct' },
        { providerId: 'sandbox', modelId: 'sandbox-1' },
      ],
      exhaustedBehaviour: 'FAIL_CLOSED',
    })
    // The sandbox is still listed, so it is still a fallback, and the sentence says so.
    expect(screen.getByText(/remains a fallback/)).toBeInTheDocument()
  })

  it('leaves the policy alone when the person says no, and says runs will not use the provider yet', async () => {
    await open()

    await paste('sk-good')
    await screen.findByText(/This workspace already has a routing policy/)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Leave the policy as it is' }))
    })

    expect(await screen.findByText(/will not use it until you add it in Model routing/)).toBeInTheDocument()
    expect(sent('PUT', '/api/model-policy')).toHaveLength(0)
  })

  it('adds nothing, and does not ask, when the provider is already in the policy', async () => {
    policy = {
      ...UNSET_POLICY,
      configured: true,
      exhaustedBehaviour: 'DEGRADE_TO_SANDBOX',
      candidates: [{ position: 0, providerId: 'openrouter', modelId: 'anthropic/claude-sonnet-4' }],
    }
    await open()

    await paste('sk-replacement')

    expect(await screen.findByText(/it was already in the routing policy/)).toBeInTheDocument()
    expect(screen.queryByText(/This workspace already has a routing policy/)).not.toBeInTheDocument()
    expect(sent('PUT', '/api/model-policy')).toHaveLength(0)
    // The key was still replaced, and the closing sentence says the sandbox is a fallback.
    expect(sent('PUT', '/api/credentials/provider:openrouter')).toHaveLength(1)
    expect(screen.getByText(/remains a fallback/)).toBeInTheDocument()
  })
})

describe('when a step fails', () => {
  it('says what went wrong and, on trying again, repeats only what is not done', async () => {
    failNext.add('POST /api/providers/openrouter/enable')
    await open()

    await paste('sk-good')

    expect(await screen.findByRole('alert')).toHaveTextContent(/Something broke on our side/)
    expect(sent('PUT', '/api/credentials/provider:openrouter')).toHaveLength(1)

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    })

    expect(await screen.findByText(/OpenRouter is connected/)).toBeInTheDocument()
    // The key was checked and stored once, and not again.
    expect(sent('POST', '/api/providers/openrouter/test')).toHaveLength(1)
    expect(sent('PUT', '/api/credentials/provider:openrouter')).toHaveLength(1)
    expect(sent('POST', '/api/providers/openrouter/enable')).toHaveLength(2)
    expect(sent('PUT', '/api/model-policy')).toHaveLength(1)
  })
})

describe('reopening', () => {
  it('starts again from an empty form, with no key left over', async () => {
    testAnswer = { result: 'rejected', message: 'Key refused. Try again.' }
    await open()
    await paste('sk-left-behind')
    await screen.findByText(/^Key refused\./)

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Reopen' }))
    })

    expect(await screen.findByLabelText('API key')).toHaveValue('')
    expect(screen.queryByText(/^Key refused\./)).not.toBeInTheDocument()
  })
})
