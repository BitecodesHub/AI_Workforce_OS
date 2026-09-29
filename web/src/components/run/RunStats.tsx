import { StatRow, StatTile } from '../ui'
import { formatCount, formatElapsed, formatMoney, formatRelative } from '../../lib/format'
import type { Run, RunStep } from '../../lib/queries'
import { isSandboxStep } from './traceModel'

/** The run's own numbers: model turns, duration (still ticking if it is running), tokens, cost. */
export function RunStats({ run, steps, now }: { run: Run; steps: RunStep[] | undefined; now: number }) {
  const cost = Number(run.cost) || 0
  const modelSteps = steps?.filter((step) => step.kind === 'model_call') ?? []
  const sandboxOnly = modelSteps.length > 0 && modelSteps.every(isSandboxStep)
  const PRICED = 'At catalogue prices per million tokens'
  // A run that cost nothing is free only when the offline sandbox answered every call; otherwise
  // it is a real figure of zero. Until the steps arrive there is no telling which.
  const costTile =
    cost > 0
      ? { value: formatMoney(cost), note: PRICED }
      : steps === undefined
        ? { value: '—', note: undefined }
        : sandboxOnly
          ? { value: 'Free', note: 'Offline sandbox' }
          : { value: formatMoney(0), note: PRICED }

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
