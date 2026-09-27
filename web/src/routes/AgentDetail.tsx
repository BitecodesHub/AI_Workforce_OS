import { useMemo, useState } from 'react'
import {
  Button,
  CATEGORY_LABEL,
  Card,
  DataTable,
  Dialog,
  EmptyState,
  Eyebrow,
  Input,
  LoadingState,
  Notice,
  PageHeader,
  StatRow,
  StatTile,
  StatusTag,
  Tag,
  Textarea,
  Time,
} from '../components/ui'
import type { Column } from '../components/ui'
import { BackLink, EmptyIcon, QueryState } from '../components/ui/QueryState'
import { TaskDialog } from '../components/ui/TaskDialog'
import { ApiError, describeApiError } from '../lib/api'
import { formatCount, formatDateTime, formatRelative, formatRunElapsed, sentenceCase } from '../lib/format'
import { serverLabel, startedByLabel, statusLabel } from '../lib/labels'
import {
  useAgent,
  useAgentModelPolicy,
  useModels,
  useProviders,
  useRunList,
  useTaskIndex,
  useUpdateAgent,
  type AgentDetail as AgentDetailData,
  type AgentGrant,
  type Run,
} from '../lib/queries'
import { useDocumentTitle } from '../lib/router'
import { useToast } from '../lib/toast'
import { can } from '../lib/session'
import { useNow } from '../lib/useNow'

/** How many of the agent's runs the page lists before pointing at the full list. */
const RECENT_RUNS = 10

/* Limits from AgentController.UpdateConfigurationRequest. */
const PROMPT_MAX = 20_000
const GOALS_MAX = 4_000
const MIN_STEPS = 1
const MAX_STEPS = 50
const DEFAULT_STEPS = 12

const FIELD_LABELS: Record<string, string> = {
  systemPrompt: 'Instructions',
  goals: 'Goals',
  maxSteps: 'Step limit per run',
}

const GRANT_COLUMNS: Column<AgentGrant>[] = [
  { key: 'server', header: 'Tool server', render: (row) => serverLabel(row.server) },
  {
    key: 'tools',
    header: 'Tools it may call',
    render: (row) =>
      row.tools.length > 0 ? (
        <span className="muted">{row.tools.map((tool) => sentenceCase(tool)).join(', ')}</span>
      ) : (
        <span className="muted">Every tool on this server</span>
      ),
  },
  {
    key: 'scopes',
    header: 'Scopes',
    render: (row) =>
      row.scopes.length > 0 ? <span className="caption">{row.scopes.join(', ')}</span> : <span className="caption">None</span>,
  },
  {
    key: 'approval',
    header: 'Approval',
    // A grant without its own requirement still stops for approval wherever the tool or a policy
    // requires it (ToolGateway), so "not required" would promise too much.
    render: (row) =>
      row.requireApproval ? (
        <Tag tone="warning">Every call</Tag>
      ) : (
        <span className="caption">When the tool requires it</span>
      ),
  },
  {
    key: 'limit',
    header: 'Calls per run',
    numeric: true,
    render: (row) => (row.maxCallsPerRun == null ? 'No limit' : formatCount(row.maxCallsPerRun)),
  },
]

function categoryLabel(category: string): string {
  return CATEGORY_LABEL[category] ?? sentenceCase(category)
}

/* ---- Edit instructions ------------------------------------------------------------------------- */

function EditInstructionsDialog({
  open,
  onClose,
  agent,
}: {
  open: boolean
  onClose: () => void
  agent: AgentDetailData
}) {
  const update = useUpdateAgent(agent.id)
  const [error, setError] = useState<string | null>(null)

  const close = () => {
    setError(null)
    update.reset()
    onClose()
  }

  return (
    <Dialog
      open={open}
      onClose={close}
      eyebrow="Edit instructions"
      title={`Change how ${agent.name} works`}
      description="Saving creates a new revision. Runs already recorded keep the revision they used, so their traces stay accurate."
      dismissible={!update.isPending}
      error={error}
    >
      {/* Mounted only while open: each opening starts from the saved configuration, and a
          cancelled edit is thrown away rather than shown again. */}
      {open && <EditInstructionsForm agent={agent} update={update} onError={setError} onDone={close} />}
    </Dialog>
  )
}

