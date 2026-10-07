import type { CSSProperties } from 'react'
import { Tag, Time } from '../ui'
import { useApproval, useMemberNamer } from '../../lib/approvalQueries'
import { formatDuration } from '../../lib/format'
import { statusLabel, toolLabel } from '../../lib/labels'
import type { RunStep } from '../../lib/queries'
import { can } from '../../lib/session'
import { ClipAudio } from './ClipAudio'
import {
  MODE_LABEL,
  citationsOf,
  clipIdOf,
  detailNumber,
  detailStrings,
  detailText,
  isInstruction,
  isSandboxStep,
  memoriesOf,
  questionItemsOf,
  readableAttempt,
  searchQueryOf,
  stepDescription,
  stepHeading,
  stepKind,
  stepMode,
} from './traceModel'

/*
 * One step of a run's trace, in full: what kind it was, what it did, how long it took, and
 * whatever else that kind of step carries (a tool's attempts, a handoff's summary, a voice clip).
 * Used by RunDetail's full trace; RunTraceCompact renders the same steps more briefly.
 */

const PROSE: CSSProperties = { whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }

/**
 * What a document search looked for and which passages it cited, each with the document, the page
 * and a short excerpt, and a way to open the source when the person may read Knowledge. A passage
 * from a restricted source is listed by name without its text.
 */
