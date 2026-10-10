// @find: test model, test now, try model, test routing, model latency, test candidate, Test button
// @what: Button and result to test a routing candidate now.
// @flow: Used by PolicyEditor.
import React from 'react'
import { Button } from '../ui'
import { checkFailureText, latencyText, useCheckProvider } from '../../lib/routingActions'
import type { ProviderCheckResult } from '../../lib/routingActions'

/*
 * "Test now": one small real call to a provider, or to one of its models, with this workspace's
 * key. A failed model is never set aside, so this is only a way to find out now instead of on the
 * next run. The outcome is said in the service's own plain sentence, with how long it took, beside
 * the button and announced politely, so somebody using a screen reader hears it without losing
 * their place.
 */

/** A duration already in a sentence: "317 ms", "1.2 s". */
const SAYS_DURATION = /\b\d+(\.\d+)?\s?(ms|s)\b/

type Outcome = { kind: 'result'; result: ProviderCheckResult } | { kind: 'failure'; text: string }

// @find: TestNow, test now, test model, test now, try model, test routing
export function TestNow({
  providerId,
  modelId,
  name,
}: {
  providerId: string
  /** Leave out to test the provider's cheapest model. */
  modelId?: string | null
  /** What is being tested, for the button's label: "OpenRouter" or "Llama 3.3 70B". */
  name: string
}) {
  const check = useCheckProvider()
  const [outcome, setOutcome] = React.useState<Outcome | null>(null)

  const run = async () => {
    setOutcome(null)
    try {
      const result = await check.mutateAsync({ providerId, ...(modelId ? { modelId } : {}) })
      setOutcome({ kind: 'result', result })
    } catch (error) {
      setOutcome({ kind: 'failure', text: checkFailureText(error) })
    }
  }

  const ok = outcome?.kind === 'result' && outcome.result.result === 'ok'
  // Added only when the service's sentence does not already say how long it took ("answered in
  // 317 ms."), so the time is never given twice.
  const latency =
    outcome?.kind === 'result' && !SAYS_DURATION.test(outcome.result.message)
      ? latencyText(outcome.result.latencyMs)
      : null

  return (
    <span className="test-now" style={{ display: 'inline-flex', flexDirection: 'column', gap: 'var(--space-1)' }}>
      <Button
        variant="outline"
        className="button-sm"
        aria-label={`Test ${name} now`}
        loading={check.isPending}
        onClick={() => void run()}
      >
        Test now
      </Button>
      <span className="caption" aria-live="polite" style={ok ? undefined : outcome ? { color: 'var(--warning-ink)' } : undefined}>
        {check.isPending
          ? `Testing ${name}…`
          : outcome?.kind === 'result'
            ? `${outcome.result.message}${latency ? ` (${latency})` : ''}`
            : outcome?.kind === 'failure'
              ? outcome.text
              : ''}
      </span>
    </span>
  )
}