/** The step limit as typed: null for empty (the default), a number, or false when it is not a valid limit. */
function parseSteps(value: string): number | null | false {
  const trimmed = value.trim()
  if (trimmed === '') return null
  if (!/^\d+$/.test(trimmed)) return false
  const steps = Number(trimmed)
  return steps >= MIN_STEPS && steps <= MAX_STEPS ? steps : false
}

function EditInstructionsForm({
  agent,
  update,
  onError,
  onDone,
}: {
  agent: AgentDetailData
  update: ReturnType<typeof useUpdateAgent>
  onError: (message: string | null) => void
  onDone: () => void
}) {
  const toast = useToast()
  const [systemPrompt, setSystemPrompt] = useState(agent.systemPrompt ?? '')
  const [goals, setGoals] = useState(agent.goals ?? '')
  const [maxSteps, setMaxSteps] = useState(agent.maxSteps == null ? '' : String(agent.maxSteps))
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  /** A server's complaint about a field goes once the field is changed. */
  const clearError = (field: string) =>
    setFieldErrors((current) => {
      if (!(field in current)) return current
      const rest = { ...current }
      delete rest[field]
      return rest
    })

  const saving = update.isPending
  const steps = parseSteps(maxSteps)
  const stepsProblem = steps === false ? `Enter a whole number from ${MIN_STEPS} to ${MAX_STEPS}, or leave it empty.` : null

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (saving || steps === false || systemPrompt.trim() === '') return
    setFieldErrors({})
    onError(null)
    try {
      // An empty limit is sent as null, which the server saves as the default. Sending 0 once
      // saved a limit that failed every later run before its first step.
      const saved = await update.mutateAsync({ systemPrompt, goals, maxSteps: steps })
      toast.success(saved.revision == null ? 'Instructions saved' : `Saved as revision ${saved.revision}`)
      onDone()
    } catch (error) {
      const fields = error instanceof ApiError ? error.fields : {}
      const shown = Object.fromEntries(Object.entries(fields).filter(([field]) => field in FIELD_LABELS))
      setFieldErrors(shown)
      const allShown = Object.keys(shown).length > 0 && Object.keys(shown).length === Object.keys(fields).length
      onError(allShown ? null : describeApiError(error, FIELD_LABELS))
    }
  }

  return (
    <form onSubmit={handleSubmit}>
      {/* Spacing between whole fields, so each hint stays with the field it describes. */}
      <div className="stack" style={{ gap: 'var(--space-4)', marginBottom: 'var(--space-6)' }}>
        <Textarea
          label="Instructions"
          value={systemPrompt}
          onChange={(e) => {
            setSystemPrompt(e.target.value)
            clearError('systemPrompt')
          }}
          placeholder="You screen job applications for the people team and draft replies to candidates."
          required
          maxLength={PROMPT_MAX}
          rows={8}
          hint="Written to the agent. The first sentence appears on its card."
          error={fieldErrors.systemPrompt}
        />
        <Textarea
          label="Goals"
          optional
          value={goals}
          onChange={(e) => {
            setGoals(e.target.value)
            clearError('goals')
          }}
          placeholder="Screen applications, draft onboarding emails and schedule interviews."
          maxLength={GOALS_MAX}
          rows={4}
          hint="What this agent is responsible for achieving."
          error={fieldErrors.goals}
        />
        <Input
          label="Step limit per run"
          optional
          type="number"
          inputMode="numeric"
          min={MIN_STEPS}
          max={MAX_STEPS}
          step={1}
          value={maxSteps}
          onChange={(e) => {
            setMaxSteps(e.target.value)
            clearError('maxSteps')
          }}
          placeholder={String(DEFAULT_STEPS)}
          hint={`From ${MIN_STEPS} to ${MAX_STEPS}. Leave it empty to use the default of ${DEFAULT_STEPS}. A run stops when it reaches the limit.`}
          error={fieldErrors.maxSteps ?? stepsProblem}
        />
      </div>
      <div className="dialog-footer" style={{ justifyContent: 'flex-end', gap: 'var(--space-3)' }}>
        <Button variant="outline" type="button" onClick={onDone} disabled={saving}>
          Cancel
        </Button>
        <Button type="submit" loading={saving} disabled={steps === false || systemPrompt.trim() === ''}>
          Save instructions
        </Button>
      </div>
    </form>
  )
}

