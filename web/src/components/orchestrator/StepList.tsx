import { useState } from 'react'
import { Button, ConfirmDialog, StatusTag, Tag } from '../ui'
import { AgentAvatar } from '../ui/AgentAvatar'
import { Collapsible, useCollapsed } from '../ui/Collapsible'
import { Markdown } from '../ui/Markdown'
import { RunTraceCompact } from '../run/RunTraceCompact'
import { describeApiError } from '../../lib/api'
import { formatElapsed, formatRunElapsed } from '../../lib/format'
import { useCancelRun } from '../../lib/queries'
import type { Board, BoardGoal, BoardTask } from '../../lib/queries'
import { can } from '../../lib/session'
import { useToast } from '../../lib/toast'
import { useNow } from '../../lib/useNow'

/*
 * A goal's chain, one step per task: who has it, what it did, and the full trace on request
 * (B2.6). The step waiting for an answer never grows a second answer form of its own - the sheet
 * already shows one at the top - it only points back up to it.
 */

const ACTIVE_STATUS = new Set(['running', 'waiting_approval', 'waiting_input'])

function stepTiming(task: BoardTask, now: number): string {
  if (!task.startedAt) return 'Not started'
  if (ACTIVE_STATUS.has(task.status.toLowerCase())) {
    return formatRunElapsed({ status: task.status, startedAt: task.startedAt, completedAt: task.completedAt }, now)
  }
  if (task.completedAt) return formatElapsed(task.startedAt, task.completedAt, now)
  return 'Not started'
}

function ClampedResult({ text }: { text: string }) {
  const [expanded, setExpanded] = useState(false)
  // A short result never needs the control at all; a rough character count stands in for actually
  // measuring six rendered lines, which would need a DOM pass this component has no reason to make.
  const long = text.length > 420

  return (
    <div>
      <div className={!expanded && long ? 'orc-clamp' : undefined}>
        <Markdown text={text} />
      </div>
      {long && !expanded && (
        <button type="button" className="link" onClick={() => setExpanded(true)}>
          Show more
        </button>
      )}
    </div>
  )
}

function StepRow({
  task,
  agentName,
  agentCategory,
  agentFallback,
  onGoToQuestion,
}: {
  task: BoardTask
  agentName: string
  agentCategory: string | undefined
  agentFallback: boolean
  onGoToQuestion: () => void
}) {
  const toast = useToast()
  const now = useNow(1_000)
  const cancelRun = useCancelRun()
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [traceOpen, setTraceOpen] = useCollapsed(null, false)

  const active = ACTIVE_STATUS.has(task.status.toLowerCase())
  const canStop = active && Boolean(task.runId) && can('run:cancel')

  const confirmStop = async () => {
    if (!task.runId) return
    setError(null)
    try {
      await cancelRun.mutateAsync(task.runId)
      toast.success('Run stopped.')
      setConfirmOpen(false)
    } catch (thrown) {
      setError(describeApiError(thrown))
    }
  }

  return (
    <li className="orc-step">
      <div className="row" style={{ gap: 'var(--space-3)', alignItems: 'center', flexWrap: 'wrap' }}>
        <AgentAvatar name={agentName} category={agentCategory} fallback={agentFallback} size="sm" />
        <span style={{ fontWeight: 'var(--weight-strong)' }}>{agentName}</span>
        {agentFallback && <Tag>Default</Tag>}
        <StatusTag kind="task" status={task.status} />
        {task.maxAttempts > 1 && (
          <span className="caption muted">
            Attempt {task.attempt} of {task.maxAttempts}
          </span>
        )}
        <span className="caption tabular muted">{stepTiming(task, now)}</span>
      </div>

      <p style={{ marginTop: 'var(--space-2)' }}>{task.title}</p>

      {task.failureReason && (
        <p className="caption" style={{ color: 'var(--danger)', marginTop: 'var(--space-2)' }}>
          {task.failureReason}
        </p>
      )}

      {task.result && (
        <div style={{ marginTop: 'var(--space-2)' }}>
          {task.status.toLowerCase() === 'failed' && (
            <p className="caption" style={{ marginBottom: 'var(--space-1)' }}>
              Incomplete answer
            </p>
          )}
          <ClampedResult text={task.result} />
        </div>
      )}

      {task.runId && (
        <div style={{ marginTop: 'var(--space-3)' }}>
          <Collapsible title="Show trace" open={traceOpen} onToggle={setTraceOpen} headingLevel="p">
            {traceOpen && (
              <div style={{ marginTop: 'var(--space-3)' }}>
                <RunTraceCompact runId={task.runId} headingLevel="h4" limit={4} onGoToQuestion={onGoToQuestion} />
              </div>
            )}
          </Collapsible>
        </div>
      )}

      <div className="row" style={{ gap: 'var(--space-4)', marginTop: 'var(--space-3)', alignItems: 'center' }}>
        {canStop && (
          <Button variant="outline" onClick={() => setConfirmOpen(true)}>
            Stop this step
          </Button>
        )}
        {task.runId && (
          <a className="link" href={`/runs/${task.runId}`}>
            Open run
          </a>
        )}
      </div>

      {task.runId && (
        <ConfirmDialog
          open={confirmOpen}
          onClose={() => setConfirmOpen(false)}
          onConfirm={confirmStop}
          eyebrow="Stop a run"
          title="Stop this step?"
          description={
            task.status.toLowerCase() === 'waiting_approval'
              ? 'The run ends here, and the approval it is waiting on is withdrawn.'
              : task.status.toLowerCase() === 'waiting_input'
                ? 'The run ends here, and the question it is waiting on is withdrawn.'
                : 'The run is marked as stopped. A model or tool call already under way is not interrupted.'
          }
          confirmLabel="Stop this step"
          cancelLabel="Keep it running"
          tone="danger"
          loading={cancelRun.isPending}
          error={error}
        />
      )}
    </li>
  )
}

export function StepList({
  goal,
  board,
  onGoToQuestion,
}: {
  goal: BoardGoal
  board: Board
  onGoToQuestion: () => void
}) {
  const tasks = [...goal.tasks].sort((a, b) => a.position - b.position)
  const agentByI = new Map(board.agents.map((agent) => [agent.id, agent]))

  return (
    <ol className="stack" style={{ gap: 'var(--space-5)', listStyle: 'none', margin: 0, padding: 0 }}>
      {tasks.map((task) => {
        const agent = task.agentId ? agentByI.get(task.agentId) : undefined
        return (
          <StepRow
            key={task.id}
            task={task}
            agentName={agent?.name ?? 'No agent'}
            agentCategory={agent?.category}
            agentFallback={agent?.fallback ?? false}
            onGoToQuestion={onGoToQuestion}
          />
        )
      })}
    </ol>
  )
}
