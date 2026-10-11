// @find: failover demo, model routing, provider fallback, model router, circuit breaker, retry, back off, fail closed, degrade to sandbox, OpenRouter Groq Gemini Anthropic, trace, inject fault, FailoverDemo, #failover
// @what: The landing page demo where the visitor breaks providers in a chain and watches the router skip, retry or fail over.
// @flow: Used by DemoStage; routes planned by failoverModel
import { useCallback, useEffect, useId, useReducer, useState } from 'react'
import type { CSSProperties, Dispatch, ReactElement, RefCallback } from 'react'
import { Button, Tag } from '../../ui'
import type { TagTone } from '../../ui'
import { DemoFrame } from '../shared/DemoFrame'
import { Icon } from '../shared/Icon'
import { useLandingMotion } from '../shared/LandingRoot'
import { SegmentedControl } from '../shared/SegmentedControl'
import { SANDBOX_MODEL } from '../shared/landingFacts'
import { useInView } from '../../../hooks/useInView'
import { useSequence } from '../../../hooks/useSequence'
import type { SequenceStep } from '../../../hooks/useSequence'
import {
  CHAIN,
  FAULT_GROUPS,
  FAULT_LABEL,
  IDLE_LABEL,
  PRESETS,
  PRESET_ORDER,
  applyBreakers,
  applyEvent,
  initialView,
  isFault,
  planRoute,
  sameConfig,
  summarise,
} from './failoverModel'
import type {
  Config,
  Fault,
  NodeState,
  NodeView,
  Outcome,
  Policy,
  PresetId,
  ProviderId,
  RouteEvent,
  RouteView,
  TraceLine,
} from './failoverModel'

/*
 * Model routing, simulated.
 *
 * The visitor breaks candidates in an example chain and watches the router skip them, retry,
 * back off or fail over, with every step written to a trace. A safety refusal stops the chain
 * instead of shopping the prompt to another vendor, and a rejected credential is remembered for
 * the next request so the visitor can see the consequence without re-injecting the fault - a
 * simplification of the real breaker, which opens only after a run of failures. Nothing here is
 * sent anywhere.
 */

/* ---- State ------------------------------------------------------------------------------------- */

/** The demo's phases: ready (configure), routing (a run is playing) and done. */
type Phase = 'ready' | 'routing' | 'done'

/** The route view (nodes, trace, hop, bar, outcome) plus the phase, the fault configuration, the failure policy, the providers whose credential was rejected by the last request (breakerNote), the autoplayed flag and the live announcement. */
type State = RouteView & {
  phase: Phase
  config: Config
  policy: Policy
  breakerNote: ReadonlyArray<ProviderId>
  autoplayed: boolean
  announcement: string
}

/** Reducer events: SEND, EVENT (one planned route event), PRESET, FAULT (one provider), POLICY and RESET. */
type Action =
  | { type: 'SEND' }
  | { type: 'EVENT'; event: RouteEvent }
  | { type: 'PRESET'; id: PresetId }
  | { type: 'FAULT'; id: ProviderId; fault: Fault }
  | { type: 'POLICY'; policy: Policy }
  | { type: 'RESET' }

/** The default failure policy, fail closed. */
const DEFAULT_POLICY: Policy = 'FAIL_CLOSED'

/** The ready state: the Throttled scenario loaded, default policy, an empty trace and no breaker notes. */
function idleState(): State {
  return {
    ...initialView(),
    phase: 'ready',
    config: PRESETS.throttled.config,
    policy: DEFAULT_POLICY,
    breakerNote: [],
    autoplayed: false,
    announcement: '',
  }
}

/** An edit made after a run puts the demo back to ready with a clean chain and trace. */
/** State after the visitor changes the configuration or policy: back to ready with a clean chain and trace (autoplay is considered done). */
function edited(state: State, changes: Partial<Pick<State, 'config' | 'policy' | 'breakerNote'>>): State {
  return { ...state, ...initialView(), ...changes, phase: 'ready', autoplayed: true, announcement: '' }
}

