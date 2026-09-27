import { describe, expect, it } from 'vitest'
import {
  credentialState,
  isEmbeddingModel,
  liveRouting,
  providerReadiness,
  routingSummary,
} from './routing'
import type { RoutingCredential, RoutingPolicy, RoutingProvider } from './routing'

const NOW = Date.parse('2026-09-27T05:00:00Z')

const sandbox: RoutingProvider = {
  id: 'sandbox',
  displayName: 'Offline sandbox',
  kind: 'SANDBOX',
  enabled: true,
  credentialRef: null,
  credentialStatus: 'valid',
  circuitState: 'CLOSED',
}

const openrouter: RoutingProvider = {
  id: 'openrouter',
  displayName: 'OpenRouter',
  kind: 'OPENAI_COMPATIBLE',
  enabled: true,
  credentialRef: 'provider:openrouter',
  credentialStatus: 'missing',
  circuitState: 'CLOSED',
}

const groq: RoutingProvider = {
  id: 'groq',
  displayName: 'Groq',
  kind: 'OPENAI_COMPATIBLE',
  enabled: true,
  credentialRef: 'provider:groq',
  credentialStatus: 'missing',
  circuitState: 'CLOSED',
}

const openrouterKey: RoutingCredential = { ref: 'provider:openrouter', present: true }
const groqKey: RoutingCredential = { ref: 'provider:groq', present: true }

const policy = (
  candidates: Array<[string, string]>,
  exhaustedBehaviour = 'FAIL_CLOSED',
): RoutingPolicy => ({
  configured: true,
  exhaustedBehaviour,
  candidates: candidates.map(([providerId, modelId], position) => ({ providerId, modelId, position })),
})

describe('isEmbeddingModel', () => {
  it('treats a one-token output limit as an embedding model', () => {
    expect(isEmbeddingModel({ maxOutputTokens: 1 })).toBe(true)
    expect(isEmbeddingModel({ maxOutputTokens: 4096 })).toBe(false)
  })
})

describe('credentialState', () => {
  it('needs no key for a provider without a credential reference', () => {
    expect(credentialState(sandbox, [], NOW)).toBe('not_needed')
  })

  it('reads presence from the credentials list, not the seeded status', () => {
    expect(credentialState(openrouter, [], NOW)).toBe('not_stored')
    expect(credentialState(openrouter, [{ ref: 'provider:openrouter', present: false }], NOW)).toBe('not_stored')
    expect(credentialState(openrouter, [openrouterKey], NOW)).toBe('stored')
  })

  it('reports a rejected key and an expired one', () => {
    expect(credentialState({ ...openrouter, credentialStatus: 'rejected' }, [openrouterKey], NOW)).toBe('rejected')
    expect(
      credentialState(openrouter, [{ ...openrouterKey, expiresAt: '2026-09-27T04:59:00Z' }], NOW),
    ).toBe('expired')
    expect(
      credentialState(openrouter, [{ ...openrouterKey, expiresAt: '2026-10-27T00:00:00Z' }], NOW),
    ).toBe('stored')
  })

  it('stops calling a key refused once it has been used since without a new refusal', () => {
    const rejected = { ...openrouter, credentialStatus: 'rejected', credentialCheckedAt: '2026-09-27T04:00:00Z' }
    expect(credentialState(rejected, [{ ...openrouterKey, lastUsedAt: '2026-09-27T03:59:59Z' }], NOW)).toBe('rejected')
    expect(credentialState(rejected, [{ ...openrouterKey, lastUsedAt: '2026-09-27T04:30:00Z' }], NOW)).toBe('stored')
    expect(credentialState(rejected, [openrouterKey], NOW)).toBe('rejected')
  })
})

describe('providerReadiness', () => {
  it('reports each reason the router would skip a provider', () => {
    expect(providerReadiness({ ...openrouter, enabled: false }, [openrouterKey], NOW)).toEqual({
      ready: false,
      reason: 'disabled',
    })
    expect(providerReadiness(openrouter, [], NOW).reason).toBe('no_key')
    expect(providerReadiness({ ...openrouter, credentialStatus: 'rejected' }, [openrouterKey], NOW).reason).toBe(
      'rejected',
    )
    expect(providerReadiness(openrouter, [{ ...openrouterKey, expiresAt: '2026-01-01T00:00:00Z' }], NOW).reason).toBe(
      'expired',
    )
  })

  it('reads the circuit state in upper case', () => {
    expect(providerReadiness({ ...openrouter, circuitState: 'OPEN' }, [openrouterKey], NOW).reason).toBe('paused')
    expect(providerReadiness({ ...openrouter, circuitState: 'FORCED_OPEN' }, [openrouterKey], NOW).reason).toBe('paused')
    expect(providerReadiness({ ...openrouter, circuitState: 'open' }, [openrouterKey], NOW).reason).toBe('paused')
    expect(providerReadiness({ ...openrouter, circuitState: 'HALF_OPEN' }, [openrouterKey], NOW).ready).toBe(true)
  })

  it('needs no key for the sandbox', () => {
    expect(providerReadiness(sandbox, [], NOW)).toEqual({ ready: true, reason: 'ready' })
  })
})

