// @find: give agent a task, new task, assign task, start run, create goal, task dialog, choose agent, run trace, Command Map task, Tasks page, TaskDialog, orchestrator
// @what: Dialog to give an agent a task: starts a run directly from an agent page or creates a goal from elsewhere, then opens the run.
// @flow: Opened from Command Map, Agent page, Tasks, Runs; uses useCreateGoal and the runs API.
import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Dialog, Notice, Select, Textarea } from './index'
import { ApiError, api, describeApiError } from '../../lib/api'
import { sentenceCase, truncateWords } from '../../lib/format'
import { CATEGORY_LABEL, statusLabel } from '../../lib/labels'
import { markStepDone } from '../../lib/onboarding'
import { agentDescription } from '../../lib/agentDescription'
import { useAgents, useCreateGoal } from '../../lib/queries'
import type { Agent, Goal } from '../../lib/queries'
import { useRouter } from '../../lib/router'
import { can, profile } from '../../lib/session'
import { useToast } from '../../lib/toast'

/*
 * Give an agent a task.
 *
 * The same dialog opens from several places: the Command Map, an agent's own page, Tasks and
 * Runs. From an agent's page the agent is already decided, so the dialog only asks what to do; it
 * starts a run directly. From everywhere else it asks which agent, and creates a goal with that
 * one task, which the orchestrator starts at once.
 *
 * Either way the platform answers as soon as the work is saved, and the agent works in the
 * background. The dialog closes straight away and opens the run's trace, which shows each step as
 * it happens. A goal's run starts a moment after the goal is saved, so for a goal the dialog looks
 * for that run for a few seconds, and opens the goal on Tasks if it has not appeared by then.
 */

// @find: task started result type
/** Where a task ended up once the dialog's request came back. */
export type TaskStarted = {
  /** The run the task started, when one started. */
  runId: string | undefined
  /** The goal created for the task, when the dialog created one. */
  goalId: string | undefined
  /** Where the dialog would go next: the run's trace, or the goal on Tasks when no run started. */
  href: string
}

// @find: task started message
/** What the person is told once the work is saved. The run's own page then shows how it goes. */
export const STARTED_MESSAGE = 'Started. The agent is working on it.'

// @find: wait time for first run of a goal
/** How long the dialog looks for a new goal's first run before opening the goal instead. */
export const FIRST_RUN_WAIT_MS = 5_000
const FIRST_RUN_POLL_MS = 500

interface TaskDialogProps {
  open: boolean
  onClose: () => void
  /** Starts a run for this agent directly. Without it the dialog asks which agent, and creates a goal. */
  agentId?: string
  /** Replaces the default of opening `started.href` once the task has started. */
  onSuccess?: (runId: string | undefined, started: TaskStarted) => void
}

// @find: give agent a task dialog, create goal, start run
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

  // Always dismissible: the request only saves the task, so there is nothing to wait for, and
  // closing early never stops the agent - the task still opens once it has been saved.
  return (
    <Dialog
      open={open}
      onClose={onClose}
      error={error}
      eyebrow="Give an agent a task"
      title={agentId ? 'What should this agent do?' : 'What should the agent do?'}
      description={
        agentId
          ? 'It starts on the task now, and its trace opens so you can follow along.'
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
          onCancel={onClose}
          onFinished={onClose}
          {...(agentId ? { agentId } : {})}
          {...(onSuccess ? { onSuccess } : {})}
        />
      )}
    </Dialog>
  )
}

/* ---- The form ---------------------------------------------------------------------------------- */

/**
 * The unscoped key used before the choice was kept per person. Anyone who signed in next on the
 * same browser inherited the previous person's agent, so it is removed rather than read.
 */
const LEGACY_LAST_AGENT_KEY = 'aiwos.lastAgentId'

// @find: last chosen agent storage key
/** Where the signed-in person's last choice is kept; null when nobody is signed in. */
export function lastAgentKey(): string | null {
  const userId = profile()?.userId
  return userId ? `${LEGACY_LAST_AGENT_KEY}.${userId}` : null
}

/** The agent this person picked last time, so handing out several tasks does not mean picking again. */
function readLastAgent(): string | null {
  try {
    // Gone after the first opening; removing a key that is not there costs nothing.
    window.localStorage.removeItem(LEGACY_LAST_AGENT_KEY)
    const key = lastAgentKey()
    return key ? window.localStorage.getItem(key) : null
  } catch {
    return null
  }
}

function rememberAgent(id: string) {
  try {
    const key = lastAgentKey()
    if (key) window.localStorage.setItem(key, id)
  } catch {
    /* Blocked storage only costs the convenience of the default. */
  }
}

/** Only an active agent with a saved configuration can start a run; the platform refuses the rest. */
const canStart = (agent: Agent) => agent.status === 'active' && agent.revision != null

function agentOptionLabel(agent: Agent): string {
  const category = CATEGORY_LABEL[agent.category] ?? sentenceCase(agent.category)
  const base = category ? `${agent.name} — ${category}` : agent.name
  // General Employee is marked by its own flag, not by status: it takes any request no
  // specialist covers, and that is worth saying every time it appears in the list.
  if (agent.fallback) return `${base} (default)`
  if (agent.status !== 'active') return `${base} (${statusLabel('agent', agent.status).label.toLowerCase()})`
  if (agent.revision == null) return `${base} (not set up yet)`
  return base
}

/** General Employee first, everyone else in the order the API gave them. */
function withGeneralFirst(agents: Agent[]): Agent[] {
  return [...agents].sort((a, b) => Number(Boolean(b.fallback)) - Number(Boolean(a.fallback)))
}

