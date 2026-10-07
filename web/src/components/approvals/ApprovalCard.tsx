import { useState } from 'react'
import { Button, Card, Eyebrow, Tag, Time } from '../ui'
import type { TagTone } from '../ui'
import { Collapsible } from '../ui/Collapsible'
import { PayloadPreview } from './PayloadPreview'
import { approvalAnchor, contextLine, readableSummary } from '../../lib/approvals'
import type { ApprovalItem } from '../../lib/approvalQueries'
import { actionClassLabel, categoryTone, statusLabel, toolLabel } from '../../lib/labels'
import type { Agent } from '../../lib/queries'
import { can } from '../../lib/session'

/*
 * One approval as a person reads it: a request waiting for a decision, with the work and the
 * person behind it and the buttons to decide it, and a request already decided, with who decided
 * it, when and why. Both carry the anchor a run trace links to.
 */

const ACTION_TONE: Record<string, TagTone> = { OUTBOUND: 'warning', DESTRUCTIVE: 'danger', WRITE: 'blue' }

/** A request waiting for a decision. */
export function ApprovalCard({
  approval,
  agent,
  agentsKnown,
  me,
  nameOf,
  onApprove,
  onReject,
  onSendBack,
  isApproving,
  isRejecting,
  isSendingBack = false,
}: {
  approval: ApprovalItem
  agent: Agent | undefined
  /** Until the agent list has arrived, a missing name is not yet an unknown agent. */
  agentsKnown: boolean
  me: string | null
  nameOf: (userId: string) => string
  onApprove: () => void
  onReject: () => void
  onSendBack?: () => void
  isApproving: boolean
  isRejecting: boolean
  isSendingBack?: boolean
}) {
  const actionClass = approval.actionClass.toUpperCase()
  const [askedOpen, setAskedOpen] = useState(false)
  // The person asked for this work and the workspace wants a second pair of eyes on it: say so,
  // rather than leaving them wondering where the button went.
  const needsSomeoneElse = !approval.canDecide && can('approval:decide') && me != null && approval.requestedBy === me

  return (
    // The id lets a run trace link straight to this card; tabIndex lets the page move focus here
    // when it arrives by that link.
    <div id={approvalAnchor(approval.id)} tabIndex={-1} style={{ scrollMarginTop: 'var(--space-7)' }}>
      <Card as="article">
        <Eyebrow>{toolLabel(approval.tool)}</Eyebrow>

        <div
          className="row"
          style={{
            justifyContent: 'space-between',
            alignItems: 'flex-start',
            flexWrap: 'wrap',
            gap: 'var(--space-3) var(--space-4)',
            marginBottom: 'var(--space-3)',
          }}
        >
          <h2 className="section-heading" style={{ overflowWrap: 'anywhere' }}>
            {readableSummary(approval)}
          </h2>
          <Tag tone={ACTION_TONE[actionClass] ?? 'neutral'}>{actionClassLabel(approval.actionClass)}</Tag>
        </div>

        <p style={{ marginBottom: 'var(--space-3)', overflowWrap: 'anywhere' }}>{contextLine(approval, me, nameOf)}</p>

        <div
          className="row"
          style={{ flexWrap: 'wrap', gap: 'var(--space-2) var(--space-4)', marginBottom: 'var(--space-5)' }}
        >
          {(agent || agentsKnown) && (
            <Tag tone={categoryTone(agent?.category)} withDot>
              {agent?.name ?? 'Unknown agent'}
            </Tag>
          )}
          <span className="caption">
            Requested <Time iso={approval.requestedAt} />
          </span>
          <span className="caption">
            Expires <Time iso={approval.expiresAt} />
          </span>
        </div>

        {approval.taskInstruction && (
          <div style={{ marginBottom: 'var(--space-4)' }}>
            <Collapsible title="What the agent was asked to do" open={askedOpen} onToggle={setAskedOpen} headingLevel="p">
              <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', margin: 'var(--space-2) 0 0' }}>
                {approval.taskInstruction}
              </p>
            </Collapsible>
          </div>
        )}

        <div style={{ marginBottom: 'var(--space-5)' }}>
          <PayloadPreview payload={approval.payload} actionClass={approval.actionClass} />
        </div>

        <div className="row" style={{ flexWrap: 'wrap', gap: 'var(--space-3)' }}>
          {approval.canDecide && (
            <>
              <Button onClick={onApprove} loading={isApproving} disabled={isRejecting || isSendingBack}>
                Approve
              </Button>
              {onSendBack && (
                <Button
                  variant="outline"
                  onClick={onSendBack}
                  loading={isSendingBack}
                  disabled={isApproving || isRejecting}
                >
                  Send back with feedback
                </Button>
              )}
              <Button
                variant="outline"
                onClick={onReject}
                loading={isRejecting}
                disabled={isApproving || isSendingBack}
              >
                Reject and stop
              </Button>
            </>
          )}
          <a className="button button-outline" href={`/runs/${approval.runId}`}>
            View the run
          </a>
        </div>
        {approval.canDecide && (
          <p className="caption muted" style={{ marginTop: 'var(--space-3)', maxWidth: '70ch' }}>
            Approve lets the agent carry it out exactly as written. Send back with feedback tells the agent what to
            change and lets it ask again. Reject and stop ends the run.
          </p>
        )}
        {needsSomeoneElse && (
          <p className="caption muted" style={{ marginTop: 'var(--space-3)' }}>
            You asked for this work, so someone else needs to decide it.
          </p>
        )}
      </Card>
    </div>
  )
}

