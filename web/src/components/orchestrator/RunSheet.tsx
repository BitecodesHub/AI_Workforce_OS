import { useState } from 'react'
import { Button, ConfirmDialog, StatusTag } from '../ui'
import { Sheet } from '../ui/Sheet'
import { InlineApproval } from '../run/InlineApproval'
import { QuestionCard } from '../run/QuestionCard'
import { RunTraceCompact } from '../run/RunTraceCompact'
import { detailText, isInstruction } from '../run/traceModel'
import { useMediaQuery } from '../../hooks/useMediaQuery'
import { describeApiError } from '../../lib/api'
import { shortId } from '../../lib/format'
import { isRunActive, useCancelRun, useMemberNames, useRun, useRunSteps } from '../../lib/queries'
import type { Board } from '../../lib/queries'
import { can, profile } from '../../lib/session'
import { useToast } from '../../lib/toast'

/*
 * A direct run's own sheet (B2.6): a run started straight from an agent, with no goal or task of
 * its own to hang a GoalSheet off. It shows the same open question or approval, at the top, and
 * the same trace, but its footer offers only what a run without a chain can: stop it, or open it.
 */
export function RunSheet({ runId, board, onClose }: { runId: string; board: Board; onClose: () => void }) {
  const toast = useToast()
  const members = useMemberNames()
  const me = profile()?.userId ?? null
  const nameOf = (userId: string) => members[userId]?.displayName ?? shortId(userId)

  const runQuery = useRun(runId)
  const run = runQuery.data
  const active = isRunActive(run)
  const stepsQuery = useRunSteps(runId, { active })
  const instruction = stepsQuery.data?.find(isInstruction)
  const instructionText = instruction ? detailText(instruction.detail, 'content') : null

  const atLeast1024 = useMediaQuery('(min-width: 1024px)')
  const atLeast768 = useMediaQuery('(min-width: 768px)')
  const modal = !atLeast1024
  const width: 'sm' | 'md' | 'full' = atLeast1024 ? 'md' : atLeast768 ? 'sm' : 'full'

  const cancelRun = useCancelRun()
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const agentName = run ? (board.agents.find((agent) => agent.id === run.agentId)?.name ?? 'The agent') : 'The agent'
  const pendingQuestion = board.questions.find((question) => question.runId === runId && question.status === 'pending')
  const pendingApproval = board.approvals.find((approval) => approval.runId === runId)
  const questionAgent = pendingQuestion ? board.agents.find((candidate) => candidate.id === pendingQuestion.agentId) : undefined

  const canStop = active && can('run:cancel')

  const confirmStop = async () => {
    setError(null)
    try {
      await cancelRun.mutateAsync(runId)
      toast.success('Run stopped.')
      setConfirmOpen(false)
    } catch (thrown) {
      setError(describeApiError(thrown))
    }
  }

  return (
    <Sheet
      open
      onClose={onClose}
      side="right"
      modal={modal}
      width={width}
      eyebrow="Run"
      title={agentName}
      footer={
        <>
          {canStop && (
            <Button variant="outline" onClick={() => setConfirmOpen(true)}>
              Stop run
            </Button>
          )}
          <a className="link" href={`/runs/${runId}`}>
            Open run
          </a>
        </>
      }
    >
      <div className="stack" style={{ gap: 'var(--space-5)' }}>
        {run && <StatusTag kind="run" status={run.status} withDot />}

        {instructionText && (
          <div>
            <h3 className="section-heading" style={{ marginBottom: 'var(--space-2)' }}>
              Instruction
            </h3>
            <p style={{ whiteSpace: 'pre-wrap' }}>{instructionText}</p>
          </div>
        )}

        {pendingQuestion && (
          <QuestionCard
            question={pendingQuestion}
            agentName={questionAgent?.name ?? agentName}
            agentCategory={questionAgent?.category}
            agentFallback={questionAgent?.fallback}
            via="orchestrator"
            compact
            headingLevel="h3"
            me={me}
            nameOf={nameOf}
          />
        )}
        {!pendingQuestion && pendingApproval && (
          <InlineApproval runId={runId} agentName={agentName} canDecide={pendingApproval.canDecide} compact />
        )}

        <RunTraceCompact runId={runId} headingLevel="h3" />
      </div>

      <ConfirmDialog
        open={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        onConfirm={confirmStop}
        eyebrow="Stop a run"
        title="Stop this run?"
        description={
          run?.status === 'waiting_approval'
            ? 'The run ends here, and the approval it is waiting on is withdrawn.'
            : run?.status === 'waiting_input'
              ? 'The run ends here, and the question it is waiting on is withdrawn.'
              : 'The run is marked as stopped. A model or tool call already under way is not interrupted.'
        }
        confirmLabel="Stop run"
        cancelLabel="Keep it running"
        tone="danger"
        loading={cancelRun.isPending}
        error={error}
      />
    </Sheet>
  )
}
