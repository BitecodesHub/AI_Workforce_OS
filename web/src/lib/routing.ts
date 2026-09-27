/*
 * Whether the workspace's runs reach a live model, worked out from the same facts the router uses.
 *
 * The router (llm-core ModelRouter) skips a candidate whose provider is turned off, whose circuit
 * breaker is open, or whose credential cannot be resolved; an unset workspace policy resolves to
 * a built-in chain of the offline sandbox alone (RoutingPolicyResolver). These functions mirror
 * those rules so a screen can say which model runs will try first, or why none is usable, instead
 * of a fixed warning that is wrong as soon as a real provider is connected.
 *
 * What they cannot see: an agent's own routing policy, budget limits and context-size skips are
 * decided per run. Copy built on them says "the workspace routing policy" and never promises that
 * a run will succeed.
 *
 * Pure, and typed structurally so they accept the query types without importing them.
 */

export type RoutingModel = { maxOutputTokens: number }

export type RoutingProvider = {
  id: string
  displayName: string
  kind: string
  enabled: boolean
  credentialRef?: string | null
  credentialStatus?: string | null
  /** When the service last marked the credential rejected (or valid). */
  credentialCheckedAt?: string | null
  circuitState?: string | null
}

export type RoutingCredential = {
  ref: string
  present: boolean
  expiresAt?: string | null
  /** When the platform last read the key to make a call. */
  lastUsedAt?: string | null
}

export type RoutingPolicy = {
  configured: boolean
  exhaustedBehaviour?: string | null
  candidates: ReadonlyArray<{ providerId: string; modelId: string; position?: number }>
}

export type CredentialState = 'not_needed' | 'stored' | 'expired' | 'rejected' | 'not_stored'

export type ReadinessReason = 'ready' | 'disabled' | 'no_key' | 'rejected' | 'expired' | 'paused'

export type ProviderReadiness = { ready: boolean; reason: ReadinessReason }

export type LiveRoutingReason = 'live' | 'no_policy' | 'sandbox_only' | 'sandbox_first' | 'none_ready'

export type LiveRouting = {
  live: boolean
  reason: LiveRoutingReason
  /** The candidate runs try first: the first ready live model, or the sandbox for 'sandbox_first'. */
  first?: { providerName: string; modelId: string }
  /**
   * Live candidates the router would skip, in policy order. The reason is a ReadinessReason, or
   * 'unavailable' for a candidate whose provider is not in the list.
   */
  blocked: Array<{ providerName: string; reason: string }>
  /**
   * True when runs end on the offline sandbox model rather than failing if nothing live answers:
   * no policy, DEGRADE_TO_SANDBOX, or a ready sandbox candidate in the chain.
   */
  fallsBackToSandbox: boolean
}

export type RoutingSummary = { tone: 'info' | 'warning' | 'success'; text: string; linkToRouting: boolean }

/** Embedding models are catalogued with an output limit of one token; they cannot answer a run. */
export function isEmbeddingModel(model: RoutingModel): boolean {
  return model.maxOutputTokens <= 1
}

const isSandbox = (provider: RoutingProvider) => provider.kind.toUpperCase() === 'SANDBOX'

const time = (iso?: string | null) => (iso ? new Date(iso).getTime() : Number.NaN)

/**
 * True when the stored key has been read for a call after the last rejection, and that call did
 * not reject it again. A rejection is only ever recorded, never cleared, so without this a key
 * replaced after a rejection would read as refused for good. A key replaced but not yet used
 * still reads as refused, which is the cautious way to be wrong.
 */
function usedSinceRejection(provider: RoutingProvider, stored: RoutingCredential): boolean {
  const rejectedAt = time(provider.credentialCheckedAt)
  const usedAt = time(stored.lastUsedAt)
  return !Number.isNaN(rejectedAt) && !Number.isNaN(usedAt) && usedAt > rejectedAt
}

/**
 * The state of a provider's key. `creds` is the loaded credentials list (GET /api/credentials);
 * call this only once it has loaded, because an unloaded list would read as "not stored".
 *
 * The provider's own credentialStatus cannot say a key is present (it is seeded 'missing' and
 * only ever set to 'rejected'), so presence comes from the credentials list.
 */
export function credentialState(
  provider: RoutingProvider,
  creds: readonly RoutingCredential[],
  now: number = Date.now(),
): CredentialState {
  if (!provider.credentialRef) return 'not_needed'
  const stored = creds.find((cred) => cred.ref === provider.credentialRef && cred.present)
  if (!stored) return 'not_stored'
  if (provider.credentialStatus?.toLowerCase() === 'rejected' && !usedSinceRejection(provider, stored)) return 'rejected'
  if (stored.expiresAt) {
    const expires = new Date(stored.expiresAt).getTime()
    if (!Number.isNaN(expires) && expires <= now) return 'expired'
  }
  return 'stored'
}

/**
 * Whether the router would try this provider, and if not, the first reason it would skip it.
 * A key problem is reported before a paused circuit, because repeated failures from a bad key
 * are what pause it.
 */
export function providerReadiness(
  provider: RoutingProvider,
  creds: readonly RoutingCredential[],
  now: number = Date.now(),
): ProviderReadiness {
  if (!provider.enabled) return { ready: false, reason: 'disabled' }
  if (!isSandbox(provider)) {
    const state = credentialState(provider, creds, now)
    if (state === 'not_stored') return { ready: false, reason: 'no_key' }
    if (state === 'rejected') return { ready: false, reason: 'rejected' }
    if (state === 'expired') return { ready: false, reason: 'expired' }
  }
  const circuit = (provider.circuitState ?? '').toUpperCase()
  if (circuit === 'OPEN' || circuit === 'FORCED_OPEN') return { ready: false, reason: 'paused' }
  return { ready: true, reason: 'ready' }
}