/** What happened to a request that is no longer waiting, and when. */
function DecisionLine({
  approval,
  me,
  nameOf,
}: {
  approval: ApprovalItem
  me: string | null
  nameOf: (userId: string) => string
}) {
  if (approval.status === 'expired') {
    return (
      <span>
        Expired without a decision <Time iso={approval.decidedAt} />. Its run was stopped.
      </span>
    )
  }
  if (approval.status === 'cancelled') {
    return (
      <span>
        Withdrawn <Time iso={approval.decidedAt} />, when its run was stopped.
      </span>
    )
  }
  const who = approval.decidedBy ? (approval.decidedBy === me ? 'You' : nameOf(approval.decidedBy)) : 'Someone'
  return (
    <span>
      {approval.sentBack ? 'Sent back' : statusLabel('approval', approval.status).label} by {who}{' '}
      <Time iso={approval.decidedAt} />
    </span>
  )
}

/** A request that was decided, expired or withdrawn, with the record of what happened to it. */
export function DecidedCard({
  approval,
  agent,
  agentsKnown,
  me,
  nameOf,
}: {
  approval: ApprovalItem
  agent: Agent | undefined
  agentsKnown: boolean
  me: string | null
  nameOf: (userId: string) => string
}) {
  const [open, setOpen] = useState(false)
  const status = statusLabel('approval', approval.status)
  return (
    <div id={approvalAnchor(approval.id)} tabIndex={-1} style={{ scrollMarginTop: 'var(--space-7)' }}>
      <Card as="article">
        <Eyebrow>{toolLabel(approval.tool)}</Eyebrow>
        <div
          className="row"
          style={{ flexWrap: 'wrap', alignItems: 'flex-start', gap: 'var(--space-2) var(--space-4)', marginBottom: 'var(--space-3)' }}
        >
          <Tag tone={approval.sentBack ? 'warning' : status.tone}>{approval.sentBack ? 'Sent back' : status.label}</Tag>
          <h2 className="section-heading" style={{ overflowWrap: 'anywhere' }}>
            {readableSummary(approval)}
          </h2>
        </div>
        <p style={{ marginBottom: 'var(--space-2)', overflowWrap: 'anywhere' }}>{contextLine(approval, me, nameOf)}</p>
        <div
          className="row"
          style={{ flexWrap: 'wrap', gap: 'var(--space-2) var(--space-4)', marginBottom: 'var(--space-3)' }}
        >
          {(agent || agentsKnown) && (
            <Tag tone={categoryTone(agent?.category)} withDot>
              {agent?.name ?? 'Unknown agent'}
            </Tag>
          )}
          <span className="caption">
            <DecisionLine approval={approval} me={me} nameOf={nameOf} />
          </span>
        </div>
        {approval.decisionNote && (
          <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', marginBottom: 'var(--space-3)' }}>
            <span className="muted">{approval.sentBack ? 'Feedback given: ' : 'Reason given: '}</span>
            {approval.decisionNote}
          </p>
        )}
        <div style={{ marginBottom: 'var(--space-3)' }}>
          <Collapsible title="Show the request" open={open} onToggle={setOpen} headingLevel="p">
            <div style={{ marginTop: 'var(--space-3)' }}>
              <PayloadPreview payload={approval.payload} actionClass={approval.actionClass} />
            </div>
          </Collapsible>
        </div>
        <a className="link" href={`/runs/${approval.runId}`}>
          View the run
        </a>
      </Card>
    </div>
  )
}
