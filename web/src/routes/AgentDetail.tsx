// @find: agent detail, edit agent, agent page, edit description, edit instructions, system prompt, temperature, pause agent, resume agent, agent connectors, grant connector, agent routing, model for agent, revisions, restore revision, voice, agent runs, give task, /agents/:id, AgentDetail
// @what: The single agent page: its description, instructions, connected tools, model routing, revision history, voice and recent runs, with pause, resume and edit actions.
// @flow: Routed from App.tsx at /agents/:id; opened from the cards on Agents.tsx
import { useMemo, useRef, useState } from 'react'
import {
  Button,
  CATEGORY_LABEL,
  Card,
  ConfirmDialog,
  DataTable,
  Dialog,
  EmptyState,
  Eyebrow,
  Input,
  LoadingState,
  Notice,
  PageHeader,
  Select,
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
import { AgentStatusButton } from '../components/agents/AgentStatusButton'
import { AgentDocumentsCard } from '../components/agents/AgentDocumentsCard'
import { AgentMemoryCard } from '../components/agents/AgentMemoryCard'
import { RetireAgentCard } from '../components/agents/RetireAgentCard'
import { PolicyEditor } from '../components/routing/PolicyEditor'
import { CapabilityList } from '../components/connectors/CapabilityList'
import { GrantDialog } from '../components/connectors/GrantDialog'
import {
  changeSummary,
  costFigure,
  successRateFigure,
  useAgentOutcomes,
  useAgentRevisions,
  useClearAgentModelPolicy,
  useRestoreRevision,
  useSetAgentModelPolicy,
  type AgentRevision,
} from '../lib/agentQueries'
import { agentDescription } from '../lib/agentDescription'
import { ApiError, describeApiError } from '../lib/api'
import { formatCount, formatDateTime, formatRelativeTicked, formatRunElapsed, sentenceCase } from '../lib/format'
import { connectorState, grantedToolNames } from '../lib/connectors'
import { connectorStateLabel, serverLabel, startedByLabel, statusLabel } from '../lib/labels'
import {
  useAgent,
  useAgentModelPolicy,
  useCredentials,
  useIntegrations,
  useMemberNames,
  useModels,
  useProviders,
  useRemoveAgentGrant,
  useRunList,
  useSetAgentDescription,
  useSetAgentVoice,
  useTaskIndex,
  useUpdateAgent,
  useVoiceStatus,
  useVoices,
  type AgentDetail as AgentDetailData,
  type AgentGrant,
  type Integration,
  type Run,
} from '../lib/queries'
import { useDocumentTitle } from '../lib/router'
import { useToast } from '../lib/toast'
import { can } from '../lib/session'
import { useNow } from '../lib/useNow'
import { useSpeaker } from '../lib/voice'
import { useRemoveAgentCandidate, warningOf } from '../lib/routingActions'

/** How many of the agent's runs the page lists before pointing at the full list. */
const RECENT_RUNS = 10

/* Limits from AgentController.UpdateConfigurationRequest. */
const PROMPT_MAX = 20_000
const GOALS_MAX = 4_000
const MIN_STEPS = 1
const MAX_STEPS = 50
const DEFAULT_STEPS = 12
const MIN_TEMPERATURE = 0
const MAX_TEMPERATURE = 2
/* From AgentController.DescriptionRequest. */
const DESCRIPTION_MAX = 200

const FIELD_LABELS: Record<string, string> = {
  systemPrompt: 'Instructions',
  goals: 'Goals',
  maxSteps: 'Step limit per run',
  temperature: 'Temperature',
  description: 'What it does',
}

function categoryLabel(category: string): string {
  return CATEGORY_LABEL[category] ?? sentenceCase(category)
}

/* ---- Edit description ---------------------------------------------------------------------------- */

/**
 * The one line that says what the agent does, written about it. It is not part of a configuration
 * revision: it describes the agent to people and is never sent to the model.
 */
// @find: edit agent description dialog, rename agent, change description, PATCH /api/agents/:id
function EditDescriptionDialog({ open, onClose, agent }: { open: boolean; onClose: () => void; agent: AgentDetailData }) {
  const save = useSetAgentDescription(agent.id)
  const toast = useToast()
  const [value, setValue] = useState(agent.description ?? '')
  const [error, setError] = useState<string | null>(null)
  const [fieldError, setFieldError] = useState<string | undefined>(undefined)

  const close = () => {
    setError(null)
    setFieldError(undefined)
    setValue(agent.description ?? '')
    save.reset()
    onClose()
  }

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (save.isPending) return
    setError(null)
    setFieldError(undefined)
    try {
      await save.mutateAsync(value.trim())
      toast.success(value.trim() ? 'Description saved' : 'Description cleared')
      onClose()
    } catch (thrown) {
      const fields = thrown instanceof ApiError ? thrown.fields : {}
      if (fields.description) setFieldError(fields.description)
      else setError(describeApiError(thrown, FIELD_LABELS))
    }
  }

  return (
    <Dialog
      open={open}
      onClose={close}
      eyebrow="Edit description"
      title={`What ${agent.name} does`}
      description="One line about the agent, shown on its card, on this page and in Chat. It is not sent to the agent."
      dismissible={!save.isPending}
      error={error}
    >
      {open && (
        <form onSubmit={handleSubmit}>
          <div style={{ marginBottom: 'var(--space-6)' }}>
            <Input
              label="What it does"
              optional
              value={value}
              onChange={(e) => {
                setValue(e.target.value)
                setFieldError(undefined)
              }}
              placeholder={agentDescription({ summary: agent.summary ?? null }) || 'e.g. Screens job applications and drafts replies to candidates.'}
              maxLength={DESCRIPTION_MAX}
              hint="Leave it empty to use a line taken from its instructions."
              error={fieldError}
              data-autofocus
            />
          </div>
          <div className="dialog-footer" style={{ justifyContent: 'flex-end', gap: 'var(--space-3)' }}>
            <Button variant="outline" type="button" onClick={close} disabled={save.isPending}>
              Cancel
            </Button>
            <Button type="submit" loading={save.isPending}>
              Save description
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}

/* ---- Edit instructions ------------------------------------------------------------------------- */

// @find: edit agent instructions dialog, change system prompt, agent instructions
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

/** The temperature as typed: null for empty (the model's default), a number, or false when it is out of range. */
// @find: parse temperature, agent temperature field validation
export function parseTemperature(value: string): number | null | false {
  const trimmed = value.trim()
  if (trimmed === '') return null
  if (!/^\d+(\.\d+)?$/.test(trimmed) && !/^\.\d+$/.test(trimmed)) return false
  const temperature = Number(trimmed)
  return temperature >= MIN_TEMPERATURE && temperature <= MAX_TEMPERATURE ? temperature : false
}

// @find: edit instructions form, instructions, temperature, save new revision, update agent
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
  const [temperature, setTemperature] = useState(agent.temperature == null ? '' : String(agent.temperature))
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
  const heat = parseTemperature(temperature)
  const heatProblem = heat === false ? `Enter a number from ${MIN_TEMPERATURE} to ${MAX_TEMPERATURE}, such as 0.3, or leave it empty.` : null
  const invalid = steps === false || heat === false || systemPrompt.trim() === ''

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (saving || invalid) return
    setFieldErrors({})
    onError(null)
    try {
      // An empty limit is sent as null, which the server saves as the default. Sending 0 once
      // saved a limit that failed every later run before its first step.
      // The output limit has no field here; it is sent as saved so a new revision keeps it.
      const saved = await update.mutateAsync({
        systemPrompt,
        goals,
        maxSteps: steps,
        temperature: heat,
        maxOutputTokens: agent.maxOutputTokens ?? null,
      })
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
          hint="Written to the agent: what it should do and how."
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
        <Input
          label="Temperature"
          optional
          inputMode="decimal"
          value={temperature}
          onChange={(e) => {
            setTemperature(e.target.value)
            clearError('temperature')
          }}
          placeholder="Model default"
          hint={`From ${MIN_TEMPERATURE} to ${MAX_TEMPERATURE}. Lower keeps answers steady and literal, higher makes them more varied. Leave it empty for the model's default.`}
          error={fieldErrors.temperature ?? heatProblem}
        />
      </div>
      <div className="dialog-footer" style={{ justifyContent: 'flex-end', gap: 'var(--space-3)' }}>
        <Button variant="outline" type="button" onClick={onDone} disabled={saving}>
          Cancel
        </Button>
        <Button type="submit" loading={saving} disabled={invalid}>
          Save instructions
        </Button>
      </div>
    </form>
  )
}

/* ---- Recent runs ---------------------------------------------------------------------------------- */

// @find: agent runs list, recent runs of this agent, give task button
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

/* ---- Connectors ------------------------------------------------------------------------------------ */

/** The grant's approval setting and call limit, in one sentence each. */
function grantRules(grant: AgentGrant): string {
  // A grant without its own requirement still stops wherever the tool or a policy requires it
  // (ToolGateway), so "never asks" would promise too much.
  const approval = grant.requireApproval
    ? 'Asks a person before every action.'
    : 'Asks a person before sending or deleting anything.'
  const limit =
    grant.maxCallsPerRun == null
      ? 'No limit on calls per run.'
      : `At most ${formatCount(grant.maxCallsPerRun)} ${grant.maxCallsPerRun === 1 ? 'call' : 'calls'} per run.`
  return `${approval} ${limit}`
}

function GrantRow({
  grant,
  integration,
  canManage,
  onEdit,
  onRemove,
}: {
  grant: AgentGrant
  integration: Integration | undefined
  canManage: boolean
  onEdit: () => void
  onRemove: () => void
}) {
  const name = serverLabel(grant.server, integration?.displayName)
  const state = integration ? connectorStateLabel(connectorState(integration)) : null
  const granted = integration ? new Set(grantedToolNames(grant, integration.tools)) : null

  return (
    <li className="stack" style={{ gap: 'var(--space-3)', padding: 'var(--space-5) 0', borderTop: '1px solid var(--line)' }}>
      <div className="row" style={{ justifyContent: 'space-between', flexWrap: 'wrap', gap: 'var(--space-3)' }}>
        <div className="row" style={{ flexWrap: 'wrap', gap: 'var(--space-3)', minWidth: 0 }}>
          <h3 className="section-heading" style={{ overflowWrap: 'anywhere' }}>
            {name}
          </h3>
          {state && <Tag tone={state.tone}>{state.label}</Tag>}
        </div>
        {canManage && (
          <div className="row" style={{ gap: 'var(--space-2)', flexWrap: 'wrap' }}>
            {/* Editing needs the connector's tools; one no longer offered can only be removed. */}
            {integration && (
              <Button variant="outline" className="button-sm" aria-label={`Edit what it may do in ${name}`} onClick={onEdit}>
                Edit
              </Button>
            )}
            <Button variant="danger" className="button-sm" aria-label={`Remove ${name}`} onClick={onRemove}>
              Remove
            </Button>
          </div>
        )}
      </div>
      {integration && granted ? (
        <CapabilityList
          tools={integration.tools.filter((tool) => granted.has(tool.name))}
          emptyText="Nothing this connector offers today is allowed."
        />
      ) : (
        // Without the connector list (a role that cannot read it, or a failed load) the tools are
        // named from the grant alone, without the grouping their side effects would need.
        <p className="caption">
          {grant.tools.length > 0
            ? `May use: ${grant.tools.map((tool) => sentenceCase(tool)).join(', ')}.`
            : 'May use everything this connector offers.'}
        </p>
      )}
      <p className="caption">{grantRules(grant)}</p>
    </li>
  )
}

// @find: agent connectors, grant connector to agent, revoke access, tools an agent may use, allowed actions, approval required
function AgentConnectors({ agent }: { agent: AgentDetailData }) {
  const toast = useToast()
  const canEdit = can('agent:grant_tools')
  const canReadConnectors = can('integration:read')
  const integrations = useIntegrations({ enabled: canReadConnectors })
  const remove = useRemoveAgentGrant(agent.id)
  // A new GrantDialog per opening (the key), so each one starts from the saved grant; the same
  // one closes, so focus goes back to the button that opened it.
  const [dialog, setDialog] = useState<{ session: number; server: string | null; open: boolean }>({
    session: 0,
    server: null,
    open: false,
  })
  const [removing, setRemoving] = useState<AgentGrant | null>(null)
  const [removeError, setRemoveError] = useState<string | null>(null)
  // The Remove button that opened the confirmation goes with its row, so focus moves to Add connector.
  const addRef = useRef<HTMLSpanElement>(null)

  const catalog = integrations.data
  const byServer = useMemo(() => new Map((catalog ?? []).map((item) => [item.server, item])), [catalog])
  const canManage = canEdit && catalog !== undefined
  const nameOf = (server: string) => serverLabel(server, byServer.get(server)?.displayName)

  const openDialog = (server: string | null) =>
    setDialog((current) => ({ session: current.session + 1, server, open: true }))

  const closeRemove = () => {
    if (remove.isPending) return
    setRemoving(null)
    setRemoveError(null)
  }

  const handleRemove = async () => {
    if (!removing) return
    setRemoveError(null)
    try {
      await remove.mutateAsync(removing.server)
      toast.success(`${nameOf(removing.server)} was removed from ${agent.name}`)
      setRemoving(null)
      // After the dialog has closed and tried to hand focus back to the removed button.
      window.setTimeout(() => addRef.current?.querySelector('button')?.focus(), 0)
    } catch (error) {
      setRemoveError(describeApiError(error))
    }
  }

  // Button does not take a ref, so the span holds one; display: contents leaves the layout alone.
  const addButton = canEdit ? (
    <span ref={addRef} style={{ display: 'contents' }}>
      <Button
        variant="outline"
        onClick={() => openDialog(null)}
        loading={canReadConnectors && integrations.isLoading}
        disabled={!canManage}
      >
        Add connector
      </Button>
    </span>
  ) : undefined

  return (
    <Card as="section">
      <div
        className="row"
        style={{ justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: 'var(--space-3)' }}
      >
        <div style={{ minWidth: 0, flex: '1 1 320px' }}>
          <Eyebrow as="h2">Connectors</Eyebrow>
          <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
            The apps and services this agent can act in, and exactly what it may do in each. Sending and
            deleting always wait for a person to approve them.
          </p>
        </div>
        {agent.grants.length > 0 && addButton}
      </div>

      {canEdit && !canReadConnectors && (
        <div style={{ marginBottom: 'var(--space-4)' }}>
          <Notice tone="info">
            Your role cannot see the connector list, so connectors cannot be added or changed from here.
          </Notice>
        </div>
      )}
      {canEdit && integrations.error != null && (
        <div style={{ marginBottom: 'var(--space-4)' }}>
          <Notice tone="warning">
            The connector list could not be loaded, so connectors cannot be added or changed right now.{' '}
            {describeApiError(integrations.error)}
          </Notice>
        </div>
      )}

      {agent.grants.length === 0 ? (
        <EmptyState
          titleAs="h3"
          icon={<EmptyIcon kind="agent" />}
          title="No connectors yet"
          body="This agent can answer from its instructions, but it cannot act in any other app or service."
          action={addButton}
        />
      ) : (
        <ul style={{ margin: 0, padding: 0, listStyle: 'none' }}>
          {agent.grants.map((grant) => (
            <GrantRow
              key={grant.server}
              grant={grant}
              integration={byServer.get(grant.server)}
              canManage={canManage}
              onEdit={() => openDialog(grant.server)}
              onRemove={() => {
                setRemoveError(null)
                setRemoving(grant)
              }}
            />
          ))}
        </ul>
      )}

      {canManage && catalog && (
        <GrantDialog
          key={dialog.session}
          open={dialog.open}
          onClose={() => setDialog((current) => ({ ...current, open: false }))}
          agent={agent}
          integrations={catalog}
          initialServer={dialog.server}
        />
      )}

      <ConfirmDialog
        open={removing !== null}
        onClose={closeRemove}
        onConfirm={handleRemove}
        eyebrow="Remove connector"
        title={removing ? `Remove ${nameOf(removing.server)} from ${agent.name}?` : 'Remove this connector?'}
        description={
          removing
            ? `${agent.name} will no longer be able to act in ${nameOf(removing.server)}. Nothing it has already done there is undone, and it can be added again at any time.`
            : undefined
        }
        confirmLabel="Remove connector"
        tone="danger"
        loading={remove.isPending}
        error={removeError}
      />
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

// @find: agent model routing, choose model for an agent, agent routing policy, override workspace models, fallback chain
function AgentRouting({ agent }: { agent: AgentDetailData }) {
  const policy = useAgentModelPolicy(agent.id)
  // The provider list and model catalogue need provider:read. Without it, providers are named
  // from the seeded list and models by their id.
  const canSeeRouting = can('provider:read')
  // Changing the chain needs the permission to set it and the lists to choose from.
  const canSetPolicy = can('agent:set_model_policy')
  const providers = useProviders({ enabled: canSeeRouting })
  const models = useModels({ enabled: canSeeRouting })
  const credentials = useCredentials({ enabled: canSeeRouting })
  const setPolicy = useSetAgentModelPolicy(agent.id)
  const clearPolicy = useClearAgentModelPolicy(agent.id)
  const removeCandidate = useRemoveAgentCandidate(agent.id)
  const toast = useToast()
  const now = useNow()
  // For the read-only list: the warning from the last removal here, else the one sent on reading.
  const [acted, setActed] = useState<{ warning: string | null } | null>(null)
  const [confirmDefault, setConfirmDefault] = useState(false)
  const [defaultError, setDefaultError] = useState<string | null>(null)
  const providerName = (id: string) =>
    providers.data?.find((provider) => provider.id === id)?.displayName ?? SEEDED_PROVIDERS[id] ?? sentenceCase(id)
  const modelName = (providerId: string, modelId: string) =>
    models.data?.find((model) => model.providerId === providerId && model.modelId === modelId)?.displayName

  const editable = canSetPolicy && canSeeRouting
  // Only a role that holds both can change the chain: it needs the lists to choose from.
  if (editable && policy.data && providers.data && models.data) {
    return (
      <PolicyEditor
        scope="agent"
        subject={agent.name}
        policy={policy.data}
        providers={providers.data}
        models={models.data}
        credentials={credentials.data}
        canManage
        now={now}
        onSave={(input) => setPolicy.mutateAsync(input)}
        liveCatalogue
        onClear={() => clearPolicy.mutateAsync()}
        onRemoveCandidate={(candidate) => removeCandidate.mutateAsync(candidate)}
        testable={can('provider:manage')}
      />
    )
  }

  // Somebody who may set the agent's models but cannot read the provider lists can still take
  // models out, or give the agent back to the workspace default, from the list below.
  const removeOne = async (candidate: { providerId: string; modelId: string }, name: string) => {
    try {
      const result = await removeCandidate.mutateAsync(candidate)
      setActed({ warning: warningOf(result) })
      toast.success(
        result.configured === false
          ? `${name} was removed. ${agent.name} now uses the workspace default.`
          : `${name} was removed from ${agent.name}'s routing.`,
      )
    } catch (err) {
      toast.error(describeApiError(err))
    }
  }

  const applyDefault = async () => {
    setDefaultError(null)
    try {
      const result = await clearPolicy.mutateAsync()
      setActed({ warning: warningOf(result) })
      setConfirmDefault(false)
      toast.success(`${agent.name} now uses the workspace default.`)
    } catch (err) {
      setDefaultError(describeApiError(err))
    }
  }

  const warning = acted ? acted.warning : (policy.data?.warning ?? null)

  let body
  if (policy.isLoading || (editable && (providers.isLoading || models.isLoading))) {
    body = <LoadingState rows={2} label="Loading this agent's model routing" />
  } else if (policy.error || !policy.data) {
    body = <Notice tone="warning">Its model routing could not be loaded. {describeApiError(policy.error)}</Notice>
  } else if (editable && (providers.error || models.error)) {
    body = (
      <Notice tone="warning">
        The models to choose from could not be loaded, so its routing cannot be changed right now.{' '}
        {describeApiError(providers.error ?? models.error)}
      </Notice>
    )
  } else if (policy.data.configured && policy.data.candidates.length > 0) {
    // The router skips an agent policy with no candidates, so only a non-empty one is the agent's own.
    const candidates = [...policy.data.candidates].sort((a, b) => a.position - b.position)
    body = (
      <>
        <p style={{ marginBottom: 'var(--space-4)' }}>This agent has its own routing. It tries these models in order:</p>
        <ol className="stack" style={{ gap: 'var(--space-3)', margin: 0, paddingLeft: 'var(--space-6)' }}>
          {candidates.map((candidate) => {
            const name = modelName(candidate.providerId, candidate.modelId)
            const removing =
              removeCandidate.isPending &&
              removeCandidate.variables?.providerId === candidate.providerId &&
              removeCandidate.variables?.modelId === candidate.modelId
            return (
              // Model ids run long ("meta-llama/llama-3.3-70b-instruct:free"); they wrap rather
              // than push a phone screen sideways.
              <li key={`${candidate.position}-${candidate.providerId}-${candidate.modelId}`} style={{ overflowWrap: 'anywhere' }}>
                {name ? <span title={candidate.modelId}>{name}</span> : <span className="mono">{candidate.modelId}</span>}{' '}
                <span className="caption">from {providerName(candidate.providerId)}</span>
                {canSetPolicy && (
                  <>
                    {' '}
                    <Button
                      variant="outline"
                      className="button-sm"
                      aria-label={`Remove ${name ?? candidate.modelId} from ${agent.name}'s routing`}
                      loading={removing}
                      disabled={removeCandidate.isPending && !removing}
                      onClick={() =>
                        void removeOne({ providerId: candidate.providerId, modelId: candidate.modelId }, name ?? candidate.modelId)
                      }
                    >
                      Remove
                    </Button>
                  </>
                )}
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
        It uses the workspace default.{' '}
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
      {canSetPolicy && !canSeeRouting && (
        <div style={{ marginBottom: 'var(--space-4)' }}>
          <Notice tone="info">
            Choosing models also needs a role that can see the model providers, so its routing cannot be changed from here.
          </Notice>
        </div>
      )}
      {warning && (
        <div style={{ marginBottom: 'var(--space-4)' }}>
          <Notice tone="warning" live>
            {warning}
          </Notice>
        </div>
      )}
      {body}
      {canSetPolicy && policy.data && (
        <div style={{ marginTop: 'var(--space-4)' }}>
          <Button
            variant="quiet"
            aria-label={`Use the workspace default for ${agent.name}`}
            onClick={() => {
              setDefaultError(null)
              setConfirmDefault(true)
            }}
          >
            Use workspace default
          </Button>
        </div>
      )}
      <ConfirmDialog
        open={confirmDefault}
        onClose={() => setConfirmDefault(false)}
        onConfirm={applyDefault}
        eyebrow="Model routing"
        title={`Use the workspace default for ${agent.name}?`}
        description={`${agent.name}'s own models are removed. From its next run it uses the workspace default. Work already running keeps the models it started with.`}
        confirmLabel="Use workspace default"
        cancelLabel="Keep its models"
        tone="primary"
        loading={clearPolicy.isPending}
        error={defaultError}
      />
    </Card>
  )
}

/* ---- Revisions ------------------------------------------------------------------------------------ */

/** How many revisions the card lists before offering the rest. */
const REVISIONS_SHOWN = 5

const REVISION_TEXT_STYLE = {
  background: 'var(--paper)',
  border: '1px solid var(--line)',
  borderRadius: 'var(--radius-control-lg)',
  padding: 'var(--space-4)',
  margin: 'var(--space-3) 0 0',
  whiteSpace: 'pre-wrap',
  overflowWrap: 'anywhere',
  color: 'var(--ink)',
  lineHeight: 1.7,
} as const

/** The settings a revision carries besides its words, as one line. */
function revisionLimits(revision: AgentRevision): string {
  const parts = [`Step limit ${formatCount(revision.maxSteps)}`]
  if (revision.temperature != null) parts.push(`temperature ${revision.temperature}`)
  if (revision.maxOutputTokens != null) parts.push(`output limit ${formatCount(revision.maxOutputTokens)} tokens`)
  return `${parts.join(', ')}.`
}

/**
 * Every revision of the agent's configuration, newest first: what changed in it, who saved it, and
 * whether a run has used it, which seals it so those traces stay accurate. Putting one back saves
 * its words and limits as a new revision on top, so the history only grows.
 */
// @find: agent revisions, version history, restore earlier version, roll back instructions
function AgentRevisions({ agent }: { agent: AgentDetailData }) {
  const toast = useToast()
  const canRestore = can('agent:update')
  const revisions = useAgentRevisions(agent.id)
  const members = useMemberNames({ enabled: can('member:read') })
  const restore = useRestoreRevision(agent.id)
  const [target, setTarget] = useState<AgentRevision | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [showAll, setShowAll] = useState(false)

  const who = (revision: AgentRevision): string | null => {
    if (!revision.createdBy) return null
    if (revision.createdBy === 'system') return 'the platform'
    return members[revision.createdBy]?.displayName ?? 'someone in the workspace'
  }

  const closeRestore = () => {
    if (restore.isPending) return
    setTarget(null)
    setError(null)
  }

  const handleRestore = async () => {
    if (!target) return
    setError(null)
    try {
      const saved = await restore.mutateAsync(target)
      toast.success(
        saved.revision == null
          ? `Revision ${target.revision} was restored`
          : `Revision ${target.revision} was restored as revision ${saved.revision}`,
      )
      setTarget(null)
    } catch (thrown) {
      setError(describeApiError(thrown, FIELD_LABELS))
    }
  }

  return (
    <Card as="section">
      <Eyebrow as="h2">Revisions</Eyebrow>
      <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
        Every saved version of this agent&apos;s instructions and limits. Runs keep the revision they used, so
        restoring an old one never changes what a past run did.
      </p>
      <QueryState
        query={revisions}
        permission="agent:read"
        what="this agent's revisions"
        rows={3}
        isEmpty={(rows) => rows.length === 0}
        empty={<p className="muted">No revision has been saved for this agent yet.</p>}
      >
        {(rows) => {
          const shown = showAll ? rows : rows.slice(0, REVISIONS_SHOWN)
          const current = rows.find((revision) => revision.current)
          const next = rows.reduce((highest, revision) => Math.max(highest, revision.revision), 0) + 1
          return (
            <>
              <ul style={{ margin: 0, padding: 0, listStyle: 'none' }}>
                {shown.map((revision) => {
                  const author = who(revision)
                  return (
                    <li
                      key={revision.id}
                      className="stack"
                      style={{ gap: 'var(--space-3)', padding: 'var(--space-5) 0', borderTop: '1px solid var(--line)' }}
                    >
                      <div
                        className="row"
                        style={{ justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: 'var(--space-3)' }}
                      >
                        <div style={{ minWidth: 0 }}>
                          <div className="row" style={{ flexWrap: 'wrap', gap: 'var(--space-3)' }}>
                            <h3 className="section-heading">Revision {revision.revision}</h3>
                            {revision.current && <Tag tone="success">Current</Tag>}
                            {revision.usedByRuns && (
                              <Tag title="A run has used this revision, so it is never edited and the trace of that run stays accurate.">
                                Sealed, used by runs
                              </Tag>
                            )}
                          </div>
                          <p className="caption">
                            {changeSummary(revision)}. Saved <Time iso={revision.createdAt} />
                            {author ? ` by ${author}` : ''}.
                          </p>
                        </div>
                        {canRestore && !revision.current && (
                          <Button
                            variant="outline"
                            className="button-sm"
                            aria-label={`Restore revision ${revision.revision}`}
                            onClick={() => {
                              setError(null)
                              setTarget(revision)
                            }}
                          >
                            Restore
                          </Button>
                        )}
                      </div>
                      <details>
                        <summary className="caption" style={{ cursor: 'pointer' }}>
                          Read revision {revision.revision}
                        </summary>
                        <pre className="mono" style={REVISION_TEXT_STYLE}>
                          {revision.systemPrompt?.trim() ? revision.systemPrompt : 'No instructions were saved in this revision.'}
                        </pre>
                        {revision.goals?.trim() && (
                          <p style={{ whiteSpace: 'pre-wrap', marginTop: 'var(--space-3)' }}>
                            <span className="caption">Goals: </span>
                            {revision.goals}
                          </p>
                        )}
                        <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
                          {revisionLimits(revision)}
                        </p>
                      </details>
                    </li>
                  )
                })}
              </ul>
              {rows.length > REVISIONS_SHOWN && (
                <div style={{ marginTop: 'var(--space-3)' }}>
                  <Button variant="quiet" className="button-sm" onClick={() => setShowAll((all) => !all)}>
                    {showAll ? 'Show fewer revisions' : `Show all ${formatCount(rows.length)} revisions`}
                  </Button>
                </div>
              )}

              <ConfirmDialog
                open={target !== null}
                onClose={closeRestore}
                onConfirm={handleRestore}
                eyebrow="Restore a revision"
                title={target ? `Restore revision ${target.revision}?` : 'Restore this revision?'}
                description={
                  target
                    ? `Its instructions, goals and limits are saved again as revision ${next}${current ? `, on top of revision ${current.revision}` : ''}. Nothing is deleted, and runs already recorded keep the revision they used.`
                    : undefined
                }
                confirmLabel={target ? `Restore revision ${target.revision}` : 'Restore'}
                cancelLabel="Keep the current one"
                tone="primary"
                loading={restore.isPending}
                error={error}
              />
            </>
          )
        }}
      </QueryState>
    </Card>
  )
}

/* ---- Voice ------------------------------------------------------------------------------------------ */

/** The words a preview says, naming the agent so two agents previewed back to back are told apart. */
const previewLine = (agentName: string) => `Hello, I am ${agentName}. This is how I will sound.`

// @find: agent voice card, choose voice, text to speech, spoken answers
function AgentVoiceCard({ agent }: { agent: AgentDetailData }) {
  const toast = useToast()
  const canSetVoice = can('agent:update')
  const status = useVoiceStatus()
  const elevenlabs = status.data?.provider === 'elevenlabs'
  const voicesQuery = useVoices({ enabled: elevenlabs })
  const setVoice = useSetAgentVoice(agent.id)
  const speaker = useSpeaker()

  const [selected, setSelected] = useState(agent.voiceId ?? '')
  const [error, setError] = useState<string | null>(null)
  const dirty = selected !== (agent.voiceId ?? '')

  const voices = voicesQuery.data ?? []
  // Without a stored key every agent speaks with the browser voice, whatever voiceId it once had
  // saved, so the name lookup only matters while ElevenLabs is actually the one speaking.
  const currentVoiceName =
    !elevenlabs || !agent.voiceId
      ? 'Browser voice'
      : (voices.find((voice) => voice.voiceId === agent.voiceId)?.name ?? 'A stored voice')

  const handlePreview = () => {
    void speaker.speak(previewLine(agent.name), agent.id)
  }

  const handleSave = async () => {
    setError(null)
    try {
      await setVoice.mutateAsync(selected || null)
      toast.success(`${agent.name}'s voice was saved`)
    } catch (err) {
      setError(describeApiError(err))
    }
  }

  return (
    <Card as="section">
      <Eyebrow as="h2">Voice</Eyebrow>
      <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
        What this agent sounds like when it speaks in Chat or leaves a voice note.
      </p>

      {error && (
        <div style={{ marginBottom: 'var(--space-4)' }}>
          <Notice tone="warning" live>
            {error}
          </Notice>
        </div>
      )}

      <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-3)', marginBottom: 'var(--space-5)' }}>
        <span className="muted">Current voice</span>
        <span>{status.isLoading ? '—' : currentVoiceName}</span>
      </div>

      {status.isLoading ? (
        <LoadingState rows={2} label="Loading this workspace's voice status" />
      ) : status.error ? (
        <Notice tone="warning">Its voice status could not be loaded. {describeApiError(status.error)}</Notice>
      ) : elevenlabs ? (
        <div className="stack" style={{ gap: 'var(--space-4)' }}>
          <Select
            label="Voice"
            value={selected}
            onChange={(event) => setSelected(event.target.value)}
            disabled={voicesQuery.isLoading || setVoice.isPending}
            hint="Chosen from the voices stored for this workspace's ElevenLabs account."
          >
            <option value="">Browser voice</option>
            {voicesQuery.isLoading && <option disabled>Loading voices…</option>}
            {voices.map((voice) => (
              <option key={voice.voiceId} value={voice.voiceId}>
                {voice.name}
              </option>
            ))}
          </Select>
          <div className="row" style={{ gap: 'var(--space-3)', alignItems: 'center' }}>
            <Button variant="outline" onClick={handlePreview} loading={speaker.speaking} disabled={speaker.provider === 'none'}>
              Preview
            </Button>
            {speaker.speaking && (
              <span className="row" style={{ gap: 'var(--space-2)', alignItems: 'center' }}>
                <span className="sch-speaking-dot" aria-hidden="true" />
                <span className="caption">Speaking</span>
              </span>
            )}
            {canSetVoice && (
              <Button onClick={() => void handleSave()} loading={setVoice.isPending} disabled={!dirty}>
                Save voice
              </Button>
            )}
          </div>
          {dirty && (
            <p className="caption">Preview plays the voice already saved for this agent. Save this choice to hear it instead.</p>
          )}
        </div>
      ) : (
        <div className="stack" style={{ gap: 'var(--space-4)' }}>
          <Notice tone="info">
            No ElevenLabs key is stored for this workspace, so every agent speaks with your browser's built-in
            voice instead.{' '}
            {can('integration:connect') ? 'A key can be added from Connectors.' : 'An owner or admin can add one in Connectors.'}
          </Notice>
          <div className="row" style={{ gap: 'var(--space-2)', alignItems: 'center' }}>
            <Button variant="outline" onClick={handlePreview} loading={speaker.speaking} disabled={speaker.provider === 'none'}>
              Preview
            </Button>
            {speaker.speaking && (
              <span className="row" style={{ gap: 'var(--space-2)', alignItems: 'center' }}>
                <span className="sch-speaking-dot" aria-hidden="true" />
                <span className="caption">Speaking</span>
              </span>
            )}
          </div>
        </div>
      )}
    </Card>
  )
}

/* ---- Page ----------------------------------------------------------------------------------------- */

/**
 * The value and note of an outcome tile: the figure once the outcomes have loaded, and a plain
 * line about it until then, so a failed or slow request never shows as 0% or US$0.
 */
function outcomeTile(
  outcomes: Pick<ReturnType<typeof useAgentOutcomes>, 'data' | 'error'>,
  figure: { value: string; note: string },
): { value: string; note: string } {
  if (outcomes.data) return figure
  return { value: '—', note: outcomes.error ? 'Its outcomes could not be loaded.' : 'Loading its outcomes.' }
}

// @find: AgentDetail component, agent page, pause agent, resume agent, delete agent, /agents/:id
export function AgentDetail({ id }: { id: string }) {
  const query = useAgent(id)
  const [editOpen, setEditOpen] = useState(false)
  const [descriptionOpen, setDescriptionOpen] = useState(false)
  const [taskDialogOpen, setTaskDialogOpen] = useState(false)
  const canEdit = can('agent:update')
  const canRun = can('agent:run')
  const canReadRuns = can('run:read')
  useDocumentTitle(query.data?.name)
  // How the agent has done over 30 days. It needs run:read; without it the tiles are not shown.
  const outcomes = useAgentOutcomes({ enabled: canReadRuns })
  const outcome = outcomes.data?.[id]
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
                description={
                  agentDescription(agent) || canEdit ? (
                    <>
                      {agentDescription(agent) || 'No description yet.'}{' '}
                      {canEdit && (
                        <button
                          type="button"
                          className="link"
                          aria-label={`Edit what ${agent.name} does`}
                          onClick={() => setDescriptionOpen(true)}
                        >
                          Edit
                        </button>
                      )}
                    </>
                  ) : undefined
                }
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
                      {/* Hides itself for a retired agent, which cannot be paused or resumed. */}
                      {canEdit && <AgentStatusButton agent={agent} />}
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
                  {canReadRuns ? (
                    <>
                      <StatTile
                        label="Success rate (30 days)"
                        {...outcomeTile(outcomes, successRateFigure(outcome))}
                      />
                      <StatTile label="Cost (30 days)" {...outcomeTile(outcomes, costFigure(outcome))} />
                      <StatTile
                        label="Runs (30 days)"
                        {...outcomeTile(outcomes, {
                          value: formatCount(outcome?.runs ?? 0),
                          note:
                            outcome && outcome.runs > 0
                              ? `${formatCount(outcome.completed)} completed, ${formatCount(outcome.failed)} failed.`
                              : 'None in the last 30 days.',
                        })}
                      />
                    </>
                  ) : (
                    <StatTile
                      label="Step limit"
                      value={agent.maxSteps == null ? '—' : formatCount(agent.maxSteps)}
                      note="The most steps one run may take."
                    />
                  )}
                  <StatTile
                    label="Connectors"
                    value={formatCount(agent.grants.length)}
                    note="Apps and services it can act in."
                  />
                  {canReadRuns && (
                    <StatTile
                      label="Last run"
                      value={lastRun ? formatRelativeTicked(lastRun.startedAt, now, 60_000) : runs.data ? 'Never' : '—'}
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
                  )}
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
                      <h3 className="section-heading" style={{ margin: 'var(--space-6) 0 var(--space-3)' }}>
                        Goals
                      </h3>
                      <p style={{ whiteSpace: 'pre-wrap' }}>{agent.goals}</p>
                    </>
                  )}
                </Card>
              </div>

              <div style={{ marginTop: 'var(--space-6)' }}>
                <AgentMemoryCard key={`memory-${agent.id}`} agentId={agent.id} agentName={agent.name} />
              </div>

              <div style={{ marginTop: 'var(--space-6)' }}>
                <AgentDocumentsCard key={`documents-${agent.id}`} agentId={agent.id} agentName={agent.name} />
              </div>

              <div style={{ marginTop: 'var(--space-6)' }}>
                <AgentRevisions key={agent.id} agent={agent} />
              </div>

              <div style={{ marginTop: 'var(--space-6)' }}>
                <AgentConnectors agent={agent} />
              </div>

              <div style={{ marginTop: 'var(--space-6)' }}>
                {/* Keyed by agent, so a draft chain or a confirm step never carries over to another agent. */}
                <AgentRouting key={agent.id} agent={agent} />
              </div>

              {/* An agent speaks only in Chat, and its voice settings are read with chat:use; a role
                  without Chat (a viewer) would only get an error here. */}
              {can('chat:use') && (
                <div style={{ marginTop: 'var(--space-6)' }}>
                  <AgentVoiceCard agent={agent} />
                </div>
              )}

              {(can('agent:update') || can('agent:delete')) && (
                <div style={{ marginTop: 'var(--space-6)' }}>
                  <RetireAgentCard agent={agent} />
                </div>
              )}

              {canEdit && <EditInstructionsDialog open={editOpen} onClose={() => setEditOpen(false)} agent={agent} />}
              {canEdit && (
                <EditDescriptionDialog
                  key={`description-${agent.id}-${agent.description ?? ''}`}
                  open={descriptionOpen}
                  onClose={() => setDescriptionOpen(false)}
                  agent={agent}
                />
              )}

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