/** Transitions of the demo. SEND starts a run; EVENT folds one event (only while routing) and, when it is the outcome, moves to done, applies opened circuit breakers to the configuration for the next request and announces the summary; PRESET, FAULT and POLICY are ignored while routing and otherwise reset to ready; RESET returns to the idle state. */
function reducer(state: State, action: Action): State {
  switch (action.type) {
    case 'SEND':
      return {
        ...state,
        ...initialView(),
        phase: 'routing',
        autoplayed: true,
        announcement: 'Routing through four candidates.',
      }

    case 'EVENT': {
      if (state.phase !== 'routing') return state
      const next = applyEvent(state, action.event)
      if (action.event.kind !== 'outcome') return next
      const opened = next.breakers.filter((id) => !state.breakerNote.includes(id))
      return {
        ...next,
        phase: 'done',
        config: applyBreakers(state.config, next.breakers),
        breakerNote: [...state.breakerNote, ...opened],
        announcement: summarise(action.event.outcome),
      }
    }

    case 'PRESET':
      if (state.phase === 'routing') return state
      return edited(state, { config: PRESETS[action.id].config, breakerNote: [] })

    case 'FAULT':
      if (state.phase === 'routing') return state
      return edited(state, {
        config: { ...state.config, [action.id]: action.fault },
        breakerNote: state.breakerNote.filter((id) => id !== action.id),
      })

    case 'POLICY':
      if (state.phase === 'routing') return state
      return edited(state, { policy: action.policy })

    case 'RESET':
      return { ...idleState(), autoplayed: true }
  }
}

/** Under reduced motion the demo opens on the resolved throttled run, folded synchronously. */
/** Initial state: ready normally; with reduced motion the demo opens on the resolved Throttled run, folded synchronously through the reducer. */
function init(reduced: boolean): State {
  const idle = idleState()
  if (!reduced) return idle
  const actions: Action[] = [
    { type: 'SEND' },
    ...planRoute(idle.config, idle.policy).map((event): Action => ({ type: 'EVENT', event })),
  ]
  return { ...actions.reduce(reducer, idle), announcement: '' }
}

/** Timed steps for one run: SEND at 0, then every planned route event at its own time (from planRoute), played with useSequence. */
function routeSteps(config: Config, policy: Policy, dispatch: Dispatch<Action>): SequenceStep[] {
  return [
    { at: 0, run: () => dispatch({ type: 'SEND' }) },
    ...planRoute(config, policy).map((event) => ({
      at: event.at,
      run: () => dispatch({ type: 'EVENT', event }),
    })),
  ]
}

/* ---- Presentation maps -------------------------------------------------------------------------- */

/** Tag tone of each node state. */
const NODE_TONE: Record<NodeState, TagTone> = {
  idle: 'neutral',
  calling: 'blue',
  waiting: 'warning',
  backing_off: 'warning',
  skipped: 'neutral',
  failed: 'danger',
  answered: 'success',
  stopped: 'danger',
  not_tried: 'neutral',
}

/** Tag tone and text for an outcome (answered by, stopped on purpose, failed closed, degraded to sandbox). */
function outcomeTag(outcome: Outcome): { tone: TagTone; text: string } {
  switch (outcome.kind) {
    case 'answered':
      return { tone: 'success', text: `Answered by ${outcome.by}` }
    case 'stopped':
      return { tone: 'warning', text: 'Stopped on purpose' }
    case 'failed_closed':
      return { tone: 'danger', text: 'Failed closed' }
    case 'degraded':
      return { tone: 'warning', text: 'Degraded to sandbox' }
  }
}

/** The two failure policies offered, with their labels. */
const POLICY_OPTIONS: ReadonlyArray<{ value: Policy; label: string }> = [
  { value: 'FAIL_CLOSED', label: 'Fail closed (default)' },
  { value: 'DEGRADE_TO_SANDBOX', label: 'Degrade to sandbox' },
]

/** Fallback node view used when a node has no state yet. */
const IDLE_NODE: NodeView = { state: 'idle', label: IDLE_LABEL }

/* ---- Trace overflow ------------------------------------------------------------------------------ */

/**
 * Whether the trace scrolls, so it can join the tab order only when there is something to scroll.
 * State changes only inside observer callbacks. New lines also scroll the trace to its end.
 */
/** Returns a ref callback and whether the list overflows. A ResizeObserver measures overflow so the trace can join the tab order only when it has something to scroll, and a MutationObserver scrolls it to its end whenever a line is added. State changes only inside observer callbacks; both observers are disconnected on cleanup. */
function useScrollableList<T extends HTMLElement>(): [RefCallback<T>, boolean] {
  const [scrollable, setScrollable] = useState(false)
  const ref = useCallback((node: T | null) => {
    if (!node) return undefined
    let known = false
    const measure = () => {
      const next = node.scrollHeight > node.clientHeight + 1
      if (next === known) return
      known = next
      setScrollable(next)
    }
    const resize = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure)
    resize?.observe(node)
    const mutation =
      typeof MutationObserver === 'undefined'
        ? null
        : new MutationObserver(() => {
            node.scrollTop = node.scrollHeight
            measure()
          })
    mutation?.observe(node, { childList: true })
    return () => {
      resize?.disconnect()
      mutation?.disconnect()
    }
  }, [])
  return [ref, scrollable]
}

