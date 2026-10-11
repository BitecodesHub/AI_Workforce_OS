// @find: failover model, planRoute, route events, provider chain, faults, presets, circuit open, rate limited, credential rejected, safety refusal, FAIL_CLOSED, DEGRADE_TO_SANDBOX, trace lines
// @what: Turns a provider chain and fault setup into a timed list of routing events for the failover demo.
// @flow: Used by FailoverDemo
import type { ProviderName } from '../shared/landingFacts'

/*
 * The provider-failover demo, as data.
 *
 * planRoute turns a chain configuration into a timed list of events: which node the request is
 * at, what each node shows, what the trace records, and how the run ends. The component plays
 * those events through useSequence; applyEvents folds the same events synchronously, which is how
 * the reduced-motion end state and the tests reach the result without waiting for anything.
 *
 * Every failure here is one the visitor injects. None of it describes a real provider.
 */

/* ---- Chain and faults ----------------------------------------------------------------------- */

/** The four providers of the demo chain, in order (CHAIN): OpenRouter, Groq, Google Gemini and Anthropic. */
export type ProviderId = 'openrouter' | 'groq' | 'gemini' | 'anthropic'

export type Candidate = { id: ProviderId; name: ProviderName }

export const CHAIN: ReadonlyArray<Candidate> = [
  { id: 'openrouter', name: 'OpenRouter' },
  { id: 'groq', name: 'Groq' },
  { id: 'gemini', name: 'Google Gemini' },
  { id: 'anthropic', name: 'Anthropic' },
]

/** Faults the router sees before it calls anything, so the candidate is skipped. */
/** Faults the router sees before it calls anything, so the candidate is skipped (disabled, no credential, circuit open, missing capability, context too small, over budget). */
export type SkipFault =
  | 'disabled'
  | 'no_credential'
  | 'circuit_open'
  | 'missing_capability'
  | 'context_too_small'
  | 'over_budget'

/** Faults that only show up once the candidate has been called. */
/** Faults that only appear once the candidate has been called: rate limited, credential rejected, timeout, model not found, safety refusal. */
export type CallFault = 'rate_limited' | 'credential_rejected' | 'timeout' | 'model_not_found' | 'safety_refusal'

/** Everything a candidate can be set to: healthy, a skip fault or a call fault. */
export type Fault = 'healthy' | SkipFault | CallFault

/** What happens when every candidate fails: FAIL_CLOSED fails the run, DEGRADE_TO_SANDBOX answers on the offline sandbox model and says so. */
export type Policy = 'FAIL_CLOSED' | 'DEGRADE_TO_SANDBOX'

export type Config = Record<ProviderId, Fault>

export const SKIP_FAULTS: ReadonlyArray<SkipFault> = [
  'disabled',
  'no_credential',
  'circuit_open',
  'missing_capability',
  'context_too_small',
  'over_budget',
]

export const CALL_FAULTS: ReadonlyArray<CallFault> = [
  'rate_limited',
  'credential_rejected',
  'timeout',
  'model_not_found',
  'safety_refusal',
]

/** Display labels of the faults, the short reasons used in skip trace lines (SKIP_REASON), and the option groups of each select (Answers, Skipped before calling, Fails when called). */
export const FAULT_LABEL: Record<Fault, string> = {
  healthy: 'Healthy',
  disabled: 'Disabled',
  no_credential: 'No credential',
  circuit_open: 'Circuit open',
  missing_capability: 'Missing a capability',
  context_too_small: 'Context too small',
  over_budget: 'Over budget',
  rate_limited: 'Rate limited (429)',
  credential_rejected: 'Credential rejected (401 or 403)',
  timeout: 'Timeout or 5xx',
  model_not_found: 'Model not found',
  safety_refusal: 'Safety refusal',
}

export const SKIP_REASON: Record<SkipFault, string> = {
  disabled: 'disabled',
  no_credential: 'no credential',
  circuit_open: 'circuit open',
  missing_capability: 'missing a capability',
  context_too_small: 'context too small',
  over_budget: 'over budget',
}

/** The option groups of each candidate's select, in the order they are shown. */
export const FAULT_GROUPS: ReadonlyArray<{ label: string; faults: ReadonlyArray<Fault> }> = [
  { label: 'Answers', faults: ['healthy'] },
  { label: 'Skipped before calling', faults: SKIP_FAULTS },
  { label: 'Fails when called', faults: CALL_FAULTS },
]

