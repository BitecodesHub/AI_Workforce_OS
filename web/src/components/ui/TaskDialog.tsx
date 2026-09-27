import { useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Dialog, Notice, Select, Textarea } from './index'
import { ApiError, describeApiError } from '../../lib/api'
import { sentenceCase, truncateWords } from '../../lib/format'
import { CATEGORY_LABEL, statusLabel } from '../../lib/labels'
import { markStepDone } from '../../lib/onboarding'
import { useAgents, useCreateGoal, useCreateRun } from '../../lib/queries'
import type { Agent, Goal, RunStarted } from '../../lib/queries'
import { useRouter } from '../../lib/router'
import { can, profile } from '../../lib/session'
import { useToast } from '../../lib/toast'

/*
 * Give an agent a task.
 *
 * The same dialog opens from several places: the Command Map, an agent's own page, Tasks and
 * Runs. From an agent's page the agent is already decided, so the dialog only asks what to do; it
 * starts a run directly. From everywhere else it asks which agent, and creates a goal with that
 * one task, which the orchestrator starts at once unless earlier work is still queued.
 *
 * Either way the request waits while the agent works: the platform drives a run until it finishes
 * or stops for an approval before it answers. The dialog says so, cannot be dismissed half way,
 * and then opens the run's trace, so the person lands on what the agent actually did.
 */

/** Where a task ended up once the dialog's request came back. */
export type TaskStarted = {
  /** The run the task started, when one started. */
  runId: string | undefined
  /** The goal created for the task, when the dialog created one. */
  goalId: string | undefined
  /** Where the dialog would go next: the run's trace, or the goal on Tasks when no run started. */
  href: string
}

interface TaskDialogProps {
  open: boolean
  onClose: () => void
  /** Starts a run for this agent directly. Without it the dialog asks which agent, and creates a goal. */
  agentId?: string
  /** Replaces the default of opening `started.href` once the task has started. */
  onSuccess?: (runId: string | undefined, started: TaskStarted) => void
}

export function TaskDialog({ open, onClose, agentId, onSuccess }: TaskDialogProps) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // A new opening starts clean: the error from a previous attempt belongs to that attempt.
  const [wasOpen, setWasOpen] = useState(open)
  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) {
      setError(null)
      setBusy(false)
    }
  }

  // Closing while the request is in flight would not stop the agent, only hide what it did.
  const close = () => {
    if (!busy) onClose()
  }

  return (
    <Dialog
      open={open}
      onClose={close}
      dismissible={!busy}
      error={error}
      eyebrow="Give an agent a task"
      title={agentId ? 'What should this agent do?' : 'What should the agent do?'}
      description={
        agentId
          ? 'It starts on the task now, and its trace opens when it finishes or stops for an approval.'
          : 'This creates a goal with one task and starts it now.'
      }
    >
      {/* Mounted only while open, so closing the dialog discards a half-written task instead of
          it reappearing the next time somebody opens it for something else. */}
      {open && (
        <TaskForm
          busy={busy}
          onBusyChange={setBusy}
          onError={setError}
          onCancel={close}
          onFinished={onClose}
          {...(agentId ? { agentId } : {})}
          {...(onSuccess ? { onSuccess } : {})}
        />
      )}
    </Dialog>
  )
}

/* ---- The form ---------------------------------------------------------------------------------- */

const LAST_AGENT_KEY = 'aiwos.lastAgentId'

/** The agent picked last time, so a person handing out several tasks is not made to pick again. */
function readLastAgent(): string | null {
  try {
    return window.localStorage.getItem(LAST_AGENT_KEY)
  } catch {
    return null
  }
}

function rememberAgent(id: string) {
  try {
    window.localStorage.setItem(LAST_AGENT_KEY, id)
  } catch {
    /* Blocked storage only costs the convenience of the default. */
  }
}

/** Only an active agent with a saved configuration can start a run; the platform refuses the rest. */
const canStart = (agent: Agent) => agent.status === 'active' && agent.revision != null

function agentOptionLabel(agent: Agent): string {
  const category = CATEGORY_LABEL[agent.category] ?? sentenceCase(agent.category)
  const base = category ? `${agent.name} — ${category}` : agent.name
  if (agent.status !== 'active') return `${base} (${statusLabel('agent', agent.status).label.toLowerCase()})`
  if (agent.revision == null) return `${base} (not set up yet)`
  return base
}

