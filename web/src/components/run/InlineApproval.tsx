import { useEffect, useState } from 'react'
import { Button, Notice, Textarea } from '../ui'
import { Collapsible, useCollapsed } from '../ui/Collapsible'
import { decisionError, formatPayload, readableSummary } from '../../lib/approvals'
import { useApprovals, useDecideApproval } from '../../lib/queries'
import { can } from '../../lib/session'

const REASON_MAX = 1_000

/**
 * A run held for an approval, decided without leaving the thread that is waiting on it.
 *
 * Extracted from ProgressCard's own inline card (chat/ProgressCard.tsx) so the Orchestrator's
 * step list can show the same decision inline too, by run id rather than by a chat task. A
 * viewer who cannot decide sees the request read-only, and someone who can add "Reject with a
 * reason" without leaving to the Approvals queue for it.
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
  const decideAllowed = canDecide ?? can('approval:decide')
  const approvalsQuery = useApprovals({ enabled: canRead })
  const decide = useDecideApproval()
  const [busy, setBusy] = useState<'approve' | 'reject' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [rejecting, setRejecting] = useState(false)
  const [reason, setReason] = useState('')
  const [payloadOpen, setPayloadOpen] = useCollapsed(null, false)

  const approval = approvalsQuery.data?.find((candidate) => candidate.runId === runId && candidate.status === 'pending')

  // The approvals list is cached and refreshed every half minute, so a request raised moments ago
  // is usually not in it yet. Ask again as soon as this card appears, and once more while it is
  // still missing, rather than telling a person who can decide that someone else must.
  const { refetch } = approvalsQuery
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
    return approvalsQuery.isFetching || approvalsQuery.isLoading ? (
      <p className="caption muted">Loading the request waiting for a decision…</p>
    ) : (
      generic
    )
  }

  async function decideIt(approved: boolean, note?: string) {
    setBusy(approved ? 'approve' : 'reject')
    setError(null)
    try {
      await decide.mutateAsync({ id: approval!.id, approved, ...(note ? { note } : {}) })
      setRejecting(false)
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

      <Collapsible title="Show what will be sent" open={payloadOpen} onToggle={setPayloadOpen} headingLevel="p">
        <pre className="inline-approval-payload">{formatPayload(approval.payload)}</pre>
      </Collapsible>

      {error && (
        <Notice tone="warning" live>
          {error}
        </Notice>
      )}

      {!decideAllowed ? (
        <Notice tone="info">Waiting for someone who can approve this.</Notice>
      ) : rejecting ? (
        <div className="stack" style={{ gap: 'var(--space-3)', marginTop: 'var(--space-3)' }}>
          <Textarea
            label="Reason"
            optional
            value={reason}
            onChange={(event) => setReason(event.target.value.slice(0, REASON_MAX))}
            maxLength={REASON_MAX}
            rows={3}
            hint="Kept on record with the decision. Up to 1,000 characters."
          />
          <div className="row" style={{ gap: 'var(--space-3)' }}>
            <Button variant="outline" onClick={() => void decideIt(false, reason.trim() || undefined)} loading={busy === 'reject'}>
              Reject
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
          <Button onClick={() => void decideIt(true)} loading={busy === 'approve'} disabled={busy === 'reject'}>
            Approve
          </Button>
          <Button variant="outline" onClick={() => setRejecting(true)} disabled={busy !== null}>
            Reject with a reason
          </Button>
        </div>
      )}
    </div>
  )
}