/* ---- Recent runs ---------------------------------------------------------------------------------- */

function AgentRuns({ agentId, onGiveTask }: { agentId: string; onGiveTask?: (() => void) | undefined }) {
  // Filtered by the server, so these are this agent's runs however busy the workspace is.
  const runs = useRunList({ agentId })
  const taskIndex = useTaskIndex()
  const now = useNow(10_000)

  // The server filters by agent; checking again costs nothing and keeps another agent's run off
  // this page should a service ever ignore the filter.
  const loaded = useMemo(
    () => runs.data?.pages.flat().filter((run) => run.agentId === agentId),
    [runs.data, agentId],
  )
  const recent = useMemo(() => loaded?.slice(0, RECENT_RUNS), [loaded])

  const columns: Column<Run>[] = [
    { key: 'started', header: 'Started', render: (run) => <Time iso={run.startedAt} /> },
    { key: 'status', header: 'Status', render: (run) => <StatusTag kind="run" status={run.status} /> },
    {
      key: 'trigger',
      header: 'Started by',
      render: (run) => (
        <span className="muted">{startedByLabel(run, run.taskId ? taskIndex[run.taskId]?.task.title : null)}</span>
      ),
    },
    { key: 'elapsed', header: 'Time', numeric: true, render: (run) => formatRunElapsed(run, now) },
    { key: 'steps', header: 'Steps', numeric: true, render: (run) => formatCount(run.stepCount) },
  ]

  return (
    <Card as="section">
      <Eyebrow as="h2">Recent runs</Eyebrow>
      <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
        What this agent has done most recently, newest first. Open a run to read its trace.
      </p>
      <QueryState
        // A failed request for an older page must not replace the runs already shown.
        query={{
          data: recent,
          error: recent ? null : runs.error,
          isLoading: runs.isLoading,
          refetch: runs.refetch,
        }}
        permission="run:read"
        what="this agent's runs"
        rows={3}
        isEmpty={(rows) => rows.length === 0}
        empty={
          <EmptyState
            titleAs="h3"
            icon={<EmptyIcon kind="task" />}
            title="No runs yet"
            body="This agent has not been given any work yet."
            action={onGiveTask ? <Button onClick={onGiveTask}>Give it a task</Button> : undefined}
          />
        }
      >
        {(rows) => (
          <>
            <DataTable
              columns={columns}
              rows={rows}
              getKey={(run) => run.id}
              getRowHref={(run) => `/runs/${run.id}`}
              getRowLabel={(run) => `Run started ${formatDateTime(run.startedAt)}`}
              caption="This agent's most recent runs, with their status, what started them and how long they took."
            />
            <p style={{ marginTop: 'var(--space-4)' }}>
              <a className="link" href={`/runs?agent=${encodeURIComponent(agentId)}`}>
                See all runs by this agent
              </a>
            </p>
          </>
        )}
      </QueryState>
    </Card>
  )
}

/* ---- Model routing -------------------------------------------------------------------------------- */

/**
 * Names of the providers the platform ships with (V2__seed_providers.sql), for a role that cannot
 * read the provider list, so a candidate never reads "from nvidia".
 */
const SEEDED_PROVIDERS: Record<string, string> = {
  sandbox: 'Offline sandbox',
  openrouter: 'OpenRouter',
  groq: 'Groq',
  nvidia: 'NVIDIA NIM',
  openai: 'OpenAI',
  anthropic: 'Anthropic',
  gemini: 'Google Gemini',
  bedrock: 'AWS Bedrock',
}

