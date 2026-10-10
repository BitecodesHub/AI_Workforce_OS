// @find: model routing, routing policy, live model, provider readiness, credential state, API key stored expired, turn provider on off, connect provider plan, planConnect, recommendModel, routingSummary, liveRouting, Model Routing page, Command Map notice, Connect your AI dialog
// @what: Pure rules for whether runs reach a live model, provider readiness and how connecting a provider changes the routing policy.
// @flow: Used by routes/ModelRouting.tsx, CommandMap.tsx, setupQueries.ts and components/onboarding/ConnectModelDialog.tsx; mirrors llm-core ModelRouter.
/*
 * Whether the workspace's runs reach a live model, worked out from the same facts the router uses.
 *
 * The router (llm-core ModelRouter) skips a candidate whose provider is turned off or which has no
 * usable key. Nothing is ever set aside after a failure: every run tries every listed model again.
 * A key the provider once refused is still tried, so it is information, not a reason to skip. With
 * no chain for the agent or the workspace, runs fail and ask for a model to be added. These mirror
 * those rules so a screen can say which model runs will try first, or why none is usable, instead
 * of a fixed warning that is wrong as soon as a real provider is connected.
 *
 * What they cannot see: an agent's own routing policy, budget limits and context-size skips are
 * decided per run. Copy built on them says "the workspace routing policy" and never promises that
 * a run will succeed.
 *
 * Every fact here is this workspace's own. The provider list is a catalogue shared by every
 * workspace, but whether a provider is on, and what its key last did, are kept per workspace: a
 * provider turned off here is still on elsewhere, and another workspace's refused key never shows
 * here.
 *
 * Pure, and typed structurally so they accept the query types without importing them.
 */

export type RoutingModel = { maxOutputTokens: number }

export type RoutingProvider = {
  id: string
  displayName: string
  kind: string
  /** On for this workspace: the platform offers it and this workspace has not turned it off. */
  enabled: boolean
  /** False when this installation does not offer the provider at all, so it cannot be turned on here. */
  platformEnabled?: boolean | null
  credentialRef?: string | null
  /** What this workspace's own key last did: 'unknown', 'valid' or 'rejected'. */
  credentialStatus?: string | null
  /** When the service last marked this workspace's credential rejected (or valid). */
  credentialCheckedAt?: string | null
  /** No longer used: providers are never paused after failures. */
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

/**
 * 'disabled' is off in this workspace, which an owner or admin can change here; 'platform_off' is
 * not offered on this installation at all, which nobody in the workspace can change.
 */
export type ReadinessReason = 'ready' | 'disabled' | 'platform_off' | 'no_key' | 'expired'

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
   * DEGRADE_TO_SANDBOX, or a ready sandbox candidate in the chain. Never without a chain.
   */
  fallsBackToSandbox: boolean
}

export type RoutingSummary = { tone: 'info' | 'warning' | 'success'; text: string; linkToRouting: boolean }

// @find: embedding model check, hide embedding models from chat choices
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

// @find: credential state, key stored expired rejected; used by: Model Routing page, policy editor
/**
 * The state of this workspace's key for a provider. `creds` is the loaded credentials list (GET
 * /api/credentials); call this only once it has loaded, because an unloaded list would read as
 * "not stored".
 *
 * The provider's credentialStatus is this workspace's own ('unknown' until a call has used the
 * key), and it cannot say a key is present, so presence comes from the credentials list.
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

// @find: provider ready, why a provider is not usable, no key, platform off; used by: Model Routing page, policy editor, setup
/**
 * Whether the router would try this provider, and if not, the reason it would skip it. A key the
 * provider refused before is still tried on every run, so it does not make a provider unready;
 * credentialState says so separately, as information.
 */
export function providerReadiness(
  provider: RoutingProvider,
  creds: readonly RoutingCredential[],
  now: number = Date.now(),
): ProviderReadiness {
  if (!provider.enabled) return { ready: false, reason: offForPlatform(provider) ? 'platform_off' : 'disabled' }
  if (!isSandbox(provider)) {
    const state = credentialState(provider, creds, now)
    if (state === 'not_stored') return { ready: false, reason: 'no_key' }
    if (state === 'expired') return { ready: false, reason: 'expired' }
  }
  return { ready: true, reason: 'ready' }
}

