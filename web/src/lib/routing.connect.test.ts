import { describe, expect, it } from 'vitest'
import {
  connectableProviders,
  connectedSentence,
  planConnect,
  recommendModel,
  type ConnectModel,
  type ConnectPolicy,
} from './routing'
import type { RoutingProvider } from './routing'

/*
 * What the Connect your AI dialog decides before it touches anything: which providers it offers,
 * which model it recommends, what it does to the routing policy and what it tells the person.
 */

const provider = (id: string, kind: string, extra: Partial<RoutingProvider> = {}): RoutingProvider => ({
  id,
  displayName: id,
  kind,
  enabled: true,
  credentialRef: kind === 'SANDBOX' ? null : `provider:${id}`,
  ...extra,
})

const PROVIDERS = [
  provider('openrouter', 'OPENAI_COMPATIBLE'),
  provider('groq', 'OPENAI_COMPATIBLE'),
  provider('bedrock', 'BEDROCK'),
  provider('sandbox', 'SANDBOX'),
]

const model = (providerId: string, modelId: string, extra: Partial<ConnectModel> = {}): ConnectModel => ({
  providerId,
  modelId,
  displayName: modelId,
  maxOutputTokens: 4096,
  supportsTools: true,
  enabled: true,
  inputCostPerMillion: 1,
  outputCostPerMillion: 1,
  ...extra,
})

describe('connectableProviders', () => {
  it('offers live providers with one key, and neither Bedrock nor the offline sandbox', () => {
    expect(connectableProviders(PROVIDERS).map((entry) => entry.id)).toEqual(['openrouter', 'groq'])
  })

  it('leaves out a provider this installation does not offer, and one with no credential to store', () => {
    const list = [
      provider('withdrawn', 'OPENAI_COMPATIBLE', { platformEnabled: false }),
      provider('keyless', 'OPENAI_COMPATIBLE', { credentialRef: null }),
      provider('openai', 'OPENAI_COMPATIBLE'),
    ]
    expect(connectableProviders(list).map((entry) => entry.id)).toEqual(['openai'])
  })
})

describe('recommendModel', () => {
  const NOW = Date.parse('2026-10-04T10:00:00Z')

  it('picks the cheapest model that can use tools, is not an embedding model, is on and is available', () => {
    const models = [
      model('openrouter', 'pricey', { inputCostPerMillion: 3, outputCostPerMillion: 15 }),
      model('openrouter', 'cheap', { inputCostPerMillion: 0.1, outputCostPerMillion: 0.3 }),
      // Cheaper still, but each one is disqualified for a different reason.
      model('openrouter', 'embedder', { maxOutputTokens: 1, inputCostPerMillion: 0, outputCostPerMillion: 0 }),
      model('openrouter', 'no-tools', { supportsTools: false, inputCostPerMillion: 0, outputCostPerMillion: 0 }),
      model('openrouter', 'switched-off', { enabled: false, inputCostPerMillion: 0, outputCostPerMillion: 0 }),
      model('openrouter', 'set-aside', {
        unavailableUntil: '2026-10-04T11:00:00Z',
        inputCostPerMillion: 0,
        outputCostPerMillion: 0,
      }),
      model('groq', 'other-provider', { inputCostPerMillion: 0, outputCostPerMillion: 0 }),
    ]
    expect(recommendModel('openrouter', models, NOW)?.modelId).toBe('cheap')
  })

  it('takes a model back once its set-aside time has passed', () => {
    const models = [model('openrouter', 'back', { unavailableUntil: '2026-10-04T09:00:00Z' })]
    expect(recommendModel('openrouter', models, NOW)?.modelId).toBe('back')
  })

  it('breaks a tie by model name, so the choice does not change between loads', () => {
    const models = [model('openrouter', 'b-model'), model('openrouter', 'a-model')]
    expect(recommendModel('openrouter', models, NOW)?.modelId).toBe('a-model')
  })

  it('finds nothing when the provider has no usable model', () => {
    expect(recommendModel('openrouter', [model('openrouter', 'x', { supportsTools: false })], NOW)).toBeUndefined()
    expect(recommendModel('openrouter', [], NOW)).toBeUndefined()
  })
})