/** Type guard: the value is one of the known fault names. */
export function isFault(value: string): value is Fault {
  return Object.prototype.hasOwnProperty.call(FAULT_LABEL, value)
}

/** Type guard: the fault is skipped before any call is made. */
export function isSkipFault(fault: Fault): fault is SkipFault {
  return (SKIP_FAULTS as ReadonlyArray<Fault>).includes(fault)
}

/* ---- Presets ---------------------------------------------------------------------------------- */

/** Named scenarios the visitor can load in one press (throttled, bad key, provider down, safety refusal, everything down), their display order and their fault configurations. */
export type PresetId = 'throttled' | 'bad_key' | 'provider_down' | 'safety_refusal' | 'everything_down'

export const PRESET_ORDER: ReadonlyArray<PresetId> = [
  'throttled',
  'bad_key',
  'provider_down',
  'safety_refusal',
  'everything_down',
]

export const PRESETS: Record<PresetId, { label: string; config: Config }> = {
  throttled: {
    label: 'Throttled',
    config: { openrouter: 'rate_limited', groq: 'healthy', gemini: 'healthy', anthropic: 'healthy' },
  },
  bad_key: {
    label: 'Bad key',
    config: { openrouter: 'credential_rejected', groq: 'no_credential', gemini: 'healthy', anthropic: 'healthy' },
  },
  provider_down: {
    label: 'Provider down',
    config: { openrouter: 'timeout', groq: 'circuit_open', gemini: 'model_not_found', anthropic: 'healthy' },
  },
  safety_refusal: {
    label: 'Safety refusal',
    config: { openrouter: 'safety_refusal', groq: 'healthy', gemini: 'healthy', anthropic: 'healthy' },
  },
  everything_down: {
    label: 'Everything down',
    config: { openrouter: 'circuit_open', groq: 'rate_limited', gemini: 'timeout', anthropic: 'over_budget' },
  },
}

/** True when two configurations give every provider the same fault; used to show which preset is active. */
export function sameConfig(a: Config, b: Config): boolean {
  return CHAIN.every((candidate) => a[candidate.id] === b[candidate.id])
}

/**
 * What this demo remembers between requests: a candidate whose credential was rejected shows as
 * skipped the next time you send one, so you can see the consequence without re-injecting the
 * fault. A circuit breaker opening on a single failure is this demo's own simplification - the
 * real router only opens one after a run of failures crosses its threshold.
 */
// @find: applyBreakers, circuit breaker applied
export function applyBreakers(config: Config, ids: ReadonlyArray<ProviderId>): Config {
  if (ids.length === 0) return config
  const next: Config = { ...config }
  for (const id of ids) next[id] = 'circuit_open'
  return next
}

/* ---- Events ----------------------------------------------------------------------------------- */

/** What a chain node looks like at a moment of the run: idle, calling, waiting (honouring Retry-After), backing off, skipped, failed, answered, stopped (safety refusal) or not tried. */
export type NodeState =
  | 'idle'
  | 'calling'
  | 'waiting'
  | 'backing_off'
  | 'skipped'
  | 'failed'
  | 'answered'
  | 'stopped'
  | 'not_tried'

/** One line of the trace: a verb (skip, try, wait, fail, ok, stop, done or sandbox), the provider and a detail. */
export type TraceLine = {
  verb: 'skip' | 'try' | 'wait' | 'fail' | 'ok' | 'stop' | 'done' | 'sandbox'
  provider: string
  detail: string
}

/** How a run ends: answered (by whom, with failover and skip counts), stopped by a safety refusal, failed closed, or degraded to the sandbox. */
export type Outcome =
  | { kind: 'answered'; by: string; failovers: number; skips: number }
  | { kind: 'stopped'; by: string }
  | { kind: 'failed_closed' }
  | { kind: 'degraded' }

/** A timed event of the plan (at is milliseconds from the start): move the hop marker, set a node's state and label, add a trace line, start a progress bar, open a breaker, show the sandbox, or finish with an outcome. */
export type RouteEvent =
  | { at: number; kind: 'hop'; index: number }
  | { at: number; kind: 'node'; index: number; state: NodeState; label: string }
  | { at: number; kind: 'trace'; line: TraceLine }
  | { at: number; kind: 'bar'; index: number; ms: number }
  | { at: number; kind: 'breaker'; id: ProviderId }
  | { at: number; kind: 'sandbox' }
  | { at: number; kind: 'outcome'; outcome: Outcome }