// @find: is the workspace routed to a live model, first model that runs; used by: Command Map, Getting started, Setup page
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
  // An unset policy, or one with no candidates: runs without a chain of their own fail.
  if (!policy || !policy.configured || policy.candidates.length === 0) {
    return { live: false, reason: 'no_policy', blocked: [], fallsBackToSandbox: false }
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
  platform_off: 'not offered on this installation',
  no_key: 'missing a key',
  expired: 'holding an expired key',
  unavailable: 'not available in this workspace',
}

function joinPhrases(parts: string[]): string {
  if (parts.length <= 1) return parts[0] ?? ''
  return `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}`
}

// @find: routing notice sentence, no live model warning; used by: Command Map, Model Routing page, chat routing card
/** One honest sentence about the workspace routing, for a notice on the Command Map or Model Routing. */
export function routingSummary(result: LiveRouting, options: { canManage: boolean }): RoutingSummary {
  const askAdmin = options.canManage ? '' : ' An owner or admin can connect a live model.'
  const linkToRouting = options.canManage && !result.live

  switch (result.reason) {
    case 'live':
      return {
        tone: 'success',
        text: `Agents answer with a live AI model. ${result.first?.providerName ?? 'The first live model'}${result.first ? ` (${result.first.modelId})` : ''} is asked first, and the next one in Model routing takes over if it cannot answer. An agent with its own model choice may use a different one.`,
        linkToRouting,
      }
    case 'no_policy':
      return {
        tone: 'info',
        text: `No AI model is set for the workspace, so agents without routing of their own cannot answer until one is added in Model routing.${askAdmin}`,
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

/* ---- Turning a provider on or off for this workspace ----------------------------------------- */

// @find: provider off for platform, not offered by this installation; used by: Model Routing page
/**
 * True when this installation does not offer the provider at all; nobody in the workspace can
 * turn it on. Every provider is offered on a standard installation, so this is rare.
 */
export function offForPlatform(provider: RoutingProvider): boolean {
  return provider.platformEnabled === false
}

// @find: turn provider on or off button wording; used by: Model Routing page
/** The on/off button: a short label, and a full one that says the change stays in this workspace. */
export function toggleCopy(provider: RoutingProvider): { label: string; ariaLabel: string } {
  const label = provider.enabled ? 'Turn off' : 'Turn on'
  return { label, ariaLabel: `${label} ${provider.displayName} for this workspace` }
}

// @find: provider turned on or off confirmation; used by: Model Routing page
/** The confirmation after a provider was turned on or off here. */
export function toggledMessage(
  provider: RoutingProvider,
  enabled: boolean,
  context: { keyMissing?: boolean; inPolicy?: boolean } = {},
): string {
  const name = provider.displayName
  if (!enabled) return `${name} is off for this workspace. Runs here no longer try it. Other workspaces are not affected.`
  if (context.keyMissing) return `${name} is on for this workspace. Runs skip it until a usable key is stored.`
  if (context.inPolicy === false) return `${name} is on for this workspace. Add it to the routing policy to use it.`
  return `${name} is on for this workspace.`
}

export type ToggleRefusal = {
  /** The service's own sentence, which names the provider (and the agent, if any) and what to do instead. */
  text: string
  /**
   * Where the fix is made: the workspace routing policy, when turning off would leave it with
   * nothing. Null when the sentence itself is the guidance, as for an agent's own routing.
   */
  fixIn: 'routing-policy' | null
}

// @find: why a provider cannot be turned on or off; used by: Model Routing page
/**
 * A 409 from turning a provider on or off is a refusal to act on, not a failure to report: off
 * would leave the routing policy (the workspace's, or an agent's own) with nothing to use, or on
 * is not possible because this installation does not offer the provider. Anything else returns
 * null, for the usual error handling.
 */
export function toggleRefusal(error: unknown, enabling: boolean): ToggleRefusal | null {
  if (typeof error !== 'object' || error === null) return null
  const { status, message, fields } = error as { status?: unknown; message?: unknown; fields?: unknown }
  if (status !== 409 || typeof message !== 'string' || !message.trim()) return null
  // The service says whose routing would be stranded; only the workspace's is fixed on this page.
  const routing = typeof fields === 'object' && fields !== null ? (fields as { routing?: unknown }).routing : undefined
  return { text: message, fixIn: enabling || routing === 'agent' ? null : 'routing-policy' }
}

/* ---- Connecting a live model in one go ----------------------------------------------------------- */

/*
 * The "Connect your AI" dialog does what three controls on Model routing used to: store the key,
 * turn the provider on for this workspace, and put one of its models in the routing policy. These
 * are the decisions inside that, kept pure so they can be tested without a dialog: which providers
 * the simple dialog offers, which model to recommend, what to do to the policy, and the one
 * sentence that says how it ended.
 */

/** The most candidates a policy may hold (ModelPolicyController.MAX_CANDIDATES). */
export const MAX_POLICY_CANDIDATES = 10

// @find: providers offered when connecting AI; used by: Connect your AI dialog
/**
 * The providers the dialog offers. The offline sandbox needs no key. Bedrock is offered with its
 * own fields (an AWS access key or a Bedrock API key, and a region). One this installation does
 * not offer cannot be turned on, so there is no point connecting it.
 */
export function connectableProviders<T extends RoutingProvider>(providers: readonly T[]): T[] {
  return providers.filter(
    (provider) => !isSandbox(provider) && Boolean(provider.credentialRef) && !offForPlatform(provider),
  )
}

export type ConnectModel = RoutingModel & {
  providerId: string
  modelId: string
  displayName?: string
  supportsTools: boolean
  enabled: boolean
  inputCostPerMillion?: number
  outputCostPerMillion?: number
  unavailableUntil?: string | null
}

// @find: recommended model for a provider, default model choice; used by: Connect your AI dialog, model order picker
/**
 * The model to put in the policy for a provider: one that can use tools (agents cannot work
 * without them), is not an embedding model and is on. Of those the
 * cheapest to run, because a workspace connecting its first key is more likely to be spending its
 * own money than choosing for quality; the person can change it on Model routing. Undefined when
 * the provider has no such model.
 */
export function recommendModel<T extends ConnectModel>(providerId: string, models: readonly T[]): T | undefined {
  const price = (model: T) => (model.inputCostPerMillion ?? 0) + (model.outputCostPerMillion ?? 0)
  // A model that failed recently is not passed over: every model is tried again on every run.
  const eligible = models.filter(
    (model) => model.providerId === providerId && model.enabled && model.supportsTools && !isEmbeddingModel(model),
  )
  return [...eligible].sort((a, b) => price(a) - price(b) || a.modelId.localeCompare(b.modelId))[0]
}

export type PolicyCandidateInput = {
  providerId: string
  modelId: string
  temperature?: number | null
  maxOutputTokens?: number | null
}

export type PolicyInput = {
  candidates: PolicyCandidateInput[]
  exhaustedBehaviour?: 'FAIL_CLOSED' | 'DEGRADE_TO_SANDBOX'
}

export type ConnectPolicy = {
  configured: boolean
  exhaustedBehaviour?: string | null
  candidates: ReadonlyArray<{
    providerId: string
    modelId: string
    position?: number
    temperature?: number | null
    maxOutputTokens?: number | null
  }>
}

/**
 * What to do to the routing policy for the provider just connected.
 *
 * - create: the workspace has no policy, so one is written with this model alone. It stops when
 *   the model cannot answer rather than falling back to the offline sandbox, so a later outage is
 *   an error somebody sees, not placeholder text that looks like an answer.
 * - ask: a policy exists, so the person decides. The model goes in ahead of any sandbox candidate
 *   (and after the live models already listed), and the policy's own exhausted behaviour is kept.
 * - already: the provider is in the policy; nothing to add.
 * - full: the policy is at its limit and cannot take another candidate.
 */
export type ConnectPlan =
  | { kind: 'create'; input: PolicyInput; fallsBackToSandbox: false }
  | { kind: 'ask'; input: PolicyInput; position: number; fallsBackToSandbox: boolean }
  | { kind: 'already'; fallsBackToSandbox: boolean }
  | { kind: 'full'; fallsBackToSandbox: boolean }

const behaviourOf = (value?: string | null): PolicyInput['exhaustedBehaviour'] => {
  const upper = (value ?? '').toUpperCase()
  return upper === 'FAIL_CLOSED' || upper === 'DEGRADE_TO_SANDBOX' ? upper : undefined
}

// @find: plan routing change after connecting a provider, create ask already full; used by: Connect your AI dialog
export function planConnect(args: {
  policy: ConnectPolicy | undefined
  providers: readonly RoutingProvider[]
  providerId: string
  modelId: string
}): ConnectPlan {
  const { policy, providers, providerId, modelId } = args
  if (!policy || !policy.configured || policy.candidates.length === 0) {
    return {
      kind: 'create',
      input: { candidates: [{ providerId, modelId }], exhaustedBehaviour: 'FAIL_CLOSED' },
      fallsBackToSandbox: false,
    }
  }

  const sandboxIds = new Set(providers.filter(isSandbox).map((provider) => provider.id))
  const existing = [...policy.candidates]
    .map((candidate, index) => ({ candidate, order: candidate.position ?? index }))
    .sort((a, b) => a.order - b.order)
    .map(({ candidate }) => candidate)
  const degrades = (policy.exhaustedBehaviour ?? '').toUpperCase() === 'DEGRADE_TO_SANDBOX'
  const fallsBack = (candidates: ReadonlyArray<{ providerId: string }>) =>
    degrades || candidates.some((candidate) => sandboxIds.has(candidate.providerId))

  if (existing.some((candidate) => candidate.providerId === providerId)) {
    return { kind: 'already', fallsBackToSandbox: fallsBack(existing) }
  }
  if (existing.length >= MAX_POLICY_CANDIDATES) return { kind: 'full', fallsBackToSandbox: fallsBack(existing) }

  const firstSandbox = existing.findIndex((candidate) => sandboxIds.has(candidate.providerId))
  const position = firstSandbox === -1 ? existing.length : firstSandbox
  const candidates: PolicyCandidateInput[] = existing.map((candidate) => ({
    providerId: candidate.providerId,
    modelId: candidate.modelId,
    ...(candidate.temperature != null ? { temperature: candidate.temperature } : {}),
    ...(candidate.maxOutputTokens != null ? { maxOutputTokens: candidate.maxOutputTokens } : {}),
  }))
  candidates.splice(position, 0, { providerId, modelId })
  const exhaustedBehaviour = behaviourOf(policy.exhaustedBehaviour)
  return {
    kind: 'ask',
    position,
    input: { candidates, ...(exhaustedBehaviour ? { exhaustedBehaviour } : {}) },
    fallsBackToSandbox: fallsBack(candidates),
  }
}

/** How connecting ended, for the sentence the dialog shows. */
export type ConnectOutcome = 'created' | 'added' | 'already' | 'unchanged' | 'no_model'

// @find: sentence shown after connecting a provider; used by: Connect your AI dialog
/**
 * The one sentence after a provider is connected. It always says whether the offline sandbox model
 * remains a fallback, because that decides whether a later failure shows an error or placeholder
 * text, and that is the thing a person connecting a key is trying to get away from.
 */
export function connectedSentence(args: {
  providerName: string
  modelName?: string
  outcome: ConnectOutcome
  fallsBackToSandbox: boolean
}): string {
  const { providerName, modelName, outcome, fallsBackToSandbox } = args
  const fallback = fallsBackToSandbox
    ? 'The offline sandbox model remains a fallback, so a run can still end on placeholder text if no live model answers.'
    : 'The offline sandbox model is not a fallback, so if no live model answers, runs stop with an error.'
  switch (outcome) {
    case 'created':
      return `${providerName} is connected. Runs now use ${modelName ?? 'its model'}. ${fallback}`
    case 'added':
      return `${providerName} is connected and ${modelName ?? 'its model'} is in the routing policy. ${fallback}`
    case 'already':
      return `${providerName} is connected, and it was already in the routing policy. ${fallback}`
    case 'unchanged':
      return `${providerName} is connected, but the routing policy is unchanged, so runs will not use it until you add it in Model routing.`
    case 'no_model':
      return `${providerName} is connected, but none of its models can be added to the routing policy yet. Choose one in Model routing.`
  }
}
