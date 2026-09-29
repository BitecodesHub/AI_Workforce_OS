import { useContext, useState } from 'react'
import { InlineApproval } from '../run/InlineApproval'
import { RunTraceCompact } from '../run/RunTraceCompact'
import { Button, Card, ConfirmDialog, Tag } from '../ui'
import { Collapsible, useCollapsed } from '../ui/Collapsible'
import { AgentAvatar } from '../ui/AgentAvatar'
import { truncateWords } from '../../lib/format'
import { statusLabel } from '../../lib/labels'
import { isGoalActive } from '../../lib/queries'
import type { Agent, BoardGoal, BoardTask, RunQuestion } from '../../lib/queries'
import { can } from '../../lib/session'
import { canRetryGoal, canStopGoal, currentTaskIndex, progressSummary } from './chatModel'
import { DetailsContext } from './detailsContext'

/*
 * A goal's chain, live: which agent has which task, who is up next, and, the moment a step needs
 * a person, the decision itself rather than a link to go find it. Each step's own trace is one
 * click away without leaving the thread. Collapses to one line once the goal has finished,
 * following the thread-wide Details mode (B1.4, B1.5).
 */

function TaskChainStep({
  task,
  agent,
  isCurrent,
  question,
}: {
  task: BoardTask
  agent: Agent | undefined
  isCurrent: boolean
  question: RunQuestion | undefined
}) {
  const [open, setOpen] = useState(false)
  const status = statusLabel('task', task.status)
  const name = agent?.name ?? (task.agentId ? 'An agent' : 'Unassigned')

  return (
    <div className="chat-chain-step">
      <button
        type="button"
        className={`chat-chain-pill${isCurrent ? ' chat-chain-pill-current' : ''}`}
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        <AgentAvatar name={name} category={agent?.category} fallback={agent?.fallback ?? false} />
        <span className="chat-chain-pill-name">{name}</span>
        <Tag tone={status.tone}>{status.label}</Tag>
        {isCurrent && <span className="chat-pulse-dot" aria-hidden="true" />}
      </button>
      {task.status === 'waiting_input' && question && (
        <a className="link caption" href={`#question-${question.id}`}>
          Go to question
        </a>
      )}
      {open && (
        <div className="chat-chain-step-body">
          {task.runId ? (
            <RunTraceCompact runId={task.runId} headingLevel="h4" limit={4} showAnswer={false} />
          ) : (
            <p className="caption muted">This task has not started yet.</p>
          )}
        </div>
      )}
    </div>
  )
}

export function ProgressCard({
  goal,
  agentNames,
  me,
  questions,
  onStop,
  onRetry,
}: {
  goal: BoardGoal
  agentNames: Record<string, Agent>
  me: string | null
  questions: RunQuestion[]
  onStop: (goalId: string) => void
  onRetry: (goalId: string) => void
}) {
  const [confirmStop, setConfirmStop] = useState(false)
  const [confirmRetry, setConfirmRetry] = useState(false)
  const details = useContext(DetailsContext)
  const override = details.mode === 'auto' ? null : details.mode
  const finished = !isGoalActive(goal)
  const [open, setOpen] = useCollapsed(null, !finished, override, details.version)

  const tasks = [...goal.tasks].sort((a, b) => a.position - b.position)
  const currentIdx = currentTaskIndex(tasks)
  const waitingTask = tasks.find((task) => task.status === 'waiting_approval')
  const failedIndex = tasks.findIndex((task) => task.status === 'failed')
  const summary = progressSummary(goal)
  const stoppable = canStopGoal(goal, me, can)
  const retryable = canRetryGoal(goal, me, can)

  return (
    <Card as="article" className="chat-progress-card">
      <Collapsible
        title="Progress"
        summary={summary}
        open={open}
        onToggle={setOpen}
        headingLevel="p"
        actions={
          <div className="row" style={{ gap: 'var(--space-2)' }}>
            {!finished && stoppable && (
              <Button variant="outline" onClick={() => setConfirmStop(true)}>
                Stop
              </Button>
            )}
            {finished && (goal.status === 'failed' || goal.status === 'cancelled') && retryable && (
              <Button variant="outline" onClick={() => setConfirmRetry(true)}>
                Try again
              </Button>
            )}
            <a className="link caption" href={`/orchestrator?goal=${goal.id}`}>
              Open in Orchestrator
            </a>
          </div>
        }
      >
        <p className="section-heading" style={{ marginBottom: 'var(--space-1)' }}>
          {truncateWords(goal.title, 140)}
        </p>

        <ol className="chat-chain">
          {tasks.map((task, index) => (
            <li key={task.id} className="chat-chain-wrap">
              {index > 0 && (
                <span className={`chat-chain-arrow${index === currentIdx ? ' chat-chain-arrow-active' : ''}`} aria-hidden="true">
                  <svg width="18" height="10" viewBox="0 0 18 10" fill="none">
                    <path d="M0 5h15M11 1l4 4-4 4" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
                  </svg>
                </span>
              )}
              <TaskChainStep
                task={task}
                agent={task.agentId ? agentNames[task.agentId] : undefined}
                isCurrent={index === currentIdx}
                question={questions.find((q) => q.status === 'pending' && q.taskId === task.id)}
              />
            </li>
          ))}
        </ol>

        {waitingTask?.runId && (
          <InlineApproval runId={waitingTask.runId} agentName={waitingTask.agentId ? (agentNames[waitingTask.agentId]?.name ?? 'The agent') : 'The agent'} />
        )}
      </Collapsible>

      <ConfirmDialog
        open={confirmStop}
        onClose={() => setConfirmStop(false)}
        onConfirm={() => {
          setConfirmStop(false)
          onStop(goal.id)
        }}
        eyebrow="Stop this work"
        title="Stop this work?"
        description="Nothing further runs for this request. A step already under way is marked stopped."
        confirmLabel="Stop"
        tone="danger"
      />
      <ConfirmDialog
        open={confirmRetry}
        onClose={() => setConfirmRetry(false)}
        onConfirm={() => {
          setConfirmRetry(false)
          onRetry(goal.id)
        }}
        eyebrow="Try again"
        title={`Try again from step ${(failedIndex === -1 ? tasks.length : failedIndex + 1)}?`}
        description={`Step ${failedIndex === -1 ? tasks.length : failedIndex + 1} (${
          failedIndex !== -1 && tasks[failedIndex]?.agentId ? (agentNames[tasks[failedIndex]!.agentId!]?.name ?? 'the agent') : 'the agent'
        }) starts again from the beginning. Anything it already did before it stopped, such as a sent email, may happen again. Check its trace first.`}
        confirmLabel="Try again"
        tone="primary"
      />
    </Card>
  )
}
