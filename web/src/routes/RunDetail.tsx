import { useEffect, useState } from 'react'
import type { CSSProperties } from 'react'
import {
  Button,
  Card,
  ConfirmDialog,
  EmptyState,
  Eyebrow,
  Notice,
  PageHeader,
  StatRow,
  StatTile,
  StatusTag,
  Tag,
  Time,
} from '../components/ui'
import type { TagTone } from '../components/ui'
import { BackLink, EmptyIcon, QueryState } from '../components/ui/QueryState'
import { describeApiError } from '../lib/api'
import {
  formatCount,
  formatDuration,
  formatElapsed,
  formatMoney,
  formatRelative,
  sentenceCase,
  truncateWords,
} from '../lib/format'
import { startedByLabel, statusLabel, toolLabel } from '../lib/labels'
import { isStepDone, markStepDone } from '../lib/onboarding'
import {
  isRunActive,
  useAgentNames,
  useAgents,
  useApprovals,
  useCancelRun,
  useMemberNames,
  useRun,
  useRunSteps,
  useTaskIndex,
} from '../lib/queries'
import type { Goal, Run, RunStep } from '../lib/queries'
import { useDocumentTitle } from '../lib/router'
import { can, profile } from '../lib/session'
import { useToast } from '../lib/toast'
import { useNow } from '../lib/useNow'

/*
 * One run, step by step.
 *
 * The page answers, top to bottom: which agent this was and what started it, whether it is
 * finished and how (or what it is waiting for, and where to decide it), what it answered, what
 * it cost, and then every step it took, beginning with what it was asked.
 */

/* ---- Reading step details ------------------------------------------------------------------------ */

function detailText(detail: Record<string, unknown>, key: string): string | null {
  const value = detail[key]
  return typeof value === 'string' && value.trim() ? value : null
}

function detailStrings(detail: Record<string, unknown>, key: string): string[] {
  const value = detail[key]
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
}