/** Milliseconds. Waits are compressed: the Retry-After of 2 s plays in 1.2 s. */
/** Durations in milliseconds of the simulation: hop between nodes, skip, call, the compressed Retry-After wait and the backoff. */
export const ROUTE_TIMING = {
  hop: 320,
  skip: 240,
  call: 600,
  retryAfter: 1200,
  backoff: 800,
} as const

/** Label of a node before a run starts. */
export const IDLE_LABEL = 'Standing by'

// @find: planRoute, plan failover route, retry, skip, fail over
/** Turns a configuration and a failure policy into the timed events of one run. Walks the chain in order. A skip fault marks the node skipped and moves on. Otherwise the node is called: healthy answers and ends the run; a rate limit waits out Retry-After and fails over; a rejected credential fails over and opens that candidate's breaker; a timeout backs off then fails over; model not found fails over; a safety refusal stops the whole chain (later nodes are not tried) rather than sending the prompt to another vendor. If every candidate fails, FAIL_CLOSED ends with a failed-closed outcome and DEGRADE_TO_SANDBOX shows the sandbox and a degraded outcome. Pure: the same inputs give the same events. */
export function planRoute(config: Config, policy: Policy): RouteEvent[] {
  const events: RouteEvent[] = []
  let at = 0
  let failovers = 0
  let skips = 0

  const node = (index: number, state: NodeState, label: string) =>
    events.push({ at, kind: 'node', index, state, label })
  const trace = (verb: TraceLine['verb'], provider: string, detail: string) =>
    events.push({ at, kind: 'trace', line: { verb, provider, detail } })

  for (let index = 0; index < CHAIN.length; index += 1) {
    const candidate = CHAIN[index]
    if (!candidate) continue
    const { id, name } = candidate
    const fault = config[id]

    events.push({ at, kind: 'hop', index })
    at += ROUTE_TIMING.hop

    if (isSkipFault(fault)) {
      const reason = SKIP_REASON[fault]
      node(index, 'skipped', `Skipped: ${reason}`)
      trace('skip', id, reason)
      skips += 1
      at += ROUTE_TIMING.skip
      continue
    }

    node(index, 'calling', 'Calling')
    trace('try', id, 'called')
    at += ROUTE_TIMING.call

    switch (fault) {
      case 'healthy':
        node(index, 'answered', 'Answered')
        trace('ok', id, 'answered')
        events.push({ at, kind: 'outcome', outcome: { kind: 'answered', by: name, failovers, skips } })
        return events

      case 'rate_limited':
        node(index, 'waiting', 'Retry-After 2 s')
        events.push({ at, kind: 'bar', index, ms: ROUTE_TIMING.retryAfter })
        trace('wait', id, '429 rate limited, honoured Retry-After')
        at += ROUTE_TIMING.retryAfter
        node(index, 'failed', 'Failed over: 429')
        trace('fail', id, 'failed over')
        failovers += 1
        break

      case 'credential_rejected':
        node(index, 'failed', 'Failed: credential rejected')
        trace('fail', id, '401, credential marked invalid, failed over')
        events.push({ at, kind: 'breaker', id })
        failovers += 1
        break

      case 'timeout':
        node(index, 'backing_off', 'Backing off')
        events.push({ at, kind: 'bar', index, ms: ROUTE_TIMING.backoff })
        at += ROUTE_TIMING.backoff
        node(index, 'failed', 'Failed over: timeout')
        trace('fail', id, 'timeout, backed off, failed over')
        failovers += 1
        break

      case 'model_not_found':
        node(index, 'failed', 'Failed: model not found')
        trace('fail', id, 'model not found, marked unavailable, failed over')
        failovers += 1
        break

      case 'safety_refusal':
        node(index, 'stopped', 'Stopped: safety refusal')
        trace('stop', id, 'safety refusal, chain stopped')
        for (let later = index + 1; later < CHAIN.length; later += 1) node(later, 'not_tried', 'Not tried')
        events.push({ at, kind: 'outcome', outcome: { kind: 'stopped', by: name } })
        return events
    }
  }

  trace('done', 'chain', 'every candidate failed')
  if (policy === 'FAIL_CLOSED') {
    events.push({ at, kind: 'outcome', outcome: { kind: 'failed_closed' } })
    return events
  }
  events.push({ at, kind: 'sandbox' })
  trace('sandbox', 'sandbox', 'answered on the offline sandbox model')
  events.push({ at, kind: 'outcome', outcome: { kind: 'degraded' } })
  return events
}

