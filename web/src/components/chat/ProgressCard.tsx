// @find: goal progress, live goal, chain of agents, task steps, who is up next, review, approval in chat, orchestrator progress, work in progress, run trace
// @what: Live card for a goal's chain of agent tasks and what each is doing now.
// @flow: Used by MessageItem and WorkStrip; uses RunTraceCompact and InlineApproval.
import { useContext, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { InlineApproval } from '../run/InlineApproval'
import { RunTraceCompact } from '../run/RunTraceCompact'
import { Card, ConfirmDialog, Tag } from '../ui'
import { Collapsible, useCollapsed } from '../ui/Collapsible'
import { AgentAvatar } from '../ui/AgentAvatar'
import { formatElapsed, truncateWords } from '../../lib/format'
import { statusLabel } from '../../lib/labels'
import { isGoalActive, useRunSteps } from '../../lib/queries'
import type { Agent, BoardGoal, BoardTask, RunQuestion } from '../../lib/queries'
import { can } from '../../lib/session'
import { useNow } from '../../lib/useNow'
import { canRetryGoal, canStopGoal, currentTaskIndex, liveStepText, progressSummary } from './chatModel'
import { DetailsContext } from './detailsContext'

/*
 * A goal's chain, live: which agent has which task, who is up next, and, the moment a step needs
 * a person, the decision itself rather than a link to go find it. Each step's own trace is one
 * click away without leaving the thread. Collapses to one line once the goal has finished,
 * following the thread-wide Details mode (B1.4, B1.5).
 *
 * The work strip's "Review" link asks for this card through DetailsContext's `revealGoal`: the
 * card opens, even when the person folded it or chose Details > Collapsed, and the Approve button
 * takes focus once the request waiting for a decision has loaded.
 */

/** How long the card keeps looking for the Approve button while the approval itself loads. */
const REVEAL_ATTEMPTS = 20
const REVEAL_RETRY_MS = 150

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
        <AgentAvatar name={name} category={agent?.category} fallback={agent?.fallback ?? false} size="sm" quietInitials />
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

/** Whether this person may see what work costs: the same permissions that open the spend screens. */
function canSeeSpend(): boolean {
  return can('budget:read') || can('analytics:read')
}

/** The step a running task is on, in plain words. Its own component so the steps query only exists once there is a run. */
function RunningStepText({ runId, active }: { runId: string; active: boolean }) {
  const steps = useRunSteps(runId, { active }).data
  return <>{liveStepText(steps?.[steps.length - 1])}</>
}

function stepText(task: BoardTask | undefined): ReactNode {
  if (!task) return 'Starting'
  if (task.status === 'waiting_approval') return 'Waiting for approval'
  if (task.status === 'waiting_input') return 'Waiting for an answer'
  if (task.runId && task.status === 'running') return <RunningStepText runId={task.runId} active />
  return 'Starting'
}

/*
 * The compact live card's header while a goal is open: who is on it, what they are doing now, how
 * long it has been, and the two quiet actions. Only the status word is a live region, so a screen
 * reader hears "Working" become "Waiting for approval" once, never the ticking clock.
 */
function LiveHeader({
  goal,
  task,
  index,
  total,
  agent,
  stoppable,
  onAskStop,
}: {
  goal: BoardGoal
  task: BoardTask | undefined
  index: number
  total: number
  agent: Agent | undefined
  stoppable: boolean
  onAskStop: () => void
}) {
  const now = useNow(1_000)
  const name = agent?.name ?? 'An agent'
  const parked = task?.status === 'waiting_approval' || task?.status === 'waiting_input'
  const status = task?.status === 'waiting_approval' ? 'Waiting for approval' : task?.status === 'waiting_input' ? 'Waiting for an answer' : 'Working'

  return (
    <div className="chat-live">
      <div className="chat-live-row">
        <AgentAvatar name={name} category={agent?.category} fallback={agent?.fallback ?? false} size="sm" quietInitials />
        <span className="chat-live-name">{name}</span>
        <span className="chat-live-status" role="status">
          {!parked && <span className="chat-pulse-dot" aria-hidden="true" />}
          {status}
        </span>
        <span className="chat-live-elapsed caption muted tabular">{formatElapsed(goal.createdAt, null, now)}</span>
        <span className="chat-live-actions">
          {stoppable && (
            <button type="button" className="button button-outline button-sm chat-live-stop" onClick={onAskStop}>
              Stop
            </button>
          )}
          <a className="link caption" href={`/orchestrator?goal=${goal.id}`}>
            Open in Orchestrator
          </a>
        </span>
      </div>
      <p className="chat-live-step caption muted">
        {total > 1 && `Step ${index + 1} of ${total} · `}
        {stepText(task)}
      </p>
      <div className="chat-live-bar" data-parked={parked || undefined} aria-hidden="true" />
    </div>
  )
}

// @find: ProgressCard, progress card, goal progress, live goal, chain of agents, task steps
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
  const approvalRef = useRef<HTMLDivElement | null>(null)

  // A "Review" request for this goal opens the card at once, during render (the "previous value"
  // pattern, 0.2), so the decision is never still hidden in the frame that scrolls to it. A request
  // already made when the card mounts (the thread shown again later) is an old one, not a new one.
  const revealNonce = details.revealGoal?.goalId === goal.id ? details.revealGoal.nonce : null
  const [seenRevealNonce, setSeenRevealNonce] = useState(revealNonce)
  const [revealing, setRevealing] = useState<number | null>(null)
  if (revealNonce !== seenRevealNonce) {
    setSeenRevealNonce(revealNonce)
    if (revealNonce !== null) {
      setRevealing(revealNonce)
      if (!open) setOpen(true)
    }
  }

  useEffect(() => {
    if (revealing === null) return
    let attempts = 0
    let timer = 0
    const look = () => {
      const holder = approvalRef.current
      const button = holder?.querySelector<HTMLElement>('[data-approve-button]')
      if (button) {
        button.scrollIntoView?.({ block: 'center' })
        button.focus()
        return
      }
      // The approval may still be loading (or this role may only read it): show where it will be.
      if (attempts === 0) holder?.scrollIntoView?.({ block: 'center' })
      attempts += 1
      if (attempts < REVEAL_ATTEMPTS) timer = window.setTimeout(look, REVEAL_RETRY_MS)
    }
    const frame = window.requestAnimationFrame(look)
    return () => {
      window.cancelAnimationFrame(frame)
      window.clearTimeout(timer)
    }
  }, [revealing])

  const tasks = [...goal.tasks].sort((a, b) => a.position - b.position)
  const currentIdx = currentTaskIndex(tasks)
  const waitingTask = tasks.find((task) => task.status === 'waiting_approval')
  const failedIndex = tasks.findIndex((task) => task.status === 'failed')
  const showCost = canSeeSpend()
  const summary = progressSummary(goal, showCost)
  const stoppable = canStopGoal(goal, me, can)
  const retryable = canRetryGoal(goal, me, can)

  // One agent that finished cleanly needs no chain drawn out: its answer sits just below. A single
  // line keeps the receipt (who, how long, where to look) without a second card in the thread.
  const soloDone = finished && goal.status === 'completed' && tasks.length === 1 && details.mode !== 'expanded'
  if (soloDone) {
    const solo = tasks[0]
    const soloName = solo?.agentId ? (agentNames[solo.agentId]?.name ?? 'The agent') : 'The agent'
    return (
      <article className="chat-progress-line" aria-label={`Progress: ${soloName} finished`}>
        <svg className="chat-progress-line-icon" width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <circle cx="8" cy="8" r="7" stroke="currentColor" strokeWidth="1.4" />
          <path d="M5 8.2l2 2 4-4.4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        <span>
          <strong className="chat-progress-line-name">{soloName}</strong> finished · {summary.replace(/^Done in /, '')}
        </span>
        <a className="link" href={`/orchestrator?goal=${goal.id}`}>
          Open in Orchestrator
        </a>
      </article>
    )
  }

  const currentTask = currentIdx === -1 ? tasks[tasks.length - 1] : tasks[currentIdx]
  const stopDialog = (
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
  )

  const chain = (
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
  )

  const approval = waitingTask?.runId ? (
    <div ref={approvalRef}>
      <InlineApproval runId={waitingTask.runId} agentName={waitingTask.agentId ? (agentNames[waitingTask.agentId]?.name ?? 'The agent') : 'The agent'} />
    </div>
  ) : null

  if (!finished) {
    return (
      <Card as="article" className="chat-progress-card chat-live-card">
        <LiveHeader
          goal={goal}
          task={currentTask}
          index={Math.max(currentIdx, 0)}
          total={tasks.length}
          agent={currentTask?.agentId ? agentNames[currentTask.agentId] : undefined}
          stoppable={stoppable}
          onAskStop={() => setConfirmStop(true)}
        />
        {tasks.length > 1 && (
          <Collapsible title="Progress" summary={summary} open={open} onToggle={setOpen} headingLevel="p">
            {chain}
          </Collapsible>
        )}
        {approval}
        {stopDialog}
      </Card>
    )
  }

  return (
    <Card as="article" className="chat-progress-card">
      <Collapsible
        title="Progress"
        summary={summary}
        open={open}
        onToggle={setOpen}
        headingLevel="p"
        actions={
          <div className="row" style={{ gap: 'var(--space-3)' }}>
            {(goal.status === 'failed' || goal.status === 'cancelled') && retryable && (
              <button type="button" className="button button-outline button-sm" onClick={() => setConfirmRetry(true)}>
                Try again
              </button>
            )}
            <a className="link caption" href={`/orchestrator?goal=${goal.id}`}>
              Open in Orchestrator
            </a>
          </div>
        }
      >
        <p className="caption muted chat-progress-goal" title={goal.title}>
          {truncateWords(goal.title, 140)}
        </p>
        {chain}
        {approval}
      </Collapsible>

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
