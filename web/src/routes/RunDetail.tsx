// @find: run detail, run trace, steps, what happened, answer, incomplete answer, tool calls, completed actions, cost, tokens, goal, cancel run, retry, /runs/:id, RunDetail
// @what: The Run detail page: the step-by-step record of one run, with its answer, actions taken and facts.
// @flow: Routed from App.tsx at /runs/:id; opened from Runs.tsx and Tasks.tsx
import { useEffect, useState } from 'react'
import type { CSSProperties } from 'react'
import { Button, Card, ConfirmDialog, EmptyState, Eyebrow, Notice, PageHeader, StatusTag, Time } from '../components/ui'
import { Markdown } from '../components/ui/Markdown'
import { BackLink, EmptyIcon, QueryState } from '../components/ui/QueryState'
import { RunRatings } from '../components/analytics/RunRatings'
import { AnswerCard } from '../components/run/AnswerCard'
import { RunStats } from '../components/run/RunStats'
import { TraceStep } from '../components/run/TraceStep'
import { WaitingForAnswer } from '../components/run/WaitingForAnswer'
import { WaitingForApproval } from '../components/run/WaitingForApproval'
import { completedAnswer, incompleteAnswerText, isInstruction, CANCELLED_BY, detailText } from '../components/run/traceModel'
import { describeApiError } from '../lib/api'
import { truncateWords } from '../lib/format'
import { startedByLabel, toolLabel } from '../lib/labels'
import { isStepDone, markStepDone } from '../lib/onboarding'
import {
  isRunActive,
  useAgentNames,
  useAgents,
  useCancelRun,
  useMemberNames,
  useRetryGoal,
  useRetryRun,
  useRun,
  useRunSteps,
  useTaskIndex,
} from '../lib/queries'
import type { Goal, Run, Task } from '../lib/queries'
import { useDocumentTitle, useRouter } from '../lib/router'
import { can, profile } from '../lib/session'
import { useToast } from '../lib/toast'
import { useNow } from '../lib/useNow'

/*
 * One run, step by step.
 *
 * The page answers, top to bottom: which agent this was and what started it, whether it is
 * finished and how (or what it is waiting for, and where to decide it), what it answered, what
 * it cost, and then every step it took, beginning with what it was asked.
 *
 * The step-reading rules and the pieces below (AnswerCard, RunStats, TraceStep,
 * WaitingForApproval) live in components/run/, shared with RunTraceCompact wherever a run's
 * progress needs to show more briefly than this full page.
 *
 * A run that stopped at its step limit or its output limit still wrote something. It is shown
 * as an incomplete answer, apart from the answer a finished run gives, so nobody takes it for one.
 */

// @find: RunDetail component, run page, run trace, /runs/:id
export function RunDetail({ id }: { id: string }) {
  const runQuery = useRun(id)
  const run = runQuery.data
  const agents = useAgentNames()
  const agentName = run ? agents[run.agentId]?.name : undefined
  const taskIndex = useTaskIndex({ enabled: can('task:read') && Boolean(run?.taskId) })
  const goal = run?.taskId ? taskIndex[run.taskId]?.goal : undefined
  const task = run?.taskId ? taskIndex[run.taskId]?.task : undefined

  useDocumentTitle(agentName ? `${agentName} run` : null)

  // Opening a trace is the getting-started step "Read the trace of a run", wherever it was opened from.
  const loadedRunId = run?.id
  useEffect(() => {
    const userId = profile()?.userId
    if (loadedRunId && userId && !isStepDone(userId, 'read-trace')) markStepDone(userId, 'read-trace')
  }, [loadedRunId])

  // Shown while the run loads and when it cannot, so there is always a way out.
  const back = goal ? { href: `/tasks?goal=${goal.id}`, label: 'Back to goal' } : { href: '/runs', label: 'Back to runs' }

  return (
    <div className="page">
      <BackLink href={back.href} label={back.label} />
      <QueryState
        query={runQuery}
        permission="run:read"
        what="this run"
        rows={4}
        notFound={
          <a className="button button-outline" href="/runs">
            See every run
          </a>
        }
      >
        {(loaded) => <RunTrace run={loaded} agentName={agentName} goal={goal} task={task} />}
      </QueryState>
    </div>
  )
}

