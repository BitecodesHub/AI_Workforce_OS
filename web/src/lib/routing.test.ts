import { describe, expect, it } from 'vitest'
import {
  credentialState,
  isEmbeddingModel,
  liveRouting,
  offForPlatform,
  providerReadiness,
  routingSummary,
  toggleCopy,
  toggledMessage,
  toggleRefusal,
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

  it("reads this workspace's own status, which starts as unknown rather than missing", () => {
    expect(credentialState({ ...openrouter, credentialStatus: 'unknown' }, [openrouterKey], NOW)).toBe('stored')
    expect(credentialState({ ...openrouter, credentialStatus: 'unknown' }, [], NOW)).toBe('not_stored')
    expect(credentialState({ ...openrouter, credentialStatus: 'valid' }, [openrouterKey], NOW)).toBe('stored')
    expect(credentialState({ ...sandbox, credentialStatus: 'unknown' }, [], NOW)).toBe('not_needed')
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

  it('tells a provider this installation does not offer apart from one this workspace turned off', () => {
    expect(providerReadiness({ ...openrouter, enabled: false, platformEnabled: false }, [openrouterKey], NOW)).toEqual({
      ready: false,
      reason: 'platform_off',
    })
    expect(providerReadiness({ ...openrouter, enabled: false, platformEnabled: true }, [openrouterKey], NOW).reason).toBe(
      'disabled',
    )
    // An older service that does not send the field reads as turned off here.
    expect(providerReadiness({ ...openrouter, enabled: false, platformEnabled: null }, [openrouterKey], NOW).reason).toBe(
      'disabled',
    )
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

  it('says a provider this installation does not offer is not offered, rather than turned off', () => {
    const result = liveRouting(
      policy([['openrouter', 'google/gemini-2.5-flash']]),
      [sandbox, { ...openrouter, enabled: false, platformEnabled: false }],
      [openrouterKey],
      NOW,
    )
    expect(result.blocked).toEqual([{ providerName: 'OpenRouter', reason: 'platform_off' }])
    expect(routingSummary(result, { canManage: true }).text).toBe(
      'OpenRouter is not offered on this installation, so runs fail until a provider is ready.',
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

describe('turning a provider on or off for this workspace', () => {
  it('says the change applies to this workspace', () => {
    expect(toggleCopy(openrouter)).toEqual({ label: 'Turn off', ariaLabel: 'Turn off OpenRouter for this workspace' })
    expect(toggleCopy({ ...groq, enabled: false })).toEqual({
      label: 'Turn on',
      ariaLabel: 'Turn on Groq for this workspace',
    })
  })

  it('confirms each outcome without claiming to reach other workspaces', () => {
    expect(toggledMessage(openrouter, false)).toBe(
      'OpenRouter is off for this workspace. Runs here no longer try it. Other workspaces are not affected.',
    )
    expect(toggledMessage(openrouter, true, { keyMissing: true, inPolicy: true })).toBe(
      'OpenRouter is on for this workspace. Runs skip it until a usable key is stored.',
    )
    expect(toggledMessage(openrouter, true, { inPolicy: false })).toBe(
      'OpenRouter is on for this workspace. Add it to the routing policy to use it.',
    )
    expect(toggledMessage(openrouter, true, { inPolicy: true })).toBe('OpenRouter is on for this workspace.')
  })

  it('tells a provider the installation does not offer apart from one this workspace turned off', () => {
    expect(offForPlatform({ ...groq, enabled: false, platformEnabled: false })).toBe(true)
    expect(offForPlatform({ ...groq, enabled: false, platformEnabled: true })).toBe(false)
    // An older service that does not send the field never hides the toggle.
    expect(offForPlatform({ ...groq, enabled: false })).toBe(false)
    expect(offForPlatform({ ...groq, enabled: false, platformEnabled: null })).toBe(false)
  })

  it('turns a 409 into the service sentence, pointing a refused turn-off at the routing policy', () => {
    const conflict = {
      status: 409,
      message: "Turning off OpenRouter would leave this workspace's routing policy with no provider that is on.",
    }
    expect(toggleRefusal(conflict, false)).toEqual({ text: conflict.message, fixIn: 'routing-policy' })
    expect(toggleRefusal({ ...conflict, fields: { routing: 'workspace' } }, false)).toEqual({
      text: conflict.message,
      fixIn: 'routing-policy',
    })
    expect(
      toggleRefusal({ status: 409, message: 'AWS Bedrock is not available yet. Contact support to have it offered.' }, true),
    ).toEqual({ text: 'AWS Bedrock is not available yet. Contact support to have it offered.', fixIn: null })
  })

  it('names no link for an agent whose own routing would be stranded, since the fix is not on this page', () => {
    const agentConflict = {
      status: 409,
      message: "Turning off OpenRouter would leave Ava's own routing with no provider that is on, so its runs would stop.",
      fields: { routing: 'agent' },
    }
    expect(toggleRefusal(agentConflict, false)).toEqual({ text: agentConflict.message, fixIn: null })
  })

  it('leaves every other failure to the usual error handling', () => {
    expect(toggleRefusal({ status: 404, message: 'Not found.' }, false)).toBeNull()
    expect(toggleRefusal({ status: 500, message: 'Something broke.' }, false)).toBeNull()
    expect(toggleRefusal({ status: 409, message: '   ' }, false)).toBeNull()
    expect(toggleRefusal(new Error('network'), false)).toBeNull()
    expect(toggleRefusal(null, false)).toBeNull()
    expect(toggleRefusal('409', false)).toBeNull()
  })
})