function AgentRouting({ agentId }: { agentId: string }) {
  const policy = useAgentModelPolicy(agentId)
  // The provider list and model catalogue need provider:read. Without it, providers are named
  // from the seeded list and models by their id.
  const canSeeRouting = can('provider:read')
  const providers = useProviders({ enabled: canSeeRouting })
  const models = useModels({ enabled: canSeeRouting })
  const providerName = (id: string) =>
    providers.data?.find((provider) => provider.id === id)?.displayName ?? SEEDED_PROVIDERS[id] ?? sentenceCase(id)
  const modelName = (providerId: string, modelId: string) =>
    models.data?.find((model) => model.providerId === providerId && model.modelId === modelId)?.displayName

  let body
  if (policy.isLoading) {
    body = <LoadingState rows={2} label="Loading this agent's model routing" />
  } else if (policy.error || !policy.data) {
    body = <Notice tone="warning">Its model routing could not be loaded. {describeApiError(policy.error)}</Notice>
  } else if (policy.data.configured && policy.data.candidates.length > 0) {
    // The router skips an agent policy with no candidates, so only a non-empty one is the agent's own.
    const candidates = [...policy.data.candidates].sort((a, b) => a.position - b.position)
    body = (
      <>
        <p style={{ marginBottom: 'var(--space-4)' }}>This agent has its own routing. It tries these models in order:</p>
        <ol className="stack" style={{ gap: 'var(--space-3)', margin: 0, paddingLeft: 'var(--space-6)' }}>
          {candidates.map((candidate) => {
            const name = modelName(candidate.providerId, candidate.modelId)
            return (
              // Model ids run long ("meta-llama/llama-3.3-70b-instruct:free"); they wrap rather
              // than push a phone screen sideways.
              <li key={`${candidate.position}-${candidate.providerId}-${candidate.modelId}`} style={{ overflowWrap: 'anywhere' }}>
                {name ? <span title={candidate.modelId}>{name}</span> : <span className="mono">{candidate.modelId}</span>}{' '}
                <span className="caption">from {providerName(candidate.providerId)}</span>
              </li>
            )
          })}
        </ol>
        <p className="caption" style={{ marginTop: 'var(--space-4)' }}>
          {policy.data.exhaustedBehaviour === 'DEGRADE_TO_SANDBOX'
            ? 'If every model fails, it answers on the offline sandbox model.'
            : 'If every model fails, the run fails.'}
        </p>
      </>
    )
  } else {
    body = (
      <p>
        It follows the workspace routing policy.{' '}
        {canSeeRouting && (
          <a className="link" href="/routing">
            See model routing
          </a>
        )}
      </p>
    )
  }

  return (
    <Card as="section">
      <Eyebrow as="h2">Model routing</Eyebrow>
      <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
        Which language models answer for this agent.
      </p>
      {body}
    </Card>
  )
}

/* ---- Page ----------------------------------------------------------------------------------------- */