describe('liveRouting and routingSummary', () => {
  const providers = [sandbox, openrouter, groq]

  it('explains an unset policy', () => {
    const result = liveRouting(undefined, providers, [], NOW)
    expect(result).toEqual({ live: false, reason: 'no_policy', blocked: [], fallsBackToSandbox: true })
    expect(liveRouting({ configured: false, candidates: [] }, providers, [], NOW).reason).toBe('no_policy')
    expect(liveRouting({ configured: true, candidates: [] }, providers, [], NOW).reason).toBe('no_policy')

    expect(routingSummary(result, { canManage: true })).toEqual({
      tone: 'info',
      text: 'No workspace routing policy is set, so agents without their own routing answer on the offline sandbox model.',
      linkToRouting: true,
    })
    expect(routingSummary(result, { canManage: false }).text).toBe(
      'No workspace routing policy is set, so agents without their own routing answer on the offline sandbox model. An owner or admin can connect a live model.',
    )
    expect(routingSummary(result, { canManage: false }).linkToRouting).toBe(false)
  })

  it('explains a sandbox-only chain', () => {
    const result = liveRouting(policy([['sandbox', 'sandbox-1']]), providers, [], NOW)
    expect(result.reason).toBe('sandbox_only')
    expect(routingSummary(result, { canManage: true }).text).toBe(
      'The workspace routing policy lists only the offline sandbox model.',
    )
  })

  it('names the first ready live model', () => {
    const result = liveRouting(
      policy([
        ['groq', 'llama-3.3-70b-versatile'],
        ['openrouter', 'google/gemini-2.5-flash'],
        ['sandbox', 'sandbox-1'],
      ]),
      providers,
      [openrouterKey],
      NOW,
    )
    expect(result.live).toBe(true)
    expect(result.reason).toBe('live')
    expect(result.first).toEqual({ providerName: 'OpenRouter', modelId: 'google/gemini-2.5-flash' })
    expect(result.blocked).toEqual([{ providerName: 'Groq', reason: 'no_key' }])
    expect(result.fallsBackToSandbox).toBe(true)
    expect(routingSummary(result, { canManage: true })).toEqual({
      tone: 'success',
      text: 'Runs try OpenRouter (google/gemini-2.5-flash) first, from the workspace routing policy. An agent with its own routing may use a different chain.',
      linkToRouting: false,
    })
  })

  it('says runs fail when nothing is ready and the policy fails closed', () => {
    const result = liveRouting(
      policy([
        ['openrouter', 'google/gemini-2.5-flash'],
        ['groq', 'llama-3.1-8b-instant'],
      ]),
      [sandbox, { ...openrouter, enabled: false }, { ...groq, circuitState: 'OPEN' }],
      [groqKey],
      NOW,
    )
    expect(result.reason).toBe('none_ready')
    expect(result.fallsBackToSandbox).toBe(false)
    expect(routingSummary(result, { canManage: true })).toEqual({
      tone: 'warning',
      text: 'OpenRouter is turned off and Groq is paused after failures, so runs fail until a provider is ready.',
      linkToRouting: true,
    })
  })

  it('says runs fall back when the policy degrades to the sandbox', () => {
    const result = liveRouting(
      policy([['openrouter', 'google/gemini-2.5-flash']], 'DEGRADE_TO_SANDBOX'),
      providers,
      [],
      NOW,
    )
    expect(result.reason).toBe('none_ready')
    expect(result.fallsBackToSandbox).toBe(true)
    expect(routingSummary(result, { canManage: false }).text).toBe(
      'OpenRouter is missing a key, so runs fall back to the offline sandbox model. An owner or admin can connect a live model.',
    )
  })

  it('names a rejected key and treats a sandbox later in the chain as a fallback', () => {
    const result = liveRouting(
      policy([
        ['openrouter', 'google/gemini-2.5-flash'],
        ['sandbox', 'sandbox-1'],
      ]),
      [sandbox, { ...openrouter, credentialStatus: 'rejected' }],
      [openrouterKey],
      NOW,
    )
    expect(result.reason).toBe('none_ready')
    expect(result.fallsBackToSandbox).toBe(true)
    expect(routingSummary(result, { canManage: true }).text).toBe(
      'OpenRouter is refusing its key, so runs fall back to the offline sandbox model.',
    )
  })

  it('warns when the sandbox is ahead of a ready live model', () => {
    const result = liveRouting(
      policy([
        ['sandbox', 'sandbox-1'],
        ['openrouter', 'google/gemini-2.5-flash'],
      ]),
      providers,
      [openrouterKey],
      NOW,
    )
    expect(result.live).toBe(false)
    expect(result.reason).toBe('sandbox_first')
    expect(routingSummary(result, { canManage: true }).tone).toBe('warning')
  })

  it('does not claim a provider it cannot see is merely turned off', () => {
    const result = liveRouting(policy([['bedrock', 'claude']]), providers, [], NOW)
    expect(result.blocked).toEqual([{ providerName: 'bedrock', reason: 'unavailable' }])
    expect(routingSummary(result, { canManage: true }).text).toBe(
      'Bedrock is not available in this workspace, so runs fail until a provider is ready.',
    )
  })
})
