import { useState } from 'react'
import { Button, ConfirmDialog, Dialog, Eyebrow, StatusTag, Tag } from '../ui'
import { describeApiError } from '../../lib/api'
import { formatMoney } from '../../lib/format'
import { categoryTone, goalSourceLabel } from '../../lib/labels'
import { isGoalActive, useAgentNames, useCancelGoal, useMemberNames } from '../../lib/queries'
import type { BoardGoal } from '../../lib/queries'
import { can, profile } from '../../lib/session'
import { useToast } from '../../lib/toast'
import { RunTraceCompact } from '../run/RunTraceCompact'
import { queueReasonText, stepLabel } from './layout'
import { requesterLabel } from './shared'
import type { BoardCard } from './layout'

/*
 * A goal's whole story, one click away from its card: the chain of agents it moved through (or
 * will), each step's own run trace, who asked for it and where that ask came from, and a way to
 * call the whole thing off while it is still moving.
 */

function sourceLink(goal: BoardGoal) {
  if (goal.source === 'chat' && goal.conversationId) {
    return (
      <a className="link" href={`/chat?c=${encodeURIComponent(goal.conversationId)}`}>
        Open the conversation
      </a>
    )
  }
  if (goal.source === 'schedule') {
    return (
      <a className="link" href="/schedules">
        Open Schedules
      </a>
    )
  }
  return null
}

export function GoalDrawer({ card, onClose }: { card: BoardCard; onClose: () => void }) {
  const { goal } = card
  const toast = useToast()
  const members = useMemberNames()
  const agents = useAgentNames()
  const currentUserId = profile()?.userId ?? null
  const cancelGoal = useCancelGoal(goal.id)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const canCancel = can('task:cancel') && isGoalActive(goal)
  const tasks = [...goal.tasks].sort((a, b) => a.position - b.position)
  const sourceEntry = goalSourceLabel(goal.source)
  const totalCost = tasks.reduce((sum, task) => sum + (task.cost ?? 0), 0)

  const confirmCancel = async () => {
    setError(null)
    try {
      await cancelGoal.mutateAsync()
      toast.success('Goal cancelled')
      setConfirmOpen(false)
      onClose()
    } catch (thrown) {
      setError(describeApiError(thrown))
    }
  }

  return (
    <>
      <Dialog
        open
        onClose={onClose}
        eyebrow="Goal"
        title={goal.title}
        description={`Asked for by ${requesterLabel(goal, members, currentUserId)}.`}
        footer={
          <>
            <Button variant="outline" onClick={onClose}>
              Close
            </Button>
            {canCancel && (
              <Button variant="danger" onClick={() => setConfirmOpen(true)} loading={cancelGoal.isPending}>
                Cancel goal
              </Button>
            )}
          </>
        }
      >
        <div className="stack" style={{ gap: 'var(--space-5)' }}>
          <div className="row" style={{ gap: 'var(--space-2)', flexWrap: 'wrap', alignItems: 'center' }}>
            <StatusTag kind="goal" status={goal.status} withDot />
            <Tag tone={sourceEntry.tone}>{sourceEntry.label}</Tag>
            {totalCost > 0 && <span className="caption tabular">{formatMoney(totalCost)}</span>}
            {sourceLink(goal)}
          </div>

          <div>
            <Eyebrow as="h3">Chain</Eyebrow>
            <div className="orc-chain" style={{ marginTop: 'var(--space-2)' }}>
              {tasks.map((task, index) => {
                const agent = task.agentId ? agents[task.agentId] : undefined
                return (
                  <span key={task.id} className="orc-chain-step">
                    {index > 0 && (
                      <span className="orc-chain-arrow" aria-hidden="true">
                        →
                      </span>
                    )}
                    <Tag tone={categoryTone(agent?.category)}>{agent?.name ?? 'No agent'}</Tag>
                    <StatusTag kind="task" status={task.status} />
                  </span>
                )
              })}
            </div>
            {card.column === 'queued' && card.reason && (
              <p className="caption muted" style={{ marginTop: 'var(--space-2)' }}>
                {queueReasonText(card.reason)}
                {card.queuePosition != null && ` · position ${card.queuePosition + 1} in the queue`}
              </p>
            )}
          </div>

          {tasks
            .filter((task) => task.runId)
            .map((task) => (
              <div key={task.id}>
                <Eyebrow as="h3">
                  {task.title} · {stepLabel(goal, task)}
                </Eyebrow>
                <div style={{ marginTop: 'var(--space-3)' }}>
                  <RunTraceCompact runId={task.runId!} headingLevel="h4" limit={4} />
                </div>
              </div>
            ))}

          {tasks.every((task) => !task.runId) && (
            <p className="caption muted">This goal has not started a run yet.</p>
          )}
        </div>
      </Dialog>

      <ConfirmDialog
        open={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        onConfirm={confirmCancel}
        eyebrow="Cancel goal"
        title="Cancel this goal?"
        description="Its unfinished tasks are cancelled and will not run again. A run still in progress is stopped, and any approval it is waiting on is withdrawn."
        confirmLabel="Cancel goal"
        tone="danger"
        loading={cancelGoal.isPending}
        error={error}
      />
    </>
  )
}