// @find: run trace, step list, tool call details
function RunTrace({
  run,
  agentName,
  goal,
  task,
}: {
  run: Run
  agentName: string | undefined
  goal: Goal | undefined
  task: Task | undefined
}) {
  const toast = useToast()
  const active = isRunActive(run)
  const stepsQuery = useRunSteps(run.id, { active })
  const steps = stepsQuery.data
  const cancelRun = useCancelRun()
  const retryRun = useRetryRun()
  const retryGoal = useRetryGoal()
  const { navigate } = useRouter()
  const [retryOpen, setRetryOpen] = useState(false)
  const [retryError, setRetryError] = useState<string | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [cancelError, setCancelError] = useState<string | null>(null)
  // A running clock ticks each second; otherwise the minute is enough for "started 5 minutes ago".
  const now = useNow(run.status === 'running' ? 1_000 : 60_000)

  const cancelledBy = run.failureReason ? CANCELLED_BY.exec(run.failureReason.trim())?.[1] : undefined
  const members = useMemberNames({ enabled: Boolean(cancelledBy) && can('member:read') })
  const failureText = cancelledBy
    ? `Cancelled by ${members[cancelledBy]?.displayName ?? 'a workspace member'}.`
    : run.failureReason

  const canCancel = can('run:cancel') && active

  // Try again: a goal's run goes back through its goal, which starts from the step that did not
  // finish; a run given straight to an agent starts afresh with the same instruction.
  const retryable = run.status === 'failed' || run.status === 'abandoned' || run.status === 'cancelled'
  const canRetry = retryable && (goal ? can('task:create') : can('agent:run'))
  const alreadyDone = completedActions(steps)

  const confirmRetry = async () => {
    setRetryError(null)
    try {
      if (goal) {
        await retryGoal.mutateAsync(goal.id)
        toast.success('Trying again.')
        setRetryOpen(false)
        navigate(`/tasks?goal=${goal.id}`)
      } else {
        const started = await retryRun.mutateAsync(run.id)
        toast.success('Trying again.')
        setRetryOpen(false)
        navigate(`/runs/${started.runId}`)
      }
    } catch (error) {
      setRetryError(describeApiError(error))
    }
  }

  const confirmCancel = async () => {
    setCancelError(null)
    try {
      await cancelRun.mutateAsync(run.id)
      setConfirmOpen(false)
      toast.success('Run stopped.')
    } catch (error) {
      setCancelError(describeApiError(error))
    }
  }

  // The task keeps the whole of what a run wrote, but only for the attempt it last made.
  const savedResult = task && task.runId === run.id ? task.result : null
  const answer = run.status === 'completed' && steps ? completedAnswer(steps, savedResult) : undefined
  const incomplete = run.status === 'failed' ? incompleteAnswerText(steps, savedResult) : null
  const instruction = steps?.find(isInstruction)
  const instructionText = instruction ? detailText(instruction.detail, 'content') : null

  return (
    <>
      <PageHeader
        eyebrow="Run trace"
        title={`${agentName ?? 'Agent'} run`}
        description="Every step the agent took, in order: what it was asked, which model answered and which tools it used."
        meta={<RunFacts run={run} agentName={agentName} goal={goal} />}
        action={
          <>
            <StatusTag kind="run" status={run.status} />
            {canCancel && (
              <Button
                variant="outline"
                onClick={() => {
                  setCancelError(null)
                  setConfirmOpen(true)
                }}
              >
                Stop run
              </Button>
            )}
            {canRetry && (
              <Button
                onClick={() => {
                  setRetryError(null)
                  setRetryOpen(true)
                }}
              >
                Try again
              </Button>
            )}
          </>
        }
      />

      <ConfirmDialog
        open={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        onConfirm={confirmCancel}
        eyebrow="Stop a run"
        title="Stop this run?"
        description={
          // A parked run ends at once. A run mid-step is marked stopped, but the platform does not
          // interrupt a model or tool call already under way, so that is not promised.
          run.status === 'waiting_approval'
            ? 'The run ends here, and the approval it is waiting on is withdrawn.'
            : run.status === 'waiting_input'
              ? 'The run ends here, and the question it is waiting on is withdrawn.'
              : 'The run is marked as stopped. A model or tool call already under way is not interrupted.'
        }
        confirmLabel="Stop run"
        cancelLabel="Keep it running"
        tone="danger"
        loading={cancelRun.isPending}
        error={cancelError}
      />

      <ConfirmDialog
        open={retryOpen}
        onClose={() => setRetryOpen(false)}
        onConfirm={confirmRetry}
        eyebrow="Try again"
        title="Try this again?"
        description={
          goal
            ? 'The task starts again from the step that did not finish.'
            : 'A new run starts with the same instruction. This run stays as it is.'
        }
        confirmLabel="Try again"
        cancelLabel="Not now"
        tone="primary"
        loading={retryRun.isPending || retryGoal.isPending}
        error={retryError}
      >
        {alreadyDone.length > 0 ? (
          <>
            <p style={{ marginBottom: 'var(--space-2)' }}>
              The last attempt already did this, and trying again will not undo it:
            </p>
            <ul className="stack" style={{ gap: 'var(--space-1)', margin: 0, paddingLeft: 'var(--space-5)' }}>
              {alreadyDone.map((line, index) => (
                <li key={index} className="caption" style={{ overflowWrap: 'anywhere' }}>
                  {line}
                </li>
              ))}
            </ul>
          </>
        ) : (
          <p className="muted">The last attempt changed nothing outside the platform.</p>
        )}
      </ConfirmDialog>

      <div className="page-sections">
        {failureText && <Notice tone={run.status === 'cancelled' ? 'info' : 'warning'}>{failureText}</Notice>}

        {run.status === 'waiting_approval' && <WaitingForApproval run={run} steps={steps} />}
        {run.status === 'waiting_input' && <WaitingForAnswer run={run} />}

        {answer && <AnswerCard step={answer} instruction={instructionText} />}
        {incomplete && <IncompleteAnswer text={incomplete} instruction={instructionText} />}

        {/* What people said of the answer, with the reasons; absent when nobody has rated it. */}
        <RunRatings runId={run.id} />

        <div>
          <RunStats run={run} steps={steps} now={now} />
          <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
            From the run's own record, written as it went.
          </p>
        </div>

        <QueryState
          query={stepsQuery}
          permission="run:read"
          what="this run's trace"
          rows={Math.max(4, Math.min(run.stepCount, 12))}
        >
          {(loaded) =>
            loaded.length > 0 ? (
              <Card as="section">
                <Eyebrow as="h2">What happened</Eyebrow>
                <ol className="trace-steps">
                  {/* What the agent was asked comes first, whatever position it was stored at. */}
                  {[...loaded.filter(isInstruction), ...loaded.filter((step) => !isInstruction(step))].map((step) => (
                    <TraceStep key={step.id} step={step} />
                  ))}
                </ol>
              </Card>
            ) : (
              <Card as="section">
                <EmptyState
                  icon={<EmptyIcon kind="task" />}
                  title="No steps recorded"
                  body="Nothing has been written to this run's trace."
                />
              </Card>
            )
          }
        </QueryState>
      </div>
    </>
  )
}

/**
 * What a run already did that a second attempt will not take back: each tool call that succeeded
 * and changed something (anything but a read), as a line of words.
 */
// @find: completed actions list, what was already done in a run
export function completedActions(steps: ReadonlyArray<{ kind: string; detail: Record<string, unknown> }> | undefined): string[] {
  if (!steps) return []
  const lines: string[] = []
  for (const step of steps) {
    if (step.kind !== 'tool_call') continue
    const status = detailText(step.detail, 'status')
    const effect = detailText(step.detail, 'sideEffect')
    if (status !== 'SUCCEEDED' || !effect || effect === 'READ') continue
    const tool = toolLabel(detailText(step.detail, 'tool') ?? '')
    const summary = detailText(step.detail, 'summary')
    lines.push(summary ? `${tool}: ${summary}` : tool)
  }
  return lines
}

/**
 * What a run wrote before it stopped at a limit. Set apart from an answer, with its own heading
 * and a sentence saying it is unfinished, because a half-written report read as a whole one is
 * worse than none.
 */
// @find: incomplete answer notice, unfinished answer
export function IncompleteAnswer({ text, instruction }: { text: string; instruction?: string | null }) {
  return (
    <Card as="section">
      <Eyebrow as="h2">Incomplete answer</Eyebrow>
      {instruction && (
        <p className="caption" style={{ marginBottom: 'var(--space-3)', overflowWrap: 'anywhere' }}>
          Asked: {truncateWords(instruction, 200)}
        </p>
      )}
      <p className="muted" style={{ marginBottom: 'var(--space-3)' }}>
        The agent stopped before it finished, so this may be missing parts. Check it before you rely on it.
      </p>
      <Markdown text={text} />
    </Card>
  )
}

/**
 * The facts under the title: the agent, what started the run, when, and its id. Each is labelled,
 * so they still read correctly when a narrow screen wraps them onto separate lines.
 */
// @find: run facts, agent, goal, cost, duration, status
function RunFacts({ run, agentName, goal }: { run: Run; agentName: string | undefined; goal: Goal | undefined }) {
  const value: CSSProperties = { color: 'var(--ink)' }
  // Named only once the agent list has it; an agent missing from the list has been removed, and a
  // link to it would open a page that does not exist.
  const agents = useAgents()
  return (
    <div className="caption" style={{ display: 'flex', flexWrap: 'wrap', columnGap: 'var(--space-5)', rowGap: 'var(--space-1)' }}>
      <span>
        Agent{' '}
        {agentName && can('agent:read') ? (
          <a className="link" href={`/agents/${run.agentId}`}>
            {agentName}
          </a>
        ) : (
          <span style={value}>{agentName ?? (agents.isLoading ? 'Loading…' : 'Unknown agent')}</span>
        )}
      </span>
      <span>
        Started by{' '}
        {goal ? (
          <a className="link" href={`/tasks?goal=${goal.id}`} title={goal.title}>
            <span className="visually-hidden">the goal </span>
            {truncateWords(goal.title, 60)}
          </a>
        ) : (
          <span style={value}>{startedByLabel(run)}</span>
        )}
      </span>
      <span>
        Started <Time iso={run.startedAt} className="tabular" />
      </span>
      <span style={{ overflowWrap: 'anywhere' }}>
        Run id <span className="mono" style={value}>{run.id}</span>
      </span>
    </div>
  )
}
