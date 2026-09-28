import { Notice } from '../ui'
import { useApprovals } from '../../lib/queries'
import type { Run, RunStep } from '../../lib/queries'
import { can } from '../../lib/session'
import { latestApprovalId, withoutFinalStop } from './traceModel'

/**
 * A run held for a person's decision: what it is waiting for and where to decide it, or, for a
 * role that cannot see the queue, who can.
 */
export function WaitingForApproval({ run, steps }: { run: Run; steps: RunStep[] | undefined }) {
  const canRead = can('approval:read')
  const approvals = useApprovals({ enabled: canRead })
  const generic = 'This run is paused until someone whose role can approve actions decides it.'

  if (!canRead) return <Notice tone="warning">{generic}</Notice>
  if (approvals.isLoading) return null

  const approvalId = latestApprovalId(steps)
  // Only pending approvals are listed; one that has expired falls back to the general sentence.
  const pending =
    approvals.data?.find((approval) => approval.id === approvalId) ??
    approvals.data?.find((approval) => approval.runId === run.id)
  if (!pending) return <Notice tone="warning">{generic}</Notice>

  return (
    <Notice tone="warning">
      <span>
        Paused until someone decides: {withoutFinalStop(pending.summary)}.{' '}
        {!can('approval:decide') &&
          'Someone whose role can approve actions (by default a manager, admin or owner) can decide it. '}
        <a className="link" href={`/approvals#approval-${pending.id}`}>
          Review it in Approvals
        </a>
      </span>
    </Notice>
  )
}
