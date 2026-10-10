// @find: tests for failover model, planRoute, presets, provider failover, breakers, safety refusal, fail closed, degrade to sandbox
// @what: Tests route planning and presets for the failover demo.
// @flow: Covers failoverModel.ts
import { describe, expect, it } from 'vitest'
import {
  CHAIN,
  PRESETS,
  PRESET_ORDER,
  applyBreakers,
  applyEvents,
  initialView,
  planRoute,
  summarise,
} from './failoverModel'
import type { Outcome, Policy, RouteEvent, RouteView } from './failoverModel'

function outcomeOf(events: ReadonlyArray<RouteEvent>): Outcome | null {
  const last = events[events.length - 1]
  return last && last.kind === 'outcome' ? last.outcome : null
}

function run(config: (typeof PRESETS)[keyof typeof PRESETS]['config'], policy: Policy = 'FAIL_CLOSED'): {
  events: RouteEvent[]
  view: RouteView
} {
  const events = planRoute(config, policy)
  return { events, view: applyEvents(initialView(), events) }
}

function calledProviders(view: RouteView): string[] {
  return view.trace.filter((line) => line.verb === 'try').map((line) => line.provider)
}

describe('planRoute presets', () => {
  it('throttled: Groq answers after one failover', () => {
    const { events, view } = run(PRESETS.throttled.config)
    expect(outcomeOf(events)).toEqual({ kind: 'answered', by: 'Groq', failovers: 1, skips: 0 })
    expect(view.nodes.map((node) => node.state)).toEqual(['failed', 'answered', 'idle', 'idle'])
    expect(view.trace.map((line) => line.verb)).toEqual(['try', 'wait', 'fail', 'try', 'ok'])
    expect(events.some((event) => event.kind === 'bar' && event.ms === 1200)).toBe(true)
    expect(view.bar).toBeNull()
    expect(summarise(view.outcome as Outcome)).toBe(
      'Answered by Groq after 1 failover. Every attempt and skip is in the trace.',
    )
  })

  it('bad_key: Google Gemini answers, and a breaker event is emitted for OpenRouter', () => {
    const { events, view } = run(PRESETS.bad_key.config)
    expect(outcomeOf(events)).toEqual({ kind: 'answered', by: 'Google Gemini', failovers: 1, skips: 1 })
    const breakers = events.filter((event) => event.kind === 'breaker')
    expect(breakers).toEqual([expect.objectContaining({ kind: 'breaker', id: 'openrouter' })])
    expect(view.breakers).toEqual(['openrouter'])
    expect(view.nodes[1]).toEqual({ state: 'skipped', label: 'Skipped: no credential' })
    expect(summarise(view.outcome as Outcome)).toBe(
      'Answered by Google Gemini after 1 failover and 1 skip. Every attempt and skip is in the trace.',
    )
  })

  it('provider_down: Anthropic answers', () => {
    const { events, view } = run(PRESETS.provider_down.config)
    expect(outcomeOf(events)).toEqual({ kind: 'answered', by: 'Anthropic', failovers: 2, skips: 1 })
    expect(view.nodes.map((node) => node.state)).toEqual(['failed', 'skipped', 'failed', 'answered'])
    expect(view.nodes[0]?.label).toBe('Failed over: timeout')
    expect(view.nodes[2]?.label).toBe('Failed: model not found')
  })

  it('safety_refusal: the chain stops and later candidates are not tried', () => {
    const { events, view } = run(PRESETS.safety_refusal.config, 'DEGRADE_TO_SANDBOX')
    expect(outcomeOf(events)).toEqual({ kind: 'stopped', by: 'OpenRouter' })
    expect(calledProviders(view)).toEqual(['openrouter'])
    expect(view.nodes.map((node) => node.state)).toEqual(['stopped', 'not_tried', 'not_tried', 'not_tried'])
    expect(view.nodes.slice(1).every((node) => node.label === 'Not tried')).toBe(true)
    // A refusal is not a failure: the chain does not fall through to the sandbox either.
    expect(view.sandboxShown).toBe(false)
    expect(events.some((event) => event.kind === 'hop' && event.index > 0)).toBe(false)
    expect(summarise(view.outcome as Outcome)).toBe(
      'OpenRouter refused on safety grounds. The chain stopped instead of sending the prompt to another vendor.',
    )
  })

  it('everything_down: fails closed or degrades to the sandbox, depending on the policy', () => {
    const closed = run(PRESETS.everything_down.config, 'FAIL_CLOSED')
    expect(outcomeOf(closed.events)).toEqual({ kind: 'failed_closed' })
    expect(closed.view.sandboxShown).toBe(false)
    expect(closed.view.trace.at(-1)).toEqual({ verb: 'done', provider: 'chain', detail: 'every candidate failed' })

    const degraded = run(PRESETS.everything_down.config, 'DEGRADE_TO_SANDBOX')
    expect(outcomeOf(degraded.events)).toEqual({ kind: 'degraded' })
    expect(degraded.view.sandboxShown).toBe(true)
    expect(degraded.view.hop).toBe(CHAIN.length)
    expect(degraded.view.trace.slice(-2).map((line) => line.verb)).toEqual(['done', 'sandbox'])
    expect(degraded.view.nodes.map((node) => node.state)).toEqual(['skipped', 'failed', 'failed', 'skipped'])
  })

  it('keeps every event time non-decreasing, for every preset and policy', () => {
    for (const id of PRESET_ORDER) {
      for (const policy of ['FAIL_CLOSED', 'DEGRADE_TO_SANDBOX'] as const) {
        const events = planRoute(PRESETS[id].config, policy)
        expect(events.length).toBeGreaterThan(0)
        for (let index = 1; index < events.length; index += 1) {
          const previous = events[index - 1]
          const current = events[index]
          expect(current && previous && current.at >= previous.at, `${id}/${policy} at event ${index}`).toBe(true)
        }
        expect(events.at(-1)?.kind).toBe('outcome')
      }
    }
  })

  it('gives a node state and its trace line the same time', () => {
    const events = planRoute(PRESETS.provider_down.config, 'FAIL_CLOSED')
    for (const [index, event] of events.entries()) {
      if (event.kind !== 'trace') continue
      const node = events[index - 1]
      if (node?.kind === 'node') expect(node.at).toBe(event.at)
    }
  })
})

describe('breaker memory', () => {
  it('skips a candidate as circuit open on the request after its 401', () => {
    const first = run(PRESETS.bad_key.config)
    const remembered = applyBreakers(PRESETS.bad_key.config, first.view.breakers)
    expect(remembered.openrouter).toBe('circuit_open')

    const second = run(remembered)
    expect(second.view.nodes[0]).toEqual({ state: 'skipped', label: 'Skipped: circuit open' })
    expect(calledProviders(second.view)).toEqual(['gemini'])
    expect(outcomeOf(second.events)).toEqual({ kind: 'answered', by: 'Google Gemini', failovers: 0, skips: 2 })
    expect(summarise(second.view.outcome as Outcome)).toBe(
      'Answered by Google Gemini with no failover and 2 skips. Every attempt and skip is in the trace.',
    )
  })
})