/* ---- Parts ---------------------------------------------------------------------------------------- */

/** The state tag of a chain node, toned by NODE_TONE. */
function NodeTag({ node }: { node: NodeView }): ReactElement {
  return (
    <span className="lp-route-state">
      <Tag tone={NODE_TONE[node.state]}>{node.label}</Tag>
    </span>
  )
}

/** A progress bar that fills over the given milliseconds, for a node that is waiting or backing off (decorative). */
function NodeMeter({ ms }: { ms: number }): ReactElement {
  return (
    <span className="lp-route-meter" aria-hidden="true">
      <span className="lp-bar">
        <span className="lp-bar-fill" data-run="fill" style={{ '--lp-bar-ms': `${ms}ms` } as CSSProperties} />
      </span>
    </span>
  )
}

/** One line of the routing trace: the verb, the provider and the detail. */
function TraceRow({ line }: { line: TraceLine }): ReactElement {
  return (
    <li className="lp-route-line lp-anim-slide">
      <span className="lp-route-verb" data-verb={line.verb}>
        {line.verb}
      </span>
      <span className="lp-route-provider">{line.provider}</span>
      <span className="lp-route-detail">{line.detail}</span>
    </li>
  )
}

/* ---- Demo ------------------------------------------------------------------------------------------ */

// @find: FailoverDemo component, failover demo
/**
 * Landing demo 02: a simulated model-routing chain the visitor can break.
 * The visitor loads a scenario preset or sets each provider's fault with a select, chooses what happens when every candidate fails (fail closed or degrade to the offline sandbox) and presses Send a request. planRoute produces the timed events, which useSequence plays into the reducer; the trace, node tags, progress bars and a moving packet show what the router skips, retries, backs off and fails over. A safety refusal stops the chain on purpose. A rejected credential is remembered for the next request. It autoplays the Throttled scenario once when the stage is 45 percent in view.
 * Accessibility: controls use aria-disabled, not the native attribute, while routing, because disabling a focused element would blur it to the body; handlers guard against changes instead. The summary is announced through the frame's status region, and the trace scrolls and is focusable only when it overflows. Nothing is sent anywhere. Rendered by DemoStage inside DemoFrame.
 */
