import type { CSSProperties } from 'react'
import { Tag, Time } from '../ui'
import { formatDuration, sentenceCase } from '../../lib/format'
import { statusLabel, toolLabel } from '../../lib/labels'
import type { RunStep } from '../../lib/queries'
import { ClipAudio } from './ClipAudio'
import {
  STEP_KIND,
  clipIdOf,
  detailNumber,
  detailStrings,
  detailText,
  isInstruction,
  isSandboxStep,
  readableAttempt,
  stepDescription,
  stepHeading,
} from './traceModel'

/*
 * One step of a run's trace, in full: what kind it was, what it did, how long it took, and
 * whatever else that kind of step carries (a tool's attempts, a handoff's summary, a voice clip).
 * Used by RunDetail's full trace; RunTraceCompact renders the same steps more briefly.
 */

const STEP_STYLE: CSSProperties = {
  borderLeft: '1px solid var(--line)',
  paddingLeft: 'var(--space-5)',
  paddingBottom: 'var(--space-2)',
}

const PROSE: CSSProperties = { whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }

export function TraceStep({ step }: { step: RunStep }) {
  const time = (
    <p className="caption muted" style={{ marginTop: 'var(--space-2)', marginBottom: 0 }}>
      <Time iso={step.occurredAt} mode="absolute" />
    </p>
  )

  if (isInstruction(step)) {
    return (
      <li style={STEP_STYLE}>
        <div className="row" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-2)', flexWrap: 'wrap' }}>
          <Tag tone="blue">Instruction</Tag>
          <h3 className="section-heading">What the agent was asked</h3>
        </div>
        <p style={PROSE}>{detailText(step.detail, 'content') ?? 'The instruction was empty.'}</p>
        {time}
      </li>
    )
  }

  const kind = STEP_KIND[step.kind] ?? { tone: 'neutral' as const, label: sentenceCase(step.kind) }
  const heading = stepHeading(step)
  const status = step.kind === 'tool_call' ? detailText(step.detail, 'status') : null
  const toolStatus = status ? statusLabel('toolCall', status) : null
  // A model step's time is its own; a tool call's is recorded in its detail.
  const ms = step.durationMs > 0 ? step.durationMs : (detailNumber(step.detail, 'durationMs') ?? 0)
  const description = stepDescription(step)
  const toolCalls = detailStrings(step.detail, 'toolCalls')
  const attempts = detailStrings(step.detail, 'attempts')
  const clipId = clipIdOf(step)

  return (
    <li style={STEP_STYLE}>
      <div className="row" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-2)', flexWrap: 'wrap' }}>
        <Tag tone={kind.tone}>{kind.label}</Tag>
        {heading && heading !== kind.label && (
          <h3 className="section-heading" style={{ overflowWrap: 'anywhere' }}>
            {heading}
          </h3>
        )}
        {toolStatus && <Tag tone={toolStatus.tone}>{toolStatus.label}</Tag>}
        {isSandboxStep(step) && <Tag tone="neutral">Offline sandbox</Tag>}
        {ms > 0 && <span className="caption tabular">{formatDuration(ms)}</span>}
      </div>

      {description && (
        <p className="muted" style={{ ...PROSE, marginBottom: 'var(--space-3)' }}>
          {description}
        </p>
      )}

      {toolCalls.length > 0 && (
        <p className="caption muted" style={{ marginBottom: 'var(--space-2)' }}>
          Asked to use: {toolCalls.map((name) => toolLabel(name)).join(', ')}
        </p>
      )}

      {attempts.length > 0 && (
        <>
          <p className="caption muted" style={{ marginBottom: 'var(--space-1)' }}>
            Attempts
          </p>
          <ul className="stack" style={{ gap: 'var(--space-1)', margin: 0, paddingLeft: 'var(--space-5)' }}>
            {attempts.map((attempt, index) => (
              <li key={`${step.id}-attempt-${index}`} className="caption">
                {readableAttempt(attempt)}
              </li>
            ))}
          </ul>
        </>
      )}

      {clipId && <ClipAudio clipId={clipId} />}

      {time}
    </li>
  )
}
