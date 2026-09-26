import { useState } from 'react'
import { Button, Card, Dialog, EmptyState, Eyebrow, Notice, PageHeader, PermissionState, StatusTag, Tag, Textarea } from '../components/ui'
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
import { timeAgo, timeUntil } from '../lib/api'
import { useApprovals, useAgentNames, useDecideApproval } from '../lib/queries'
import { useToast } from '../lib/toast'

const ACTION_TONE = { OUTBOUND: 'warning', DESTRUCTIVE: 'danger', WRITE: 'blue' } as const
const ACTION_LABEL = {
  OUTBOUND: 'Leaves the workspace',
  DESTRUCTIVE: 'Removes something',
  WRITE: 'Changes data',
} as const

/** The backend guarantees valid JSON; the fallback only covers a payload from before this column was populated. */
function formatPayload(payload: string): string {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2)
  } catch {
    return payload
  }
}

function ApprovalCard({
  approval,
  agentNames,
  onApprove,
  onReject,
  canDecide,
  isApproving,
  isRejecting,
}: {
  approval: {
    id: string
    runId: string
    agentId: string
    tool: string | null
    actionClass: string
    summary: string
    payload: string
    status: string
    requestedAt: string
    expiresAt: string
  }
  agentNames: Record<string, { name: string }>
  onApprove: () => void
  onReject: () => void
  canDecide: boolean
  isApproving: boolean
  isRejecting: boolean
}) {
  const agent = agentNames[approval.agentId]
  const agentName = agent?.name ?? approval.agentId
  const actionClass = approval.actionClass as keyof typeof ACTION_TONE

  return (
    <Card key={approval.id} as="article">
      <Eyebrow>{approval.tool ?? 'Unknown tool'}</Eyebrow>

      <div
        className="row"
        style={{ justifyContent: 'space-between', gap: 'var(--space-4)', marginBottom: 'var(--space-4)' }}
      >
        <h2 className="section-heading" style={{ fontSize: '15px' }}>
          {approval.summary}
        </h2>
        <Tag tone={ACTION_TONE[actionClass] ?? 'neutral'}>{ACTION_LABEL[actionClass] ?? actionClass}</Tag>
      </div>

      <div className="row" style={{ gap: 'var(--space-4)', marginBottom: 'var(--space-5)' }}>
        <Tag tone="operations" withDot>
          {agentName}
        </Tag>
        <span className="caption">Requested {timeAgo(approval.requestedAt)}</span>
        <span className="caption">Expires {timeUntil(approval.expiresAt)}</span>
        <StatusTag status={approval.status} />
      </div>

      <div
        style={{
          background: 'var(--paper)',
          border: '1px solid var(--line)',
          borderRadius: 'var(--radius-control-lg)',
          padding: 'var(--space-5)',
          marginBottom: 'var(--space-5)',
          color: 'var(--ink)',
          lineHeight: 1.6,
        }}
      >
        <p style={{ margin: 0, marginBottom: 'var(--space-4)' }}>{approval.summary}</p>
        <p className="caption muted" style={{ marginBottom: 'var(--space-2)' }}>
          Exactly what will be sent
        </p>
        <pre
          style={{
            margin: 0,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
            fontFamily: 'var(--font-mono)',
            fontSize: '12px',
          }}
        >
          {formatPayload(approval.payload)}
        </pre>
      </div>

      <div className="row" style={{ gap: 'var(--space-3)' }}>
        {canDecide ? (
          <>
            <Button onClick={onApprove} loading={isApproving}>
              Approve
            </Button>
            <Button variant="outline" onClick={onReject} loading={isRejecting}>
              Reject
            </Button>
          </>
        ) : (
          <PermissionState permission="approval:decide" what="approve or reject this request" />
        )}
        <a className="nav-pill" href={`/runs/${approval.runId}`}>
          View the run
        </a>
      </div>
    </Card>
  )
}

