import { Notice } from '../ui'
import { readableSummary } from '../../lib/approvals'
import { useRunApproval } from '../../lib/approvalQueries'
import type { Run, RunStep } from '../../lib/queries'
import { can } from '../../lib/session'
import { withoutFinalStop } from './traceModel'

/**
 * A run held for a person's decision: what it is waiting for and where to decide it, or, for a
 * role that cannot see the queue, who can.
 *
 * The request is asked of the server by run, so it is found however long the queue is. `steps`
 * is still passed by the screens that show this, but a run is parked on at most one request at a
 * time, so the run alone says which.
 */
export function WaitingForApproval({ run }: { run: Run; steps?: RunStep[] | undefined }) {
  const canRead = can('approval:read')
  const approval = useRunApproval(run.id, { enabled: canRead })
  const generic = 'This run is paused until someone whose role can approve actions decides it.'

  if (!canRead) return <Notice tone="warning">{generic}</Notice>
  if (approval.isLoading) return null

  // Only a waiting request is found; one that has expired falls back to the general sentence.
  const pending = approval.data
  if (!pending) return <Notice tone="warning">{generic}</Notice>

  return (
    <Notice tone="warning">
      <span>
        Paused until someone decides: {withoutFinalStop(readableSummary(pending))}.{' '}
        {!can('approval:decide') &&
          'Someone whose role can approve actions (by default a manager, admin or owner) can decide it. '}
        <a className="link" href={`/approvals#approval-${pending.id}`}>
          Review it in Approvals
        </a>
      </span>
    </Notice>
  )
}