/** Where to take the person for a goal: its first task's run, or the goal on Tasks while there is none. */
function startedFor(goal: Goal): TaskStarted {
  const runId = goal.tasks[0]?.runId ?? undefined
  return { runId, goalId: goal.id, href: runId ? `/runs/${runId}` : `/tasks?goal=${goal.id}` }
}

/** A task that has given up before any run began: nothing more is coming to wait for. */
const couldNotStart = (goal: Goal) => {
  const task = goal.tasks[0]
  return !task?.runId && Boolean(task?.failureReason)
}

const pause = (ms: number) => new Promise<void>((resolve) => window.setTimeout(resolve, ms))

// @find: wait for a goal first run to appear
/**
 * Looks for a new goal's first run for up to `waitMs`, asking every `pollMs`. The goal is saved
 * first and its run starts a moment later, so the answer to creating it seldom names the run yet.
 * Resolves with the goal as last read: with the run when it appeared, without it when it did not,
 * and as soon as the task is known to have failed to start. A read that fails is tried again.
 */
export async function waitForFirstRun(
  goal: Goal,
  load: (goalId: string) => Promise<Goal>,
  { waitMs = FIRST_RUN_WAIT_MS, pollMs = FIRST_RUN_POLL_MS }: { waitMs?: number; pollMs?: number } = {},
): Promise<Goal> {
  let latest = goal
  const deadline = Date.now() + waitMs
  while (!latest.tasks[0]?.runId && !couldNotStart(latest) && Date.now() < deadline) {
    await pause(Math.min(pollMs, Math.max(0, deadline - Date.now())))
    try {
      latest = await load(goal.id)
    } catch {
      /* A failed read only costs this one look; the next one, or the goal on Tasks, follows. */
    }
  }
  return latest
}

const loadGoal = (goalId: string) => api<Goal>(`/api/goals/${goalId}`)

/** Where the person is now, to tell whether they have moved on while the dialog was still looking. */
const currentLocation = () => window.location.pathname + window.location.search

const FIELD_LABELS: Record<string, string> = {
  instruction: 'Instruction',
  title: 'Title',
  'tasks[0].instruction': 'Instruction',
  'tasks[0].title': 'Title',
  'tasks[0].agentId': 'Agent',
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
  const agents = withGeneralFirst(agentsQuery.data ?? [])
  const startable = agents.filter(canStart)

  // Until somebody picks, the agent used last time is the default if it can still take work.
  // Failing that, General Employee, the workspace's default for anything no specialist covers,
  // and only then the first agent that can start at all. The choice stays visible and can change.
  const rememberedAgent = startable.find((agent) => agent.id === lastAgentId)
  const generalAgent = startable.find((agent) => agent.fallback)
  const selectedAgentId = chosenAgentId || rememberedAgent?.id || generalAgent?.id || startable[0]?.id || ''
  const selectedAgent = agents.find((agent) => agent.id === selectedAgentId)
  const effectiveAgentId = agentId ?? selectedAgentId
  const createGoalMutation = useCreateGoal()

  const agentsReady = !needsAgentPicker || (!agentsQuery.isLoading && !agentsQuery.error)
  const noAgentCanStart = needsAgentPicker && agentsReady && startable.length === 0
  const canSubmit = instruction.trim().length > 0 && effectiveAgentId.length > 0 && agentsReady && !noAgentCanStart

  // Whether the person closed the dialog (Cancel, Escape or the close button) before the save
  // returned. The work is still saved and said so, but they are not then taken to it: closing was
  // their way of staying where they are. Set on unmount unless this form closed itself on success;
  // reset on mount, since StrictMode mounts twice in development.
  const finishing = useRef(false)
  const dismissed = useRef(false)
  useEffect(() => {
    dismissed.current = false
    return () => {
      if (!finishing.current) dismissed.current = true
    }
  }, [])

  // Opens what the task started, unless the person has moved on to another page meanwhile.
  const openStarted = (started: TaskStarted, from: string) => {
    if (dismissed.current || currentLocation() !== from) return
    if (onSuccess) onSuccess(started.runId, started)
    else navigate(started.href)
  }

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (!canSubmit || busy) return

    onError(null)
    onBusyChange(true)
    const from = currentLocation()
    let saved: { goal: Goal }
    try {
      // Always a one-task goal, from the agent's own page too: the work then shows on the board,
      // and can be stopped and tried again like any other. (Starting a run directly stays in the
      // API, for integrations.)
      saved = {
        goal: await createGoalMutation.mutateAsync({
          // The backend allows 200 characters; a title cut at a word reads better in lists.
          title: truncateWords(instruction, 80),
          agentId: effectiveAgentId,
          instruction,
        }),
      }
    } catch (error) {
      onBusyChange(false)
      onError(describeApiError(error, FIELD_LABELS))
      return
    }

    // The work is saved and the agent is starting on it: the dialog has nothing left to wait for.
    rememberAgent(effectiveAgentId)
    const userId = profile()?.userId
    if (userId) markStepDone(userId, 'give-task')
    onBusyChange(false)
    if (!dismissed.current) {
      finishing.current = true
      onFinished()
    }
    toast.success(STARTED_MESSAGE)

    const latest = await waitForFirstRun(saved.goal, loadGoal)
    if (couldNotStart(latest)) toast.info('The task could not start. Tasks shows why.')
    openStarted(startedFor(latest), from)
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
              // What the agent does, written about it, never a quote of its second-person instructions.
              (selectedAgent && agentDescription(selectedAgent)) || undefined
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

      <div className="dialog-footer">
        <Button variant="outline" type="button" onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" loading={busy} disabled={!canSubmit}>
          Start
        </Button>
      </div>
    </form>
  )
}