export function Approvals() {
  const toast = useToast()
  const approvalsQuery = useApprovals()
  const agentNames = useAgentNames()
  const decideApproval = useDecideApproval()
  const [rejectDialogOpen, setRejectDialogOpen] = useState(false)
  const [rejectNote, setRejectNote] = useState('')
  const [currentApprovalId, setCurrentApprovalId] = useState<string | null>(null)
  const [isApprovingId, setIsApprovingId] = useState<string | null>(null)
  const [isRejectingId, setIsRejectingId] = useState<string | null>(null)

  const handleApprove = async (approvalId: string) => {
    setIsApprovingId(approvalId)
    try {
      await decideApproval.mutateAsync({ id: approvalId, approved: true })
      toast.success('Approved')
    } catch (error) {
      if (error instanceof Error && error.message.includes('approval_already_decided')) {
        toast.error('Already decided')
        approvalsQuery.refetch()
      } else {
        const message = error instanceof Error ? error.message : 'Failed to approve'
        toast.error(message)
      }
    } finally {
      setIsApprovingId(null)
    }
  }

  const handleRejectOpen = (approvalId: string) => {
    setCurrentApprovalId(approvalId)
    setRejectNote('')
    setRejectDialogOpen(true)
  }

  const handleRejectConfirm = async () => {
    if (!currentApprovalId) return
    setIsRejectingId(currentApprovalId)
    try {
      await decideApproval.mutateAsync({ id: currentApprovalId, approved: false, note: rejectNote })
      toast.success('Rejected')
    } catch (error) {
      if (error instanceof Error && error.message.includes('approval_already_decided')) {
        toast.error('Already decided')
        approvalsQuery.refetch()
      } else {
        const message = error instanceof Error ? error.message : 'Failed to reject'
        toast.error(message)
      }
    } finally {
      setIsRejectingId(null)
      setRejectDialogOpen(false)
      setCurrentApprovalId(null)
    }
  }

  const isEmpty = (data: { id: string }[]) => data.length === 0

  return (
    <div className="page">
      <PageHeader
        eyebrow="Waiting on you"
        title="Approvals"
        description="Agents stop here before doing anything that leaves the workspace or cannot be undone."
      />

      <Notice tone="info">
        An approval nobody decides is rejected when it expires. Nothing is sent because a deadline
        passed.
      </Notice>

      <QueryState
        query={approvalsQuery}
        permission="approval:read"
        what="the approvals queue"
        rows={4}
        isEmpty={isEmpty}
        empty={
          <Card>
            <EmptyState
              icon={
                <EmptyIcon kind="inbox" />
              }
              title="No approvals waiting"
              body="When an agent needs permission to send or delete something, it will appear here."
              action={<a className="nav-pill" href="/runs">View recent decisions</a>}
            />
          </Card>
        }
      >
        {(approvals) => (
          <div className="stack" style={{ gap: 'var(--space-5)', marginTop: 'var(--space-7)' }}>
            {approvals
              .filter((a) => a.status === 'pending')
              .map((approval) => (
                <ApprovalCard
                  key={approval.id}
                  approval={approval}
                  agentNames={agentNames}
                  onApprove={() => handleApprove(approval.id)}
                  onReject={() => handleRejectOpen(approval.id)}
                  canDecide={decideApproval.mutate !== undefined}
                  isApproving={isApprovingId === approval.id}
                  isRejecting={isRejectingId === approval.id}
                />
              ))}
          </div>
        )}
      </QueryState>

      <Dialog
        open={rejectDialogOpen}
        onClose={() => {
          setRejectDialogOpen(false)
          setCurrentApprovalId(null)
        }}
        eyebrow="Reject approval"
        title="Add a note (optional)"
        description="The note will be visible to the agent and in the audit log. Maximum 1000 characters."
        footer={
          <div className="dialog-footer">
            <Button variant="outline" onClick={() => setRejectDialogOpen(false)}>
              Cancel
            </Button>
            <Button variant="danger" onClick={handleRejectConfirm} loading={!!isRejectingId}>
              Reject
            </Button>
          </div>
        }
      >
        <Textarea
          label="Note"
          value={rejectNote}
          onChange={(e) => setRejectNote(e.target.value.slice(0, 1000))}
          placeholder="Why is this being rejected?"
          rows={4}
        />
      </Dialog>
    </div>
  )
}