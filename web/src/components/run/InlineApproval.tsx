import { useEffect, useState } from 'react'
import { Button, Notice, Textarea, Time } from '../ui'
import { Collapsible, useCollapsed } from '../ui/Collapsible'
import { PayloadPreview } from '../approvals/PayloadPreview'
import {
  contextLine,
  decisionError,
  previewOpensByDefault,
  previewToggleLabel,
  readableSummary,
  runOutcome,
} from '../../lib/approvals'
import { useMemberNamer, useRunApproval } from '../../lib/approvalQueries'
import type { ApprovalItem } from '../../lib/approvalQueries'
import { useDecideApproval } from '../../lib/queries'
import { can } from '../../lib/session'
import { useToast } from '../../lib/toast'

const REASON_MAX = 1_000

/**
 * A run held for an approval, decided without leaving the thread that is waiting on it.
 *
 * Extracted from ProgressCard's own inline card (chat/ProgressCard.tsx) so the Orchestrator's
 * step list can show the same decision inline too, by run id rather than by a chat task. A
 * viewer who cannot decide sees the request read-only, and someone who can add "Reject with a
 * reason" without leaving to the Approvals queue for it.
 *
 * The request is shown as the email or message it is, open from the start when it leaves the
 * workspace or removes something: an approver should not have to find the details to see what
 * they are agreeing to.
 */
export function InlineApproval({
  runId,
  agentName,
  compact = false,
  canDecide,
}: {
  runId: string
  agentName: string
  compact?: boolean
  /** Defaults to the signed-in role's own approval:decide permission. */
  canDecide?: boolean
}) {
  const canRead = can('approval:read')
  const approvalQuery = useRunApproval(runId, { enabled: canRead })
  const approval = approvalQuery.data ?? undefined

  // A request raised moments ago may not be there yet. Ask again as soon as this card appears,
  // and every few seconds while it is still missing, rather than telling a person who can decide
  // that someone else must.
  const { refetch } = approvalQuery
  const missing = canRead && !approval
  useEffect(() => {
    if (!missing) return
    void refetch()
    const timer = window.setInterval(() => void refetch(), 3_000)
    return () => window.clearInterval(timer)
  }, [missing, refetch])

  const generic = <Notice tone="warning">Waiting for someone who can approve actions.</Notice>
  if (!canRead) return generic
  if (!approval) {
    return approvalQuery.isFetching || approvalQuery.isLoading ? (
      <p className="caption muted">Loading the request waiting for a decision…</p>
    ) : (
      generic
    )
  }

  return <InlineDecision approval={approval} agentName={agentName} compact={compact} canDecide={canDecide} />
}

