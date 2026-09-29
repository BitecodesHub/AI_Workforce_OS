import type { CSSProperties } from 'react'
import { Tag } from '../ui'
import { formatDuration } from '../../lib/format'
import { statusLabel } from '../../lib/labels'
import { isRunActive, useRun, useRunSteps } from '../../lib/queries'
import type { RunStep } from '../../lib/queries'
import { AnswerCard } from './AnswerCard'
import { ClipAudio } from './ClipAudio'
import { WaitingForAnswer } from './WaitingForAnswer'
import { WaitingForApproval } from './WaitingForApproval'
import {
  STEP_KIND,
  answerStep,
  clipIdOf,
  detailNumber,
  detailText,
  isInstruction,
  isSandboxStep,
  stepDescription,
  stepHeading,
} from './traceModel'

/*
 * A run's trace, briefly: enough to follow along from a chat thread or an orchestrator drawer,
 * without the full page RunDetail gives it. It polls exactly like useRun/useRunSteps already do
 * while the run is active, so it updates itself; "Open full trace" is always the way to the rest.
 */

const COMPACT_STEP: CSSProperties = { borderLeft: '1px solid var(--line)', paddingLeft: 'var(--space-4)' }

function CompactStep({ step }: { step: RunStep }) {
  if (step.kind === 'handoff') {
    const from = detailText(step.detail, 'fromAgentName')
    const summary = stepDescription(step)
    return (
      <li style={COMPACT_STEP}>
        <p className="caption" style={{ marginBottom: summary ? 'var(--space-1)' : 0 }}>
          {from ? `Handed over from ${from}` : 'Handed over from an earlier step'}
        </p>
        {summary && (
          <p className="muted" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
            {summary}
          </p>
        )}
      </li>
    )
  }

  const kind = STEP_KIND[step.kind] ?? { tone: 'neutral' as const, label: step.kind }
  const heading = stepHeading(step)
  const status = step.kind === 'tool_call' ? detailText(step.detail, 'status') : null
  const toolStatus = status ? statusLabel('toolCall', status) : null
  const ms = step.durationMs > 0 ? step.durationMs : (detailNumber(step.detail, 'durationMs') ?? 0)
  const description = stepDescription(step)
  const clipId = clipIdOf(step)

  return (
    <li style={COMPACT_STEP}>
      <div className="row" style={{ gap: 'var(--space-2)', flexWrap: 'wrap', alignItems: 'baseline' }}>
        <Tag tone={kind.tone}>{kind.label}</Tag>
        {heading && heading !== kind.label && <span className="caption">{heading}</span>}
        {toolStatus && <Tag tone={toolStatus.tone}>{toolStatus.label}</Tag>}
        {isSandboxStep(step) && <Tag tone="neutral">Offline sandbox</Tag>}
        {ms > 0 && <span className="caption tabular">{formatDuration(ms)}</span>}
      </div>
      {description && (
        <p
          className="caption muted"
          style={{ marginTop: 'var(--space-1)', whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}
        >
          {description}
        </p>
      )}
      {clipId && <ClipAudio clipId={clipId} />}
    </li>
  )
}

export function RunTraceCompact({
  runId,
  headingLevel = 'h3',
  limit = 6,
  showAnswer = true,
  onGoToQuestion,
}: {
  runId: string
  /** The heading level for "What happened", so this nests correctly under whatever wraps it. */
  headingLevel?: 'h3' | 'h4'
  /** The most recent steps to show, instruction aside. The full trace is always one click away. */
  limit?: number
  /** False where the caller already shows the finished answer elsewhere, to avoid showing it twice. */
  showAnswer?: boolean
  /** Where "Go to question" (shown while the run is waiting for an answer) should take the person. */
  onGoToQuestion?: (questionId: string) => void
}) {
  const runQuery = useRun(runId)
  const run = runQuery.data
  const active = isRunActive(run)
  const stepsQuery = useRunSteps(runId, { active })
  const steps = stepsQuery.data

  if (runQuery.isLoading) return <p className="caption muted">Loading the run…</p>
  if (!run) return <p className="caption muted">This run could not be loaded.</p>

  const answer = run.status === 'completed' && steps ? answerStep(steps) : undefined
  const instruction = steps?.find(isInstruction)
  const instructionText = instruction ? detailText(instruction.detail, 'content') : null
  const shown = (steps ?? []).filter((step) => !isInstruction(step)).slice(-limit)

  const Heading = headingLevel

  return (
    <div className="stack" style={{ gap: 'var(--space-4)' }}>
      {run.status === 'waiting_approval' && <WaitingForApproval run={run} steps={steps} />}
      {run.status === 'waiting_input' && <WaitingForAnswer run={run} mode="notice" onGoToQuestion={onGoToQuestion} />}

      {showAnswer && answer && <AnswerCard step={answer} instruction={instructionText} />}

      {shown.length > 0 && (
        <div>
          <Heading className="section-heading" style={{ marginBottom: 'var(--space-2)' }}>
            What happened
          </Heading>
          <ol className="stack" style={{ gap: 'var(--space-3)', listStyle: 'none', margin: 0, padding: 0 }}>
            {shown.map((step) => (
              <CompactStep key={step.id} step={step} />
            ))}
          </ol>
        </div>
      )}

      <a className="link" href={`/runs/${runId}`}>
        Open full trace
      </a>
    </div>
  )
}