function detailNumber(detail: Record<string, unknown>, key: string): number | null {
  const value = detail[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

/** The kinds of step a trace can hold (run_steps.kind), as a tag. */
const STEP_KIND: Record<string, { tone: TagTone; label: string }> = {
  model_call: { tone: 'blue', label: 'Model' },
  tool_call: { tone: 'operations', label: 'Tool' },
  approval: { tone: 'warning', label: 'Approval' },
  error: { tone: 'danger', label: 'Error' },
  handoff: { tone: 'neutral', label: 'Handoff' },
  knowledge_query: { tone: 'neutral', label: 'Knowledge search' },
  memory_read: { tone: 'neutral', label: 'Memory read' },
  memory_write: { tone: 'neutral', label: 'Memory write' },
  note: { tone: 'neutral', label: 'Note' },
}

/** The first step of every run since the platform began recording it: what the agent was asked. */
const isInstruction = (step: RunStep) => step.kind === 'note' && step.detail.type === 'instruction'

const isSandboxStep = (step: RunStep) => step.kind === 'model_call' && step.provider === 'sandbox'

function stepHeading(step: RunStep): string | null {
  switch (step.kind) {
    case 'model_call':
      if (!step.provider) return 'Model call'
      return step.model ? `${step.provider} / ${step.model}` : step.provider
    case 'tool_call':
    case 'approval':
      return toolLabel(detailText(step.detail, 'tool'))
    case 'error':
      return sentenceCase(detailText(step.detail, 'code')) || 'The run stopped'
    default: {
      const tool = detailText(step.detail, 'tool')
      if (tool) return toolLabel(tool)
      const type = sentenceCase(detailText(step.detail, 'type'))
      return type || null
    }
  }
}

function stepDescription(step: RunStep): string | null {
  return (
    detailText(step.detail, 'summary') ??
    detailText(step.detail, 'reason') ??
    detailText(step.detail, 'detail') ??
    detailText(step.detail, 'content')
  )
}

/** The last model reply with any text: what a completed run answered. */
function answerStep(steps: RunStep[]): RunStep | undefined {
  for (let index = steps.length - 1; index >= 0; index -= 1) {
    const step = steps[index]!
    if (step.kind === 'model_call' && detailText(step.detail, 'content')) return step
  }
  return undefined
}

/** The approval the run is paused on, from the latest approval step. */
function latestApprovalId(steps: RunStep[] | undefined): string | null {
  if (!steps) return null
  for (let index = steps.length - 1; index >= 0; index -= 1) {
    const step = steps[index]!
    if (step.kind === 'approval') return detailText(step.detail, 'approvalId')
  }
  return null
}

/**
 * One routing attempt as the platform wrote it ('openrouter/gpt-4o answered in 5234 ms'), with
 * its time in the same form as every other duration on the page.
 */
const readableAttempt = (attempt: string) =>
  attempt.replace(/answered in (\d+) ms$/, (_, ms: string) => `answered in ${formatDuration(Number(ms))}`)

const withoutFinalStop = (text: string) => text.trim().replace(/[.\s]+$/, '')

/** Runs cancelled before the reason was written for people carry the canceller's user id. */
const CANCELLED_BY = /^Cancelled by (.+)$/

/* ---- The screen ------------------------------------------------------------------------------------ */

export function RunDetail({ id }: { id: string }) {
  const runQuery = useRun(id)
  const run = runQuery.data
  const agents = useAgentNames()
  const agentName = run ? agents[run.agentId]?.name : undefined
  const taskIndex = useTaskIndex({ enabled: can('task:read') && Boolean(run?.taskId) })
  const goal = run?.taskId ? taskIndex[run.taskId]?.goal : undefined

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
        {(loaded) => <RunTrace run={loaded} agentName={agentName} goal={goal} />}
      </QueryState>
    </div>
  )
}

function RunTrace({ run, agentName, goal }: { run: Run; agentName: string | undefined; goal: Goal | undefined }) {
  const toast = useToast()
  const active = isRunActive(run)
  const stepsQuery = useRunSteps(run.id, { active })
  const steps = stepsQuery.data
  const cancelRun = useCancelRun()
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

  const answer = run.status === 'completed' && steps ? answerStep(steps) : undefined
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
            : 'The run is marked as stopped. A model or tool call already under way is not interrupted.'
        }
        confirmLabel="Stop run"
        cancelLabel="Keep it running"
        tone="danger"
        loading={cancelRun.isPending}
        error={cancelError}
      />

      <div className="stack" style={{ gap: 'var(--space-6)' }}>
        {failureText && <Notice tone={run.status === 'cancelled' ? 'info' : 'warning'}>{failureText}</Notice>}

        {run.status === 'waiting_approval' && <WaitingForApproval run={run} steps={steps} />}

        {answer && <AnswerCard step={answer} instruction={instructionText} />}

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
                <ol className="stack" style={{ gap: 'var(--space-5)', listStyle: 'none', margin: 0, padding: 0 }}>
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
 * The facts under the title: the agent, what started the run, when, and its id. Each is labelled,
 * so they still read correctly when a narrow screen wraps them onto separate lines.
 */
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

/**
 * A run held for a person's decision: what it is waiting for and where to decide it, or, for a
 * role that cannot see the queue, who can.
 */
function WaitingForApproval({ run, steps }: { run: Run; steps: RunStep[] | undefined }) {
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

function AnswerCard({ step, instruction }: { step: RunStep; instruction: string | null }) {
  const sandbox = isSandboxStep(step)
  return (
    <Card as="section">
      <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap', alignItems: 'baseline' }}>
        <Eyebrow as="h2">Answer</Eyebrow>
        {sandbox && <Tag tone="neutral">Offline sandbox</Tag>}
      </div>
      {instruction && (
        <p className="caption" style={{ marginBottom: 'var(--space-3)', overflowWrap: 'anywhere' }}>
          Asked: {truncateWords(instruction, 200)}
        </p>
      )}
      <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{detailText(step.detail, 'content')}</p>
      {sandbox && (
        <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
          A placeholder answer from the offline model, not a real model's reply.
        </p>
      )}
    </Card>
  )
}

function RunStats({ run, steps, now }: { run: Run; steps: RunStep[] | undefined; now: number }) {
  const cost = Number(run.cost) || 0
  const modelSteps = steps?.filter((step) => step.kind === 'model_call') ?? []
  const sandboxOnly = modelSteps.length > 0 && modelSteps.every(isSandboxStep)
  const PRICED = 'At catalogue prices per million tokens'
  // A run that cost nothing is free only when the offline sandbox answered every call; otherwise
  // it is a real figure of zero. Until the steps arrive there is no telling which.
  const costTile =
    cost > 0
      ? { value: formatMoney(cost), note: PRICED }
      : steps === undefined
        ? { value: '—', note: undefined }
        : sandboxOnly
          ? { value: 'Free', note: 'Offline sandbox' }
          : { value: formatMoney(0), note: PRICED }

  let duration: { value: string; note: string }
  if (run.status === 'running') {
    duration = { value: formatElapsed(run.startedAt, null, now), note: 'So far; the run is still going' }
  } else if (run.status === 'waiting_approval') {
    duration = { value: 'Paused', note: `Waiting for an approval. Started ${formatRelative(run.startedAt, now)}.` }
  } else if (run.completedAt) {
    duration = { value: formatElapsed(run.startedAt, run.completedAt), note: 'From start to finish' }
  } else {
    duration = { value: '—', note: 'No finish time was recorded' }
  }

  return (
    <StatRow>
      <StatTile label="Model turns" value={formatCount(run.stepCount)} note="One per call to a model" />
      <StatTile label="Duration" value={duration.value} note={duration.note} />
      <StatTile
        label="Tokens"
        value={formatCount(run.promptTokens + run.completionTokens)}
        note={`${formatCount(run.promptTokens)} prompt and ${formatCount(run.completionTokens)} completion`}
      />
      <StatTile label="Estimated cost" value={costTile.value} {...(costTile.note ? { note: costTile.note } : {})} />
    </StatRow>
  )
}

const STEP_STYLE: CSSProperties = {
  borderLeft: '1px solid var(--line)',
  paddingLeft: 'var(--space-5)',
  paddingBottom: 'var(--space-2)',
}

const PROSE: CSSProperties = { whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }

function TraceStep({ step }: { step: RunStep }) {
  const time = (
    <p className="caption muted" style={{ marginTop: 'var(--space-2)', marginBottom: 0 }}>
      <Time iso={step.occurredAt} mode="absolute" />
    </p>
  )

  if (isInstruction(step)) {
    return (
      <li style={STEP_STYLE}>
        <div className="row" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-2)', flexWrap: 'wrap' }}>
          <Tag tone="blue">Instruction</Tag>
          <h3 className="section-heading">What the agent was asked</h3>
        </div>
        <p style={PROSE}>{detailText(step.detail, 'content') ?? 'The instruction was empty.'}</p>
        {time}
      </li>
    )
  }

  const kind = STEP_KIND[step.kind] ?? { tone: 'neutral' as const, label: sentenceCase(step.kind) }
  const heading = stepHeading(step)
  const status = step.kind === 'tool_call' ? detailText(step.detail, 'status') : null
  const toolStatus = status ? statusLabel('toolCall', status) : null
  // A model step's time is its own; a tool call's is recorded in its detail.
  const ms = step.durationMs > 0 ? step.durationMs : (detailNumber(step.detail, 'durationMs') ?? 0)
  const description = stepDescription(step)
  const toolCalls = detailStrings(step.detail, 'toolCalls')
  const attempts = detailStrings(step.detail, 'attempts')

  return (
    <li style={STEP_STYLE}>
      <div className="row" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-2)', flexWrap: 'wrap' }}>
        <Tag tone={kind.tone}>{kind.label}</Tag>
        {heading && heading !== kind.label && (
          <h3 className="section-heading" style={{ overflowWrap: 'anywhere' }}>
            {heading}
          </h3>
        )}
        {toolStatus && <Tag tone={toolStatus.tone}>{toolStatus.label}</Tag>}
        {isSandboxStep(step) && <Tag tone="neutral">Offline sandbox</Tag>}
        {ms > 0 && <span className="caption tabular">{formatDuration(ms)}</span>}
      </div>

      {description && (
        <p className="muted" style={{ ...PROSE, marginBottom: 'var(--space-3)' }}>
          {description}
        </p>
      )}

      {toolCalls.length > 0 && (
        <p className="caption muted" style={{ marginBottom: 'var(--space-2)' }}>
          Asked to use: {toolCalls.map((name) => toolLabel(name)).join(', ')}
        </p>
      )}

      {attempts.length > 0 && (
        <>
          <p className="caption muted" style={{ marginBottom: 'var(--space-1)' }}>
            Attempts
          </p>
          <ul className="stack" style={{ gap: 'var(--space-1)', margin: 0, paddingLeft: 'var(--space-5)' }}>
            {attempts.map((attempt, index) => (
              <li key={`${step.id}-attempt-${index}`} className="caption">
                {readableAttempt(attempt)}
              </li>
            ))}
          </ul>
        </>
      )}

      {time}
    </li>
  )
}