/* ---- Folding events into a view --------------------------------------------------------------- */

/** The folded view of a run: each node's state and label, whether the sandbox is shown, the trace, the current hop, the active progress bar, the breakers opened during the run (the component remembers them after it ends) and the outcome. */
export type NodeView = { state: NodeState; label: string }

export type RouteView = {
  nodes: ReadonlyArray<NodeView>
  sandboxShown: boolean
  trace: ReadonlyArray<TraceLine>
  hop: number
  bar: { index: number; ms: number } | null
  /** Candidates whose breaker this run opened; the component remembers them once the run ends. */
  breakers: ReadonlyArray<ProviderId>
  outcome: Outcome | null
}

// @find: initialView, failover initial view
/** The view before any event: every node idle, an empty trace and no outcome. */
export function initialView(): RouteView {
  return {
    nodes: CHAIN.map(() => ({ state: 'idle', label: IDLE_LABEL })),
    sandboxShown: false,
    trace: [],
    hop: 0,
    bar: null,
    breakers: [],
    outcome: null,
  }
}

/** States during which a node's progress bar keeps running. */
/** Node states during which a progress bar keeps running; any other state change ends the bar. */
const HOLDING: ReadonlyArray<NodeState> = ['waiting', 'backing_off']

// @find: applyEvent, fold route event
/** Folds one event into a view without mutating it. A node event also ends that node's progress bar unless it moved into a holding state. A breaker is recorded once. The sandbox event shows the sandbox and moves the hop marker past the chain. An outcome ends the run and clears the bar. */
export function applyEvent<V extends RouteView>(view: V, event: RouteEvent): V {
  switch (event.kind) {
    case 'hop':
      return { ...view, hop: event.index }
    case 'node': {
      const nodes = view.nodes.map((current, index) =>
        index === event.index ? { state: event.state, label: event.label } : current,
      )
      const barEnds = view.bar !== null && view.bar.index === event.index && !HOLDING.includes(event.state)
      return { ...view, nodes, bar: barEnds ? null : view.bar }
    }
    case 'trace':
      return { ...view, trace: [...view.trace, event.line] }
    case 'bar':
      return { ...view, bar: { index: event.index, ms: event.ms } }
    case 'breaker':
      return view.breakers.includes(event.id) ? view : { ...view, breakers: [...view.breakers, event.id] }
    case 'sandbox':
      return { ...view, sandboxShown: true, hop: CHAIN.length }
    case 'outcome':
      return { ...view, outcome: event.outcome, bar: null }
  }
}

/** Folds events synchronously, in order. */
// @find: applyEvents, fold route events, end state
/** Folds a list of events synchronously in order. Used for the reduced-motion end state and by tests. */
export function applyEvents<V extends RouteView>(view: V, events: ReadonlyArray<RouteEvent>): V {
  return events.reduce<V>((current, event) => applyEvent(current, event), view)
}

/* ---- Words ------------------------------------------------------------------------------------ */

/** Count with a singular or plural noun. */
function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`
}

/** The sentence the status line announces when a run ends. */
// @find: summarise, failover outcome sentence
/** The sentence announced when a run ends, for each outcome kind: who answered with the failover and skip counts, a safety stop that did not try another vendor, a failed-closed run, or a degradation to the offline sandbox. */
export function summarise(outcome: Outcome): string {
  switch (outcome.kind) {
    case 'answered': {
      const { by, failovers, skips } = outcome
      const head =
        failovers > 0
          ? `Answered by ${by} after ${plural(failovers, 'failover', 'failovers')}`
          : `Answered by ${by} with no failover`
      const tail = skips > 0 ? ` and ${plural(skips, 'skip', 'skips')}` : ''
      return `${head}${tail}. Every attempt and skip is in the trace.`
    }
    case 'stopped':
      return `${outcome.by} refused on safety grounds. The chain stopped instead of sending the prompt to another vendor.`
    case 'failed_closed':
      return 'Every candidate failed. The run failed closed, and nothing was guessed.'
    case 'degraded':
      return 'Every candidate failed. The run degraded to the offline sandbox model, and the trace says so.'
  }
}