function SearchedDocuments({ step }: { step: RunStep }) {
  const query = searchQueryOf(step)
  const citations = citationsOf(step)
  const mayReadKnowledge = can('knowledge:read')
  if (!query && citations.length === 0) return null
  return (
    <div style={{ marginBottom: 'var(--space-3)' }}>
      {query && (
        <p className="caption" style={{ ...PROSE, marginBottom: 'var(--space-2)' }}>
          Searched for: {query}
        </p>
      )}
      {citations.length > 0 && (
        <ol className="stack" style={{ gap: 'var(--space-2)', margin: 0, paddingLeft: 'var(--space-5)' }}>
          {citations.map((citation) => (
            <li key={citation.number} className="caption">
              <span style={PROSE}>
                [{citation.number}] {citation.title}
                {citation.page != null ? `, page ${citation.page}` : ''}
                {citation.heading ? `, ${citation.heading}` : ''}
              </span>
              {citation.restricted && (
                <>
                  {' '}
                  <Tag tone="neutral">Restricted source</Tag>
                </>
              )}
              {citation.sourceId && mayReadKnowledge && (
                <>
                  {' '}
                  <a className="link" href={`/knowledge/${encodeURIComponent(citation.sourceId)}`}>
                    Open in Knowledge
                  </a>
                </>
              )}
              {citation.excerpt && (
                <span className="muted" style={{ ...PROSE, display: 'block', marginTop: 'var(--space-1)' }}>
                  {citation.excerpt}
                </span>
              )}
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}

/**
 * What became of an approval the run asked for, read from the approval itself: who decided, when
 * and what they said, or that nobody did. Names come from the member directory.
 */
function ApprovalDecision({ approvalId }: { approvalId: string }) {
  const approval = useApproval(approvalId, { enabled: can('approval:read') })
  const { me, nameOf } = useMemberNamer()
  const found = approval.data
  if (!found) return null
  const who = found.decidedBy ? (found.decidedBy === me ? 'You' : nameOf(found.decidedBy)) : null
  const note = found.decisionNote ? `: ${found.decisionNote}` : ''
  let line: React.ReactNode
  if (found.status === 'pending') {
    line = 'Waiting for a decision'
  } else if (found.status === 'approved') {
    line = (
      <>
        Approved by {who ?? 'someone'} at <Time iso={found.decidedAt} mode="absolute" />
        {note}
      </>
    )
  } else if (found.status === 'rejected' && found.sentBack) {
    line = (
      <>
        Sent back by {who ?? 'someone'}
        {note}
      </>
    )
  } else if (found.status === 'rejected') {
    line = (
      <>
        Rejected by {who ?? 'someone'}
        {note}
      </>
    )
  } else if (found.status === 'expired') {
    line = 'Expired without a decision'
  } else {
    line = 'Withdrawn when the run stopped'
  }
  return (
    <p className="caption" style={{ ...PROSE, marginBottom: 'var(--space-3)' }}>
      {line}
    </p>
  )
}

export function TraceStep({ step }: { step: RunStep }) {
  const time = (
    <p className="caption muted" style={{ marginTop: 'var(--space-2)', marginBottom: 0 }}>
      <Time iso={step.occurredAt} mode="absolute" />
    </p>
  )

  if (isInstruction(step)) {
    return (
      <li className="trace-step">
        <div className="trace-step-head">
          <Tag tone="blue">Instruction</Tag>
          <h3 className="section-heading">What the agent was asked</h3>
        </div>
        <p style={PROSE}>{detailText(step.detail, 'content') ?? 'The instruction was empty.'}</p>
        {time}
      </li>
    )
  }

  const kind = stepKind(step)
  const heading = stepHeading(step)
  const status = step.kind === 'tool_call' ? detailText(step.detail, 'status') : null
  const toolStatus = status ? statusLabel('toolCall', status) : null
  // Where a tool call's answer came from, so practice data is never read as a real result.
  const mode = stepMode(step)
  // A model step's time is its own; a tool call's is recorded in its detail.
  const ms = step.durationMs > 0 ? step.durationMs : (detailNumber(step.detail, 'durationMs') ?? 0)
  const description = stepDescription(step)
  const toolCalls = detailStrings(step.detail, 'toolCalls')
  const attempts = detailStrings(step.detail, 'attempts')
  const clipId = clipIdOf(step)

  return (
    <li className="trace-step">
      <div className="trace-step-head">
        <Tag tone={kind.tone}>{kind.label}</Tag>
        {heading && heading !== kind.label && (
          <h3 className="section-heading" style={{ overflowWrap: 'anywhere' }}>
            {heading}
          </h3>
        )}
        {toolStatus && <Tag tone={toolStatus.tone}>{toolStatus.label}</Tag>}
        {mode && (
          <Tag
            tone={mode === 'live' ? 'blue' : 'neutral'}
            title={mode === 'live' ? 'Answered by the connected service.' : 'Answered from practice data, not a real service.'}
          >
            {MODE_LABEL[mode]}
          </Tag>
        )}
        {isSandboxStep(step) && <Tag tone="neutral">Offline sandbox</Tag>}
        {ms > 0 && <span className="caption tabular">{formatDuration(ms)}</span>}
      </div>

      {description && (
        <p className="muted" style={{ ...PROSE, marginBottom: 'var(--space-3)' }}>
          {description}
        </p>
      )}

      <SearchedDocuments step={step} />

      {memoriesOf(step).length > 0 && (
        <ul className="stack" style={{ gap: 'var(--space-1)', margin: 0, marginBottom: 'var(--space-3)', paddingLeft: 'var(--space-5)' }}>
          {memoriesOf(step).map((note, index) => (
            <li key={index} className="caption" style={PROSE}>
              {note.content}
            </li>
          ))}
        </ul>
      )}

      {step.kind === 'approval' && detailText(step.detail, 'approvalId') && (
        <ApprovalDecision approvalId={detailText(step.detail, 'approvalId') as string} />
      )}

      {step.kind === 'question' && (
        <ul className="stack" style={{ gap: 'var(--space-3)', margin: 0, marginBottom: 'var(--space-3)', padding: 0, listStyle: 'none' }}>
          {questionItemsOf(step.detail).map((item, index) => (
            <li key={item.id ?? index}>
              <p style={{ marginBottom: 'var(--space-1)' }}>
                <Tag>{item.header}</Tag> <span style={PROSE}>{item.question}</span>
              </p>
              <ul className="stack" style={{ gap: 'var(--space-1)', margin: 0, paddingLeft: 'var(--space-5)' }}>
                {item.options.map((option) => (
                  <li key={option.label} className="caption muted">
                    {option.label}
                    {option.recommended ? ' — Recommended' : ''}
                    {option.description ? `: ${option.description}` : ''}
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ul>
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