export function FailoverDemo(): ReactElement {
  const { reduced } = useLandingMotion()
  const [state, dispatch] = useReducer(reducer, reduced, init)
  const { play, cancel } = useSequence()
  const { ref: stageRef, inView } = useInView<HTMLFieldSetElement>({ threshold: 0.45 })
  const [traceRef, traceScrolls] = useScrollableList<HTMLOListElement>()
  const selectId = useId()

  const routing = state.phase === 'routing'

  useEffect(() => {
    if (inView && state.phase === 'ready' && !state.autoplayed) {
      play(routeSteps(PRESETS.throttled.config, DEFAULT_POLICY, dispatch))
    }
  }, [inView, state.phase, state.autoplayed, play])

  const send = () => {
    if (routing) return
    play(routeSteps(state.config, state.policy, dispatch))
  }

  const reset = () => {
    cancel()
    dispatch({ type: 'RESET' })
  }

  const loadPreset = (id: PresetId) => {
    if (routing) return
    dispatch({ type: 'PRESET', id })
  }

  const busy = routing ? { 'aria-disabled': true } : {}
  const outcome = state.outcome ? outcomeTag(state.outcome) : null

  return (
    <DemoFrame
      area="failover"
      index="02"
      name="Model routing"
      title="It keeps working when a provider does not"
      lead="Each agent has an ordered chain of provider candidates. Break one and watch the router skip it, record why, and try the next."
      footnote="Example chain. Out of the box, agents answer on the offline sandbox model. The failures are ones you inject and say nothing about any real provider."
      steps={[
        'Load a scenario such as Throttled, or set each provider yourself.',
        'Press Send a request.',
        'Read the trace: every attempt, every skip, and why.',
      ]}
      status={state.announcement}
    >
      <div className="lp-route-presets" role="group" aria-label="Load a scenario">
        {PRESET_ORDER.map((id) => (
          <button
            key={id}
            type="button"
            className="lp-chip"
            aria-pressed={sameConfig(state.config, PRESETS[id].config)}
            {...busy}
            onClick={() => loadPreset(id)}
          >
            {PRESETS[id].label}
          </button>
        ))}
      </div>

      {/* aria-disabled rather than the native disabled attribute: disabling a fieldset a
          visitor's focus is inside (reached by tabbing a select into view, which is also what
          triggers autoplay) force-blurs that control to the document body. The onChange handlers
          below guard against a change while routing instead. */}
      <fieldset ref={stageRef} className="lp-route-config" aria-disabled={routing || undefined}>
        <legend className="visually-hidden">Provider chain, tried in order</legend>
        {!reduced && (
          <span
            className="lp-route-packet"
            aria-hidden="true"
            data-active={state.phase === 'ready' ? 'false' : 'true'}
            style={{ '--lp-hop': String(state.hop) } as CSSProperties}
          />
        )}
        <ol className="lp-route-chain">
          {CHAIN.map((candidate, index) => {
            const node = state.nodes[index] ?? IDLE_NODE
            const id = `${selectId}-${candidate.id}`
            return (
              <li key={candidate.id} className="lp-route-node" data-state={node.state}>
                <span className="lp-route-index" aria-hidden="true">
                  {String(index + 1).padStart(2, '0')}
                </span>
                <span className="lp-route-who">
                  <span className="lp-route-name">{candidate.name}</span>
                  {state.breakerNote.includes(candidate.id) && (
                    <span className="caption lp-route-breaker">Credential rejected by the last request</span>
                  )}
                </span>
                <label className="visually-hidden" htmlFor={id}>
                  {`What happens at ${candidate.name}`}
                </label>
                <span className="lp-route-fault">
                  <select
                    id={id}
                    className="lp-select"
                    value={state.config[candidate.id]}
                    aria-disabled={routing || undefined}
                    onChange={(event) => {
                      // Not the native disabled attribute: this select may be the element a
                      // keyboard visitor is focused on right now, and disabling it would blur
                      // focus to the document body out from under them.
                      if (routing) return
                      const value = event.currentTarget.value
                      if (isFault(value)) dispatch({ type: 'FAULT', id: candidate.id, fault: value })
                    }}
                  >
                    {FAULT_GROUPS.map((group) => (
                      <optgroup key={group.label} label={group.label}>
                        {group.faults.map((fault) => (
                          <option key={fault} value={fault}>
                            {FAULT_LABEL[fault]}
                          </option>
                        ))}
                      </optgroup>
                    ))}
                  </select>
                </span>
                <NodeTag node={node} />
                {state.bar && state.bar.index === index && (
                  <NodeMeter key={`${state.trace.length}-${state.bar.ms}`} ms={state.bar.ms} />
                )}
              </li>
            )
          })}
          {state.sandboxShown && (
            <li className="lp-route-node lp-route-node-sandbox lp-anim-rise" data-state="answered">
              <span className="lp-route-index" aria-hidden="true">
                <Icon name="route" />
              </span>
              <span className="lp-route-who">
                <span className="lp-route-name">{SANDBOX_MODEL}</span>
              </span>
              <NodeTag node={{ state: 'answered', label: 'Answered' }} />
            </li>
          )}
        </ol>
      </fieldset>

      <div className="lp-route-outcome">
        <span className="lp-micro">Outcome</span>
        {outcome ? (
          <Tag tone={outcome.tone} withDot>
            {outcome.text}
          </Tag>
        ) : (
          <span className="caption">{routing ? 'Routing…' : 'No request sent yet'}</span>
        )}
      </div>

      <div className="lp-route-controls">
        <SegmentedControl
          legend="When every candidate fails"
          value={state.policy}
          options={POLICY_OPTIONS}
          onChange={(policy) => dispatch({ type: 'POLICY', policy })}
          disabled={routing}
        />
        <div className="lp-demo-actions">
          <Button variant="primary" icon={<Icon name="route" />} {...busy} onClick={send}>
            Send a request
          </Button>
          <Button variant="quiet" icon={<Icon name="reset" />} onClick={reset}>
            Reset
          </Button>
        </div>
      </div>

      <ol
        ref={traceRef}
        className="lp-route-trace lp-code"
        aria-label="Routing trace"
        {...(traceScrolls ? { tabIndex: 0 } : {})}
      >
        {state.trace.length === 0 ? (
          <li className="lp-route-trace-empty">Send a request to see every attempt and skip here.</li>
        ) : (
          state.trace.map((line, index) => <TraceRow key={index} line={line} />)
        )}
      </ol>
    </DemoFrame>
  )
}
