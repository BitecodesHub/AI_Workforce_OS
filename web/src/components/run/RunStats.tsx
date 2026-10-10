// @find: run stats, model turns, duration, tokens, cost, run numbers, run detail
// @what: The run's own numbers: turns, duration, tokens and cost.
// @flow: Used by RunDetail.
import { StatRow, StatTile } from '../ui'
import { formatCount, formatElapsed, formatRelative } from '../../lib/format'
import { runCost } from '../analytics/figures'
import type { Run, RunPricing, RunStep } from '../../lib/queries'
import { isSandboxStep } from './traceModel'

const COST_NOTES: Record<RunPricing, string> = {
  priced: 'At catalogue prices per million tokens',
  free: 'Every model it used is free in the catalogue',
  sandbox: 'Offline sandbox',
  unpriced: 'No catalogue price for the model it used',
  none: 'No model has answered yet',
}

// @find: RunStats, run stats, run stats, model turns, duration, tokens
/** The run's own numbers: model turns, duration (still ticking if it is running), tokens, cost. */
export function RunStats({ run, steps, now }: { run: Run; steps: RunStep[] | undefined; now: number }) {
  const modelSteps = steps?.filter((step) => step.kind === 'model_call') ?? []
  const sandboxOnly = modelSteps.length > 0 && modelSteps.every(isSandboxStep)
  // The same words as the Runs list and the agent's page. The service says what a zero cost
  // means; an older one that does not is read from the steps, once they arrive.
  const pricing: RunPricing | undefined =
    Number(run.cost) > 0
      ? 'priced'
      : (run.pricing ?? (steps === undefined ? undefined : sandboxOnly ? 'sandbox' : 'unpriced'))
  const costTile =
    pricing === undefined
      ? { value: '—', note: undefined }
      : { value: runCost({ ...run, pricing }).text, note: COST_NOTES[pricing] }

  let duration: { value: string; note: string }
  if (run.status === 'running') {
    duration = { value: formatElapsed(run.startedAt, null, now), note: 'So far; the run is still going' }
  } else if (run.status === 'waiting_approval') {
    duration = { value: 'Paused', note: `Waiting for an approval. Started ${formatRelative(run.startedAt, now)}.` }
  } else if (run.status === 'waiting_input') {
    duration = { value: 'Paused', note: `Waiting for an answer. Started ${formatRelative(run.startedAt, now)}.` }
  } else if (run.completedAt) {
    duration = { value: formatElapsed(run.startedAt, run.completedAt), note: 'From start to finish' }
  } else {
    duration = { value: '—', note: 'No finish time was recorded' }
  }

  return (
    <StatRow>
      <StatTile label="Model turns" value={formatCount(run.stepCount)} note="One per call to a model" />
      <StatTile label="Duration" value={duration.value} note={duration.note} />
      <StatTile
        label="Tokens"
        value={formatCount(run.promptTokens + run.completionTokens)}
        note={`${formatCount(run.promptTokens)} prompt and ${formatCount(run.completionTokens)} completion`}
      />
      <StatTile label="Estimated cost" value={costTile.value} {...(costTile.note ? { note: costTile.note } : {})} />
    </StatRow>
  )
}