export function AgentDetail({ id }: { id: string }) {
  const query = useAgent(id)
  const [editOpen, setEditOpen] = useState(false)
  const [taskDialogOpen, setTaskDialogOpen] = useState(false)
  const canEdit = can('agent:update')
  const canRun = can('agent:run')
  useDocumentTitle(query.data?.name)
  // The same query as the runs card below, so this costs no second request.
  const runs = useRunList({ agentId: id })
  const now = useNow()
  const lastRun = runs.data?.pages[0]?.find((run) => run.agentId === id)

  return (
    <div className="page">
      <BackLink href="/agents" label="Back to agents" />
      <QueryState
        query={query}
        permission="agent:read"
        what="this agent"
        rows={5}
        notFound={
          <a className="link" href="/agents">
            See every agent
          </a>
        }
      >
        {(agent) => {
          // A paused or retired agent, or one never configured, is refused a run by the platform
          // (AgentRunner.start), so it is not offered one. The status in the header says why.
          const canGiveTask = canRun && agent.status === 'active' && agent.revision != null
          return (
            <>
              <PageHeader
                eyebrow={categoryLabel(agent.category)}
                title={agent.name}
                description={agent.summary ? <>From its instructions: “{agent.summary}”</> : undefined}
                meta={
                  <>
                    <StatusTag kind="agent" status={agent.status} withDot />
                    <span className="caption">
                      {agent.revision == null ? 'No saved configuration yet' : `Configuration revision ${agent.revision}`}
                    </span>
                  </>
                }
                action={
                  canEdit || canGiveTask ? (
                    <>
                      {canEdit && (
                        <Button variant="outline" onClick={() => setEditOpen(true)}>
                          Edit instructions
                        </Button>
                      )}
                      {canGiveTask && <Button onClick={() => setTaskDialogOpen(true)}>Give it a task</Button>}
                    </>
                  ) : undefined
                }
              />

              <div style={{ marginTop: 'var(--space-6)' }}>
                <StatRow>
                  <StatTile
                    label="Step limit"
                    value={agent.maxSteps == null ? '—' : formatCount(agent.maxSteps)}
                    note="The most steps one run may take."
                  />
                  <StatTile
                    label="Tool servers"
                    value={formatCount(agent.grants.length)}
                    note="Servers it is granted tools on."
                  />
                  <StatTile
                    label="Last run"
                    value={lastRun ? formatRelative(lastRun.startedAt, now) : runs.data ? 'Never' : '—'}
                    note={
                      lastRun
                        ? statusLabel('run', lastRun.status).label
                        : runs.data
                          ? 'It has not been given any work yet.'
                          : runs.error
                            ? 'Its runs could not be loaded.'
                            : 'Loading its runs.'
                    }
                  />
                </StatRow>
              </div>

              <div style={{ marginTop: 'var(--space-6)' }}>
                <AgentRuns agentId={agent.id} onGiveTask={canGiveTask ? () => setTaskDialogOpen(true) : undefined} />
              </div>

              <div style={{ marginTop: 'var(--space-6)' }}>
                <Card as="section">
                  <Eyebrow as="h2">Instructions</Eyebrow>
                  <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
                    What this agent is told, in its current configuration.
                  </p>
                  {agent.systemPrompt ? (
                    <pre
                      className="mono"
                      style={{
                        background: 'var(--paper)',
                        border: '1px solid var(--line)',
                        borderRadius: 'var(--radius-control-lg)',
                        padding: 'var(--space-5)',
                        margin: 0,
                        whiteSpace: 'pre-wrap',
                        overflowWrap: 'anywhere',
                        color: 'var(--ink)',
                        lineHeight: 1.7,
                      }}
                    >
                      {agent.systemPrompt}
                    </pre>
                  ) : (
                    <p className="muted">No instructions have been saved for this agent.</p>
                  )}
                  {agent.goals?.trim() && (
                    <>
                      <h3 className="section-heading" style={{ fontSize: '15px', margin: 'var(--space-6) 0 var(--space-3)' }}>
                        Goals
                      </h3>
                      <p style={{ whiteSpace: 'pre-wrap' }}>{agent.goals}</p>
                    </>
                  )}
                </Card>
              </div>

              <div style={{ marginTop: 'var(--space-6)' }}>
                <Card as="section">
                  <Eyebrow as="h2">Tools granted</Eyebrow>
                  <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
                    The only tools this agent can use, with their scopes. Some tools always wait for a
                    person to approve them, whatever the grant says.
                  </p>
                  {agent.grants.length > 0 ? (
                    <DataTable
                      columns={GRANT_COLUMNS}
                      rows={agent.grants}
                      getKey={(row) => row.server}
                      caption="Tool grants for this agent, with the scopes and approval requirements each one carries."
                    />
                  ) : (
                    <EmptyState
                      titleAs="h3"
                      icon={<EmptyIcon kind="agent" />}
                      title="No tools are granted"
                      body="This agent can answer from its instructions, but it cannot act through a tool server."
                    />
                  )}
                </Card>
              </div>

              <div style={{ marginTop: 'var(--space-6)' }}>
                <AgentRouting agentId={agent.id} />
              </div>

              {canEdit && <EditInstructionsDialog open={editOpen} onClose={() => setEditOpen(false)} agent={agent} />}

              {canGiveTask && (
                <TaskDialog open={taskDialogOpen} onClose={() => setTaskDialogOpen(false)} agentId={agent.id} />
              )}
            </>
          )
        }}
      </QueryState>
    </div>
  )
}