type Outcome = { tone: 'success' | 'info'; message: string }

/** What a run's status means for the person who just started it. */
function runOutcome(status: string): Outcome {
  switch (status) {
    case 'completed':
      return { tone: 'success', message: 'The agent finished.' }
    case 'waiting_approval':
      return { tone: 'info', message: 'The agent is waiting for an approval.' }
    case 'failed':
    case 'abandoned':
      return { tone: 'info', message: 'The run failed. The trace shows why.' }
    case 'cancelled':
      return { tone: 'info', message: 'The run was stopped.' }
    default:
      return { tone: 'info', message: 'The run started.' }
  }
}

/** Where the goal's one task stands, and where to take the person next. */
function goalOutcome(goal: Goal): Outcome & { runId: string | undefined; href: string } {
  const task = goal.tasks[0]
  const goalHref = `/tasks?goal=${goal.id}`
  if (task?.runId) {
    const href = `/runs/${task.runId}`
    // A failed attempt with attempts left goes back to waiting, keeping the reason.
    if (task.status === 'pending' && task.failureReason) {
      return { tone: 'info', message: 'The first attempt failed. The trace shows why.', runId: task.runId, href }
    }
    return { ...runOutcome(task.status), runId: task.runId, href }
  }
  if (task?.failureReason) {
    return { tone: 'info', message: 'The task could not start. Tasks shows why.', runId: undefined, href: goalHref }
  }
  if (task?.status === 'pending') {
    return { tone: 'info', message: 'The task is queued behind earlier work.', runId: undefined, href: goalHref }
  }
  return { tone: 'success', message: 'The goal was created.', runId: undefined, href: goalHref }
}

const FIELD_LABELS: Record<string, string> = {
  instruction: 'Instruction',
  title: 'Title',
  'tasks[0].instruction': 'Instruction',
  'tasks[0].title': 'Title',
  'tasks[0].agentId': 'Agent',
}

function failureMessage(error: unknown, goalMode: boolean): string {
  // The gateway stops waiting after a minute, but the agent carries on: say where to find it.
  if (error instanceof ApiError && error.status === 504) {
    return goalMode
      ? 'This window stopped waiting, but the agent is probably still working. Its goal will appear in Tasks.'
      : 'This window stopped waiting, but the agent is probably still working. Its run will appear in Runs.'
  }
  return describeApiError(error, FIELD_LABELS)
}

type TaskFormProps = {
  agentId?: string
  onSuccess?: (runId: string | undefined, started: TaskStarted) => void
  busy: boolean
  onBusyChange: (busy: boolean) => void
  onError: (message: string | null) => void
  /** The person closed the form without starting anything. */
  onCancel: () => void
  /** The task started; the dialog can close. */
  onFinished: () => void
}

