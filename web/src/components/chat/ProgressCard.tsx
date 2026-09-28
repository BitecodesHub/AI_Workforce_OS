import { useEffect, useState } from 'react'
import { RunTraceCompact } from '../run/RunTraceCompact'
import { Button, Card, Eyebrow, Notice, Tag } from '../ui'
import { decisionError, formatPayload, readableSummary } from '../../lib/approvals'
import { formatElapsed, truncateWords } from '../../lib/format'
import { statusLabel } from '../../lib/labels'
import { useApprovals, useDecideApproval } from '../../lib/queries'
import type { Agent, Approval, Goal, Task } from '../../lib/queries'
import { can } from '../../lib/session'
import { AgentAvatar } from './AgentAvatar'
import { currentTaskIndex } from './chatModel'

/*
 * A goal's chain, live: which agent has which task, who is up next, and, the moment a step needs
 * a person, the decision itself rather than a link to go find it. Each step's own trace is one
 * click away without leaving the thread.
 */

function TaskChainStep({ task, agent, isCurrent }: { task: Task; agent: Agent | undefined; isCurrent: boolean }) {
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
        <AgentAvatar name={name} category={agent?.category} />
        <span className="chat-chain-pill-name">{name}</span>
        <Tag tone={status.tone}>{status.label}</Tag>
        {isCurrent && <span className="chat-pulse-dot" aria-hidden="true" />}
      </button>
      {open && (
        <div className="chat-chain-step-body">
          {task.runId ? (
            <RunTraceCompact runId={task.runId} headingLevel="h4" limit={4} />
          ) : (
            <p className="caption muted">This task has not started yet.</p>
          )}
        </div>
      )}
    </div>
  )
}

function InlineApproval({ task, agentName }: { task: Task; agentName: string }) {
  const canRead = can('approval:read')
  const canDecide = can('approval:decide')
  const approvalsQuery = useApprovals({ enabled: canRead })
  const decide = useDecideApproval()
  const [busy, setBusy] = useState<'approve' | 'reject' | null>(null)
  const [error, setError] = useState<string | null>(null)

  const approval: Approval | undefined = approvalsQuery.data?.find(
    (candidate) => candidate.runId === task.runId && candidate.status === 'pending',
  )
  // The approvals list is cached and refreshed every half minute, so a request raised moments
  // ago is usually not in it yet. Ask again as soon as this card appears, and once more while it
  // is still missing, rather than telling a person who can decide that someone else must.
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

  async function decideIt(approved: boolean) {
    setBusy(approved ? 'approve' : 'reject')
    setError(null)
    try {
      await decide.mutateAsync({ id: approval!.id, approved })
    } catch (err) {
      setError(decisionError(err))
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="chat-inline-approval">
      <p className="caption muted">{agentName} is waiting for a decision</p>
      <p>{readableSummary(approval)}</p>
      <pre className="chat-inline-approval-payload">{formatPayload(approval.payload)}</pre>
      {error && (
        <Notice tone="warning" live>
          {error}
        </Notice>
      )}
      {canDecide ? (
        <div className="row" style={{ gap: 'var(--space-3)', marginTop: 'var(--space-3)' }}>
          <Button onClick={() => void decideIt(true)} loading={busy === 'approve'} disabled={busy === 'reject'}>
            Approve
          </Button>
          <Button variant="outline" onClick={() => void decideIt(false)} loading={busy === 'reject'} disabled={busy === 'approve'}>
            Reject
          </Button>
        </div>
      ) : (
        <Notice tone="info">Waiting for someone who can approve actions.</Notice>
      )}
    </div>
  )
}

export function ProgressCard({ goal, agentNames, now }: { goal: Goal; agentNames: Record<string, Agent>; now: number }) {
  const tasks = [...goal.tasks].sort((a, b) => a.position - b.position)
  const currentIdx = currentTaskIndex(tasks)
  const waitingTask = tasks.find((task) => task.status === 'waiting_approval')
  const overall = statusLabel('goal', goal.status)
  const finished = goal.status === 'completed' || goal.status === 'failed' || goal.status === 'cancelled'
  const elapsed = finished
    ? goal.completedAt
      ? `Took ${formatElapsed(goal.createdAt, goal.completedAt)}`
      : null
    : `Running ${formatElapsed(goal.createdAt, null, now)}`

  return (
    <Card as="article" className="chat-progress-card">
      <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-3)', flexWrap: 'wrap' }}>
        <Eyebrow as="h2">Progress</Eyebrow>
        <Tag tone={overall.tone}>{overall.label}</Tag>
      </div>
      <p className="section-heading" style={{ marginBottom: 'var(--space-1)' }}>
        {truncateWords(goal.title, 140)}
      </p>
      {elapsed && (
        <p className="caption muted" style={{ marginBottom: 'var(--space-4)' }}>
          {elapsed}
        </p>
      )}

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
            <TaskChainStep task={task} agent={task.agentId ? agentNames[task.agentId] : undefined} isCurrent={index === currentIdx} />
          </li>
        ))}
      </ol>

      {waitingTask && (
        <InlineApproval
          task={waitingTask}
          agentName={waitingTask.agentId ? (agentNames[waitingTask.agentId]?.name ?? 'The agent') : 'The agent'}
        />
      )}
    </Card>
  )
}