describe('planConnect', () => {
  const plan = (policy: ConnectPolicy | undefined, providerId = 'openrouter') =>
    planConnect({ policy, providers: PROVIDERS, providerId, modelId: 'cheap' })

  it('writes a new policy when there is none, that stops rather than falling back to the sandbox', () => {
    for (const none of [undefined, { configured: false, candidates: [] }]) {
      expect(plan(none)).toEqual({
        kind: 'create',
        input: { candidates: [{ providerId: 'openrouter', modelId: 'cheap' }], exhaustedBehaviour: 'FAIL_CLOSED' },
        fallsBackToSandbox: false,
      })
    }
  })

  it('asks before changing a policy that exists, and puts the model ahead of the sandbox', () => {
    const result = plan({
      configured: true,
      exhaustedBehaviour: 'FAIL_CLOSED',
      candidates: [
        { providerId: 'groq', modelId: 'llama', position: 0, temperature: 0.2 },
        { providerId: 'sandbox', modelId: 'sandbox-1', position: 1 },
      ],
    })

    expect(result.kind).toBe('ask')
    if (result.kind !== 'ask') return
    expect(result.position).toBe(1)
    expect(result.input.candidates).toEqual([
      { providerId: 'groq', modelId: 'llama', temperature: 0.2 },
      { providerId: 'openrouter', modelId: 'cheap' },
      { providerId: 'sandbox', modelId: 'sandbox-1' },
    ])
    // The policy's own behaviour is kept, and the sandbox it still lists stays a fallback.
    expect(result.input.exhaustedBehaviour).toBe('FAIL_CLOSED')
    expect(result.fallsBackToSandbox).toBe(true)
  })

  it('puts the model last when no sandbox is listed, and says the sandbox is not a fallback', () => {
    const result = plan({
      configured: true,
      exhaustedBehaviour: 'FAIL_CLOSED',
      candidates: [{ providerId: 'groq', modelId: 'llama' }],
    })
    expect(result).toMatchObject({ kind: 'ask', position: 1, fallsBackToSandbox: false })
  })

  it('reads the order from position, not from the order the list arrived in', () => {
    const result = plan({
      configured: true,
      candidates: [
        { providerId: 'sandbox', modelId: 'sandbox-1', position: 1 },
        { providerId: 'groq', modelId: 'llama', position: 0 },
      ],
    })
    expect(result.kind).toBe('ask')
    if (result.kind !== 'ask') return
    expect(result.input.candidates.map((entry) => entry.providerId)).toEqual(['groq', 'openrouter', 'sandbox'])
  })

  it('keeps DEGRADE_TO_SANDBOX, and counts it as a sandbox fallback even with no sandbox listed', () => {
    const result = plan({
      configured: true,
      exhaustedBehaviour: 'DEGRADE_TO_SANDBOX',
      candidates: [{ providerId: 'groq', modelId: 'llama' }],
    })
    expect(result).toMatchObject({ kind: 'ask', fallsBackToSandbox: true })
    if (result.kind === 'ask') expect(result.input.exhaustedBehaviour).toBe('DEGRADE_TO_SANDBOX')
  })

  it('adds nothing when the provider is already in the policy', () => {
    expect(
      plan({ configured: true, exhaustedBehaviour: 'FAIL_CLOSED', candidates: [{ providerId: 'openrouter', modelId: 'old' }] }),
    ).toEqual({ kind: 'already', fallsBackToSandbox: false })
  })

  it('adds nothing when the policy already holds ten candidates', () => {
    const full = Array.from({ length: 10 }, (_, index) => ({ providerId: 'groq', modelId: `m${index}` }))
    expect(plan({ configured: true, candidates: full })).toMatchObject({ kind: 'full' })
  })
})

describe('connectedSentence', () => {
  it('says the sandbox is not a fallback for a new policy, and what happens instead', () => {
    const text = connectedSentence({
      providerName: 'OpenRouter',
      modelName: 'Llama 3.3',
      outcome: 'created',
      fallsBackToSandbox: false,
    })
    expect(text).toContain('OpenRouter is connected')
    expect(text).toContain('Llama 3.3')
    expect(text).toContain('not a fallback')
    expect(text).toContain('stop with an error')
  })

  it('says the sandbox remains a fallback when it does', () => {
    const text = connectedSentence({
      providerName: 'Groq',
      modelName: 'Llama',
      outcome: 'added',
      fallsBackToSandbox: true,
    })
    expect(text).toContain('remains a fallback')
    expect(text).toContain('placeholder text')
  })

  it('tells somebody who left the policy alone that runs will not use the provider yet', () => {
    expect(
      connectedSentence({ providerName: 'Groq', modelName: 'Llama', outcome: 'unchanged', fallsBackToSandbox: false }),
    ).toContain('will not use it until you add it in Model routing')
  })

  it('says so when no model could be added', () => {
    expect(connectedSentence({ providerName: 'Groq', outcome: 'no_model', fallsBackToSandbox: false })).toContain(
      'none of its models',
    )
  })

  it('writes every sentence without an exclamation mark', () => {
    for (const outcome of ['created', 'added', 'already', 'unchanged', 'no_model'] as const) {
      expect(connectedSentence({ providerName: 'P', modelName: 'M', outcome, fallsBackToSandbox: true })).not.toContain('!')
    }
  })
})