function TaskForm({ agentId, onSuccess, busy, onBusyChange, onError, onCancel, onFinished }: TaskFormProps) {
  const { navigate } = useRouter()
  const toast = useToast()
  const [instruction, setInstruction] = useState('')
  const [chosenAgentId, setChosenAgentId] = useState('')
  const [lastAgentId] = useState(readLastAgent)

  const needsAgentPicker = !agentId
  const agentsQuery = useAgents()
  const agents = agentsQuery.data ?? []
  const startable = agents.filter(canStart)

  // Until somebody picks, the agent used last time is the default if it can still take work,
  // else the first one that can. The choice stays visible and can be changed.
  const rememberedAgent = startable.find((agent) => agent.id === lastAgentId)
  const selectedAgentId = chosenAgentId || rememberedAgent?.id || startable[0]?.id || ''
  const selectedAgent = agents.find((agent) => agent.id === selectedAgentId)
  const effectiveAgentId = agentId ?? selectedAgentId
  const createRunMutation = useCreateRun(effectiveAgentId || 'unselected')
  const createGoalMutation = useCreateGoal()

  const agentsReady = !needsAgentPicker || (!agentsQuery.isLoading && !agentsQuery.error)
  const noAgentCanStart = needsAgentPicker && agentsReady && startable.length === 0
  const canSubmit = instruction.trim().length > 0 && effectiveAgentId.length > 0 && agentsReady && !noAgentCanStart

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (!canSubmit || busy) return

    onError(null)
    onBusyChange(true)
    try {
      let started: TaskStarted
      let outcome: Outcome
      if (agentId) {
        const result: RunStarted = await createRunMutation.mutateAsync({ instruction })
        outcome = runOutcome(result.status)
        started = { runId: result.runId, goalId: undefined, href: `/runs/${result.runId}` }
      } else {
        const goal = await createGoalMutation.mutateAsync({
          // The backend allows 200 characters; a title cut at a word reads better in lists.
          title: truncateWords(instruction, 80),
          agentId: effectiveAgentId,
          instruction,
        })
        const result = goalOutcome(goal)
        outcome = result
        started = { runId: result.runId, goalId: goal.id, href: result.href }
      }

      rememberAgent(effectiveAgentId)
      const userId = profile()?.userId
      if (userId) markStepDone(userId, 'give-task')

      onBusyChange(false)
      if (outcome.tone === 'success') toast.success(outcome.message)
      else toast.info(outcome.message)
      if (onSuccess) onSuccess(started.runId, started)
      else navigate(started.href)
      onFinished()
    } catch (error) {
      onBusyChange(false)
      onError(failureMessage(error, !agentId))
    }
  }

  return (
    <form onSubmit={handleSubmit}>
      {needsAgentPicker && agentsQuery.error ? (
        <div style={{ marginBottom: 'var(--space-5)' }}>
          <Notice tone="warning">
            {agentsQuery.error instanceof ApiError && agentsQuery.error.isPermissionDenied
              ? 'Your role cannot see the list of agents, so there is no agent to choose from. An owner or admin can change your role.'
              : 'The list of agents could not be loaded. Close this and try again.'}
          </Notice>
        </div>
      ) : null}

      {needsAgentPicker && noAgentCanStart && (
        <div style={{ marginBottom: 'var(--space-5)' }}>
          <Notice tone="warning">
            {agents.length === 0
              ? 'This workspace has no agents yet. '
              : 'No agent can take a task right now: each one is paused or not set up yet. '}
            {/* Adding an agent and setting one up are different permissions. */}
            {can(agents.length === 0 ? 'agent:create' : 'agent:update') ? (
              <a className="link" href="/agents">
                Go to Agents
              </a>
            ) : (
              'Someone whose role can manage agents has to set one up first.'
            )}
          </Notice>
        </div>
      )}

      {needsAgentPicker && !noAgentCanStart && !agentsQuery.error && (
        <div style={{ marginBottom: 'var(--space-4)' }}>
          <Select
            label="Agent"
            value={selectedAgentId}
            onChange={(e) => setChosenAgentId(e.target.value)}
            disabled={agentsQuery.isLoading || busy}
            required
            hint={
              selectedAgent?.summary ? (
                <>
                  From its instructions: <q>{selectedAgent.summary}</q>
                </>
              ) : undefined
            }
          >
            {agentsQuery.isLoading && <option value="">Loading agents…</option>}
            {!agentsQuery.isLoading && !selectedAgentId && (
              <option value="" disabled>
                Choose an agent
              </option>
            )}
            {agents.map((agent) => (
              <option key={agent.id} value={agent.id} disabled={!canStart(agent)}>
                {agentOptionLabel(agent)}
              </option>
            ))}
          </Select>
        </div>
      )}

      <Textarea
        label="Instruction"
        value={instruction}
        onChange={(e) => setInstruction(e.target.value)}
        placeholder="Describe the task in a sentence or two…"
        required
        readOnly={busy}
        maxLength={10000}
        rows={6}
        hint="Write it as you would to a colleague. Up to 10,000 characters."
        data-autofocus
      />

      {busy && (
        <div style={{ marginTop: 'var(--space-5)' }}>
          <Notice tone="info" live>
            The agent is working. This window waits until it finishes or stops for an approval.
          </Notice>
        </div>
      )}

      <div className="dialog-footer">
        <Button variant="outline" type="button" onClick={onCancel} disabled={busy}>
          Cancel
        </Button>
        <Button type="submit" loading={busy} disabled={!canSubmit}>
          Start
        </Button>
      </div>
    </form>
  )
}