/**
 * What the workspace routing policy does with a run: the first candidate the router would use,
 * and the live candidates it would skip on the way. An agent with its own policy is not covered.
 */
export function liveRouting(
  policy: RoutingPolicy | undefined,
  providers: readonly RoutingProvider[],
  creds: readonly RoutingCredential[],
  now: number = Date.now(),
): LiveRouting {
  // An unset policy, or one with no candidates, resolves to the built-in sandbox chain.
  if (!policy || !policy.configured || policy.candidates.length === 0) {
    return { live: false, reason: 'no_policy', blocked: [], fallsBackToSandbox: true }
  }

  const degrades = (policy.exhaustedBehaviour ?? '').toUpperCase() === 'DEGRADE_TO_SANDBOX'
  const byId = new Map(providers.map((provider) => [provider.id, provider]))
  const ordered = policy.candidates
    .map((candidate, index) => ({ candidate, order: candidate.position ?? index }))
    .sort((a, b) => a.order - b.order)
    .map(({ candidate }) => candidate)

  // A ready sandbox candidate anywhere in the chain answers once every live candidate before it
  // has been skipped, whatever the exhausted behaviour says.
  const sandboxInChain = ordered.some((candidate) => {
    const provider = byId.get(candidate.providerId)
    return provider !== undefined && isSandbox(provider) && providerReadiness(provider, creds, now).ready
  })
  const fallsBackToSandbox = degrades || sandboxInChain

  const blocked: LiveRouting['blocked'] = []
  let sandboxAhead: { providerName: string; modelId: string } | undefined
  let liveCandidates = 0

  for (const candidate of ordered) {
    const provider = byId.get(candidate.providerId)
    if (!provider) {
      // Not in this workspace's provider list, so the router cannot resolve it either.
      blocked.push({ providerName: candidate.providerId, reason: 'unavailable' })
      liveCandidates += 1
      continue
    }
    const readiness = providerReadiness(provider, creds, now)
    if (isSandbox(provider)) {
      if (readiness.ready && !sandboxAhead) sandboxAhead = { providerName: provider.displayName, modelId: candidate.modelId }
      continue
    }
    liveCandidates += 1
    if (!readiness.ready) {
      blocked.push({ providerName: provider.displayName, reason: readiness.reason })
      continue
    }
    // A ready sandbox ahead of the first ready live model answers every run before it is reached.
    if (sandboxAhead) return { live: false, reason: 'sandbox_first', first: sandboxAhead, blocked, fallsBackToSandbox }
    const first = { providerName: provider.displayName, modelId: candidate.modelId }
    return { live: true, reason: 'live', first, blocked, fallsBackToSandbox }
  }

  if (liveCandidates === 0) return { live: false, reason: 'sandbox_only', blocked, fallsBackToSandbox }
  return { live: false, reason: 'none_ready', blocked, fallsBackToSandbox }
}

const BLOCKED_PHRASE: Record<string, string> = {
  disabled: 'turned off',
  no_key: 'missing a key',
  rejected: 'refusing its key',
  expired: 'holding an expired key',
  paused: 'paused after failures',
  unavailable: 'not available in this workspace',
}

function joinPhrases(parts: string[]): string {
  if (parts.length <= 1) return parts[0] ?? ''
  return `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}`
}

/** One honest sentence about the workspace routing, for a notice on the Command Map or Model Routing. */
export function routingSummary(result: LiveRouting, options: { canManage: boolean }): RoutingSummary {
  const askAdmin = options.canManage ? '' : ' An owner or admin can connect a live model.'
  const linkToRouting = options.canManage && !result.live

  switch (result.reason) {
    case 'live':
      return {
        tone: 'success',
        text: `Runs try ${result.first?.providerName ?? 'a live model'}${result.first ? ` (${result.first.modelId})` : ''} first, from the workspace routing policy. An agent with its own routing may use a different chain.`,
        linkToRouting,
      }
    case 'no_policy':
      return {
        tone: 'info',
        text: `No workspace routing policy is set, so agents without their own routing answer on the offline sandbox model.${askAdmin}`,
        linkToRouting,
      }
    case 'sandbox_only':
      return {
        tone: 'info',
        text: `The workspace routing policy lists only the offline sandbox model.${askAdmin}`,
        linkToRouting,
      }
    case 'sandbox_first':
      return {
        tone: 'warning',
        text: `The workspace routing policy puts the offline sandbox model first, so runs answer on it before any live model is tried.${askAdmin}`,
        linkToRouting,
      }
    case 'none_ready': {
      const reasons = joinPhrases(
        result.blocked.map((entry) => `${entry.providerName} is ${BLOCKED_PHRASE[entry.reason] ?? 'unavailable'}`),
      )
      const outcome = result.fallsBackToSandbox
        ? 'fall back to the offline sandbox model'
        : 'fail until a provider is ready'
      const lead = reasons ? reasons.charAt(0).toUpperCase() + reasons.slice(1) : 'No live provider is ready'
      return {
        tone: 'warning',
        text: `${lead}, so runs ${outcome}.${askAdmin}`,
        linkToRouting,
      }
    }
  }
}