function InlineDecision({
  approval,
  agentName,
  compact,
  canDecide,
}: {
  approval: ApprovalItem
  agentName: string
  compact: boolean
  canDecide: boolean | undefined
}) {
  // The caller's own say (the board's, which counts the permission) and the server's (which also
  // counts the workspace's four-eyes rule): both must agree.
  const decideAllowed = (canDecide ?? can('approval:decide')) && approval.canDecide
  const decide = useDecideApproval()
  const toast = useToast()
  const { me, nameOf } = useMemberNamer()
  const [busy, setBusy] = useState<'approve' | 'reject' | 'request_changes' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [rejecting, setRejecting] = useState(false)
  const [sendingBack, setSendingBack] = useState(false)
  const [reason, setReason] = useState('')
  const [payloadOpen, setPayloadOpen] = useCollapsed(null, previewOpensByDefault(approval.actionClass))
  const needsSomeoneElse = !approval.canDecide && can('approval:decide') && me != null && approval.requestedBy === me

  async function decideIt(mode: 'approve' | 'reject' | 'request_changes', note?: string) {
    setBusy(mode)
    setError(null)
    try {
      const result = await decide.mutateAsync({
        id: approval.id,
        approved: mode === 'approve',
        mode,
        ...(note ? { note } : {}),
      })
      if (mode === 'approve') {
        const outcome = runOutcome(result.runStatus)
        toast.success(outcome.startsWith('Approved.') ? outcome : `Approved. ${outcome}`)
      } else if (mode === 'request_changes') {
        toast.success('Sent back. The agent will revise it and ask again.')
      }
      setRejecting(false)
      setSendingBack(false)
      setReason('')
    } catch (err) {
      setError(decisionError(err))
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className={`inline-approval${compact ? ' inline-approval-compact' : ''}`}>
      <p className="caption muted">{agentName} is waiting for a decision</p>
      <p>{readableSummary(approval)}</p>
      <p className="caption muted">
        {contextLine(approval, me, nameOf)} · Expires <Time iso={approval.expiresAt} />
      </p>

      <Collapsible
        title={previewToggleLabel(approval.actionClass)}
        open={payloadOpen}
        onToggle={setPayloadOpen}
        headingLevel="p"
      >
        <PayloadPreview payload={approval.payload} actionClass={approval.actionClass} />
      </Collapsible>

      {error && (
        <Notice tone="warning" live>
          {error}
        </Notice>
      )}

      {!decideAllowed ? (
        <Notice tone="info">
          {needsSomeoneElse
            ? 'You asked for this work, so someone else needs to decide it.'
            : 'Waiting for someone who can approve this.'}
        </Notice>
      ) : sendingBack ? (
        <div className="stack" style={{ gap: 'var(--space-3)', marginTop: 'var(--space-3)' }}>
          <Textarea
            label="What should change"
            value={reason}
            onChange={(event) => setReason(event.target.value.slice(0, REASON_MAX))}
            maxLength={REASON_MAX}
            rows={3}
            hint="The agent does not take this action as written. It reads this, revises it and asks again."
          />
          <div className="row" style={{ gap: 'var(--space-3)' }}>
            <Button
              onClick={() => void decideIt('request_changes', reason.trim())}
              loading={busy === 'request_changes'}
              disabled={reason.trim() === ''}
            >
              Send back
            </Button>
            <Button
              variant="quiet"
              onClick={() => {
                setSendingBack(false)
                setReason('')
              }}
              disabled={busy !== null}
            >
              Cancel
            </Button>
          </div>
        </div>
      ) : rejecting ? (
        <div className="stack" style={{ gap: 'var(--space-3)', marginTop: 'var(--space-3)' }}>
          <Textarea
            label="Reason"
            optional
            value={reason}
            onChange={(event) => setReason(event.target.value.slice(0, REASON_MAX))}
            maxLength={REASON_MAX}
            rows={3}
            hint="Ends the run. Shown to the person who asked and recorded in the audit log. Up to 1,000 characters."
          />
          <div className="row" style={{ gap: 'var(--space-3)' }}>
            <Button variant="outline" onClick={() => void decideIt('reject', reason.trim() || undefined)} loading={busy === 'reject'}>
              Reject and stop
            </Button>
            <Button
              variant="quiet"
              onClick={() => {
                setRejecting(false)
                setReason('')
              }}
              disabled={busy !== null}
            >
              Cancel
            </Button>
          </div>
        </div>
      ) : (
        <div className="row" style={{ gap: 'var(--space-3)', marginTop: 'var(--space-3)' }}>
          {/* data-approve-button: the chat's "Review" link focuses this button (chat/ProgressCard.tsx). */}
          <Button data-approve-button onClick={() => void decideIt('approve')} loading={busy === 'approve'} disabled={busy === 'reject'}>
            Approve
          </Button>
          <Button variant="outline" onClick={() => setSendingBack(true)} disabled={busy !== null}>
            Send back with feedback
          </Button>
          <Button variant="outline" onClick={() => setRejecting(true)} disabled={busy !== null}>
            Reject and stop
          </Button>
        </div>
      )}
    </div>
  )
}
