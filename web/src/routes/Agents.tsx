// @find: agents list, create agent, new agent, add assistant, ready-made assistants, templates, suggested assistants, from template, agent cards, success rate, 30-day cost, outcomes, General Employee, /agents, Agents page
// @what: The Agents page: lists the workspace's AI assistants with their results, and lets people create one from scratch or from a ready-made template.
// @flow: Routed from App.tsx at /agents; each card links to AgentDetail.tsx
import { useState } from 'react'
import {
  Button,
  CATEGORY_LABEL,
  Card,
  Dialog,
  EmptyState,
  Eyebrow,
  Input,
  Notice,
  PageHeader,
  Select,
  StatusTag,
  Textarea,
} from '../components/ui'
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
// Imported from its own module, not the ../components/ui barrel: this screen is lazy-loaded, and
// the barrel is also part of the main bundle, so going through it created a circular chunk
// dependency (Rollup warned of a "broken execution order").
import { FilterBar, FilterEmpty } from '../components/ui/FilterBar'
import { Collapsible } from '../components/ui/Collapsible'
import { AgentStatusButton } from '../components/agents/AgentStatusButton'
import { costFigure, successRateFigure, useAgentOutcomes } from '../lib/agentQueries'
import type { AgentOutcome } from '../lib/agentQueries'
import { agentDescription } from '../lib/agentDescription'
import { ApiError, describeApiError } from '../lib/api'
import { nameList, sentenceCase } from '../lib/format'
import { serverLabel, statusLabel } from '../lib/labels'
import { useAgents, useCreateAgent, type Agent } from '../lib/queries'
import { useToast } from '../lib/toast'
import { useRouter } from '../lib/router'
import { can } from '../lib/session'
import { useCreateFromTemplate, useAgentTemplates, type FromTemplate, type TemplateView } from '../lib/templateQueries'
import { connectPrompts } from '../lib/templates'
import { useListFilter } from '../lib/useListFilter'

/** A search field is worth its space only once the grid no longer fits on a screen or two. */
const SEARCH_THRESHOLD = 8

/* Limits from AgentController.CreateAgentRequest, so the form refuses what the server would. */
const KEY_MAX = 60
const NAME_MAX = 120
const PROMPT_MAX = 20_000
const DESCRIPTION_MAX = 200

/** Lowercase words joined by single hyphens: 'people-ops', 'research-2'. */
const KEY_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/

/** The key a name suggests, by the same rule the workspace address uses when creating a workspace. */
function keyFromName(name: string): string {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')
    .slice(0, KEY_MAX)
    .replace(/-$/, '')
}

/** The labels on the form, so a validation message names the field the person can see. */
const FIELD_LABELS: Record<string, string> = {
  name: 'Name',
  description: 'What it does',
  key: 'Key',
  category: 'Category',
  systemPrompt: 'Instructions',
}

function categoryLabel(category: string): string {
  return CATEGORY_LABEL[category] ?? sentenceCase(category)
}

function agentSearchText(agent: Agent): string {
  return [
    agent.name,
    agent.key,
    categoryLabel(agent.category),
    agentDescription(agent),
    agent.summary ?? '',
    ...(agent.tools ?? []).map((server) => serverLabel(server)),
  ].join(' ')
}

/** How the outcome figures stand: still loading, loaded, or not shown at all (no permission, or they failed). */
type Outcomes = { state: 'loading' } | { state: 'ready'; byAgent: Record<string, AgentOutcome> } | null

/**
 * Two figures on every card, so a manager can compare agents without opening each one: how often
 * its finished runs complete, and what the last 30 days cost. Below five finished runs the first
 * says so instead of showing a percentage that means nothing.
 */
// @find: agent outcome figures, success rate, 30-day cost, not enough runs yet
function OutcomeFigures({ agent, outcomes }: { agent: Agent; outcomes: Exclude<Outcomes, null> }) {
  const outcome = outcomes.state === 'ready' ? outcomes.byAgent[agent.id] : undefined
  const loading = outcomes.state === 'loading'
  const success = loading ? null : successRateFigure(outcome)
  const cost = loading ? null : costFigure(outcome)
  const figure = { margin: 0, fontWeight: 'var(--weight-strong)' } as const
  return (
    <dl style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--space-2) var(--space-6)', margin: 0 }}>
      <div>
        <dt className="caption">Success rate (30 days)</dt>
        <dd className="tabular" style={figure} title={success?.note}>
          {success?.value ?? '—'}
        </dd>
      </div>
      <div>
        <dt className="caption">Cost (30 days)</dt>
        <dd className="tabular" style={figure} title={cost?.note}>
          {cost?.value ?? '—'}
        </dd>
      </div>
    </dl>
  )
}

// @find: agent card, open agent, status, pause state
function AgentCard({ agent, outcomes }: { agent: Agent; outcomes: Outcomes }) {
  const tools = agent.tools
  // Pause or Resume sits on the card's corner, beside the card rather than inside its link: a
  // button inside a link is not valid, and pressing it must not open the agent.
  const toggle = can('agent:update') && agent.status !== 'retired'
  const description = agentDescription(agent)
  const descriptionId = `agent-card-desc-${agent.id}`
  return (
    <div style={{ position: 'relative', display: 'grid' }}>
      {/* The whole card is one link. Its name is the agent's name and status, and what it does is
          its description, so a screen reader announces "Open HR, Active" rather than every line on
          the card run together. */}
      <Card
        href={`/agents/${agent.id}`}
        aria-label={`Open ${agent.name}, ${statusLabel('agent', agent.status).label}`}
        aria-describedby={description ? descriptionId : undefined}
      >
        <Eyebrow>{categoryLabel(agent.category)}</Eyebrow>
        <div
          className="row"
          style={{
            justifyContent: 'space-between',
            alignItems: 'flex-start',
            flexWrap: 'wrap',
            gap: 'var(--space-2) var(--space-3)',
            marginBottom: 'var(--space-3)',
          }}
        >
          <h2 className="section-heading" style={{ overflowWrap: 'anywhere' }}>
            {agent.name}
          </h2>
          <StatusTag kind="agent" status={agent.status} />
        </div>
        {/* What it does, written about it: its own description, else a line derived from its
            instructions, never the second-person instructions quoted back. */}
        {description ? (
          <p id={descriptionId} className="muted" style={{ marginBottom: 'var(--space-4)' }}>
            {description}
          </p>
        ) : (
          // Only an agent with no saved revision has no instructions. A missing summary on one that
          // has a revision says nothing about its instructions, so nothing is claimed.
          agent.revision == null && (
            <p className="muted" style={{ marginBottom: 'var(--space-4)' }}>
              No instructions have been saved yet.
            </p>
          )
        )}
        {/* Which connectors it can act in, by name, so the card answers "what can it reach" before
            anybody opens it. */}
        {tools != null && (
          <p className="caption">
            {tools.length > 0
              ? `Connectors: ${tools.map((server) => serverLabel(server)).join(', ')}`
              : 'No connectors yet'}
          </p>
        )}
        {(outcomes || toggle) && (
          // Room for the button laid over this corner, so the figures never run under it.
          <div
            style={{
              marginTop: 'var(--space-4)',
              minHeight: toggle ? 'var(--space-7)' : undefined,
              paddingRight: toggle ? 'calc(var(--space-8) * 2)' : undefined,
            }}
          >
            {outcomes && <OutcomeFigures agent={agent} outcomes={outcomes} />}
          </div>
        )}
      </Card>
      {toggle && (
        <div style={{ position: 'absolute', right: 'var(--space-6)', bottom: 'var(--space-6)' }}>
          <AgentStatusButton agent={agent} className="button-sm" />
        </div>
      )}
    </div>
  )
}

/** The connectors a template works with, by name: "Gmail and Google Calendar". */
function connectorsLine(template: TemplateView): string {
  return nameList(template.suggestedConnectors.map((server) => serverLabel(server)), 6)
}

/**
 * One ready-made assistant: what it does, what it works with, and a button to add it. The button
 * names the assistant, so a list of four "Add" buttons is not four identical controls.
 */
// @find: template card, ready-made assistant, add from template
function TemplateCard({
  template,
  pending,
  disabled,
  onAdd,
}: {
  template: TemplateView
  pending: boolean
  disabled: boolean
  onAdd: () => void
}) {
  return (
    <Card>
      <Eyebrow>{categoryLabel(template.category)}</Eyebrow>
      <h3 className="section-heading" style={{ marginBottom: 'var(--space-2)' }}>
        {template.name}
      </h3>
      <p className="muted" style={{ marginBottom: 'var(--space-3)' }}>
        {template.description}
      </p>
      <p className="caption" style={{ marginBottom: 'var(--space-4)' }}>
        Works with {connectorsLine(template)}
      </p>
      <Button
        variant="outline"
        className="button-sm"
        aria-label={`Add the ${template.name} assistant`}
        loading={pending}
        disabled={disabled}
        onClick={onAdd}
      >
        Add {template.name}
      </Button>
    </Card>
  )
}

/**
 * The same assistant as a compact row, for the dialog, where four cards one under another would
 * push "Start from scratch" below the fold. The button names the assistant for the same reason.
 */
// @find: template row, ready-made assistant list item, add assistant
function TemplateRow({
  template,
  pending,
  disabled,
  onAdd,
}: {
  template: TemplateView
  pending: boolean
  disabled: boolean
  onAdd: () => void
}) {
  return (
    <div
      className="row"
      style={{
        alignItems: 'flex-start',
        justifyContent: 'space-between',
        gap: 'var(--space-4)',
        padding: 'var(--space-4)',
        border: '1px solid var(--line)',
        borderRadius: 'var(--radius-control-lg)',
      }}
    >
      <div style={{ minWidth: 0 }}>
        <h4 className="section-heading">{template.name}</h4>
        <p className="muted" style={{ margin: 'var(--space-1) 0' }}>
          {template.description}
        </p>
        <p className="caption">Works with {connectorsLine(template)}</p>
      </div>
      <Button
        variant="outline"
        className="button-sm"
        aria-label={`Add the ${template.name} assistant`}
        loading={pending}
        disabled={disabled}
        onClick={onAdd}
      >
        Add
      </Button>
    </div>
  )
}

/**
 * Adds a ready-made assistant and hands the result on, for the dialog and the suggestions strip
 * alike. Says what went wrong in words when it cannot, and never leaves a button spinning.
 */
function useAddAssistant(onAdded: (result: FromTemplate) => void, onFailed: (message: string) => void) {
  const create = useCreateFromTemplate()
  return {
    pendingKey: create.isPending ? create.variables : null,
    busy: create.isPending,
    add: async (template: TemplateView) => {
      try {
        onAdded(await create.mutateAsync(template.key))
      } catch (error) {
        onFailed(describeApiError(error))
      }
    },
  }
}

// @find: create agent dialog, new agent, Create agent button
function CreateAgentDialog({
  open,
  onClose,
  onAdded,
}: {
  open: boolean
  onClose: () => void
  onAdded: (result: FromTemplate) => void
}) {
  const createAgent = useCreateAgent()
  const templates = useAgentTemplates({ enabled: open })
  // First a choice between a ready-made assistant and a blank page; the blank page is the form.
  const [view, setView] = useState<'choose' | 'scratch'>('choose')
  const [error, setError] = useState<string | null>(null)

  const close = () => {
    setError(null)
    setView('choose')
    createAgent.reset()
    onClose()
  }

  const adding = useAddAssistant(
    (result) => {
      onAdded(result)
      close()
    },
    setError,
  )

  return (
    <Dialog
      open={open}
      onClose={close}
      eyebrow="New agent"
      title="Add an agent"
      description={
        view === 'choose'
          ? 'Start from a ready-made assistant, or write your own from scratch. You can change any assistant’s instructions afterwards.'
          : 'Name the agent, choose its category and write the instructions it works from.'
      }
      dismissible={!createAgent.isPending && !adding.busy}
      error={error}
    >
      {/* Mounted only while open, so a cancelled draft does not come back the next time. */}
      {open && view === 'choose' && (
        <div className="stack" style={{ gap: 'var(--space-4)' }}>
          {/* Both ways in sit above the list, so neither is below the fold on a short screen. */}
          <div
            className="row"
            style={{ justifyContent: 'space-between', alignItems: 'center', gap: 'var(--space-3)', flexWrap: 'wrap' }}
          >
            <h3 className="section-heading">Start from a ready-made assistant</h3>
            <Button variant="outline" className="button-sm" onClick={() => setView('scratch')} disabled={adding.busy}>
              Start from scratch
            </Button>
          </div>
          <ul
            aria-label="Ready-made assistants"
            style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: 'var(--space-3)' }}
          >
            {templates.map((template) => (
              <li key={template.key}>
                <TemplateRow
                  template={template}
                  pending={adding.pendingKey === template.key}
                  disabled={adding.busy}
                  onAdd={() => {
                    setError(null)
                    void adding.add(template)
                  }}
                />
              </li>
            ))}
          </ul>
          <div className="dialog-footer">
            <Button variant="outline" onClick={close} disabled={adding.busy}>
              Cancel
            </Button>
          </div>
        </div>
      )}
      {open && view === 'scratch' && (
        <CreateAgentForm createAgent={createAgent} onError={setError} onDone={close} onBack={() => setView('choose')} />
      )}
    </Dialog>
  )
}

/**
 * After a ready-made assistant is added: what exists now, and what is still to do. The assistant
 * starts with no connectors, so each one it works with is a prompt to connect, never a claim that
 * it can already act there.
 */
// @find: added assistant confirmation, assistant added, what to do next
function AddedAssistantDialog({ added, onClose }: { added: FromTemplate | null; onClose: () => void }) {
  const { navigate } = useRouter()
  const agent = added?.agent
  const prompts = added ? connectPrompts(added.suggestedConnectors, (server) => serverLabel(server)) : []
  const canOpenConnectors = can('integration:read')
  return (
    <Dialog
      open={added !== null}
      onClose={onClose}
      eyebrow="New agent"
      title={agent ? `${agent.name} is ready` : 'The assistant is ready'}
      description="It starts from the ready-made instructions, which you can change on its page. It cannot act in any connector yet."
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            Done
          </Button>
          {agent && (
            <Button
              onClick={() => {
                onClose()
                navigate(`/agents/${agent.id}`)
              }}
            >
              Open {agent.name}
            </Button>
          )}
        </>
      }
    >
      {prompts.length > 0 && (
        <div className="stack" style={{ gap: 'var(--space-3)' }}>
          <h3 className="section-heading">To let it act</h3>
          <ul className="stack" style={{ gap: 'var(--space-2)', margin: 0, paddingLeft: 'var(--space-5)' }}>
            {prompts.map((prompt) => (
              <li key={prompt}>
                {canOpenConnectors ? (
                  <a className="link" href="/connectors">
                    {prompt}
                  </a>
                ) : (
                  prompt
                )}
              </li>
            ))}
          </ul>
          {!canOpenConnectors && (
            <p className="caption">An owner or admin can connect these in Connectors.</p>
          )}
        </div>
      )}
    </Dialog>
  )
}

// @find: create agent form, name, description, instructions, POST /api/agents, create agent from scratch
function CreateAgentForm({
  createAgent,
  onError,
  onDone,
  onBack,
}: {
  createAgent: ReturnType<typeof useCreateAgent>
  onError: (message: string | null) => void
  onDone: () => void
  onBack: () => void
}) {
  const { navigate } = useRouter()
  const toast = useToast()
  const [name, setName] = useState('')
  // Until somebody types a key of their own, it follows the name.
  const [typedKey, setTypedKey] = useState<string | null>(null)
  const [category, setCategory] = useState('operations')
  const [description, setDescription] = useState('')
  const [systemPrompt, setSystemPrompt] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [advancedOpen, setAdvancedOpen] = useState(false)

  /** A server's complaint about a field goes once the field is changed. */
  const clearError = (field: string) =>
    setFieldErrors((current) => {
      if (!(field in current)) return current
      const rest = { ...current }
      delete rest[field]
      return rest
    })

  const key = typedKey ?? keyFromName(name)
  const keyProblem = key
    ? KEY_PATTERN.test(key)
      ? null
      : 'Use lowercase letters and numbers, with single hyphens between words.'
    : name.trim()
      ? 'Use letters or numbers in the name, or write a key here.'
      : null
  const saving = createAgent.isPending
  const complete = name.trim() !== '' && key !== '' && systemPrompt.trim() !== '' && !keyProblem
  // The key is tucked away until it needs attention: a clash, or a name it cannot be made from.
  const keyShown = advancedOpen || Boolean(fieldErrors.key ?? keyProblem)

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!complete || saving) return
    setFieldErrors({})
    onError(null)
    try {
      const agent = await createAgent.mutateAsync({
        key,
        name: name.trim(),
        category,
        systemPrompt,
        ...(description.trim() ? { description: description.trim() } : {}),
      })
      toast.success(`${agent.name} was added`)
      onDone()
      navigate(`/agents/${agent.id}`)
    } catch (error) {
      if (error instanceof ApiError && error.code === 'already_exists') {
        setFieldErrors({ key: 'Another agent already uses this key.' })
        return
      }
      const fields = error instanceof ApiError ? error.fields : {}
      const shown = Object.fromEntries(Object.entries(fields).filter(([field]) => field in FIELD_LABELS))
      setFieldErrors(shown)
      // Problems beside their fields need no second copy above the form, unless some field is
      // not on the form at all.
      const allShown = Object.keys(shown).length > 0 && Object.keys(shown).length === Object.keys(fields).length
      onError(allShown ? null : describeApiError(error, FIELD_LABELS))
    }
  }

  return (
    <form onSubmit={handleSubmit}>
      {/* Spacing between whole fields, so each hint stays with the field it describes. */}
      <div className="stack" style={{ gap: 'var(--space-4)', marginBottom: 'var(--space-6)' }}>
        <Input
          label="Name"
          value={name}
          onChange={(e) => {
            setName(e.target.value)
            clearError('name')
            // The name also changes a key that is still following it.
            if (typedKey === null) clearError('key')
          }}
          placeholder="e.g. People operations"
          required
          maxLength={NAME_MAX}
          error={fieldErrors.name}
          data-autofocus
        />
        <Select
          label="Category"
          value={category}
          onChange={(e) => {
            setCategory(e.target.value)
            clearError('category')
          }}
          error={fieldErrors.category}
        >
          {Object.entries(CATEGORY_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </Select>
        <Input
          label="What it does"
          optional
          value={description}
          onChange={(e) => {
            setDescription(e.target.value)
            clearError('description')
          }}
          placeholder="e.g. Screens job applications and drafts replies to candidates."
          maxLength={DESCRIPTION_MAX}
          hint="One line about the agent, shown on its card and in Chat. Leave it empty to use its instructions."
          error={fieldErrors.description}
        />
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
        {/* The key is made from the name and rarely needs a look, so it sits behind Advanced. */}
        <details open={keyShown} onToggle={(event) => setAdvancedOpen(event.currentTarget.open)}>
          <summary className="section-heading" style={{ cursor: 'pointer' }}>
            Advanced
          </summary>
          <div style={{ marginTop: 'var(--space-4)' }}>
            <Input
              label="Key"
              value={key}
              onChange={(e) => {
                setTypedKey(e.target.value)
                clearError('key')
              }}
              placeholder="e.g. people-ops"
              maxLength={KEY_MAX}
              autoComplete="off"
              spellCheck={false}
              hint="Filled in from the name. Lowercase letters, numbers and hyphens. It cannot be changed later."
              error={fieldErrors.key ?? keyProblem ?? undefined}
            />
          </div>
        </details>
      </div>
      <div className="dialog-footer" style={{ justifyContent: 'space-between', gap: 'var(--space-3)' }}>
        <Button variant="quiet" type="button" onClick={onBack} disabled={saving}>
          Back to ready-made assistants
        </Button>
        <div className="row" style={{ gap: 'var(--space-3)' }}>
          <Button variant="outline" type="button" onClick={onDone} disabled={saving}>
            Cancel
          </Button>
          <Button type="submit" loading={saving} disabled={!complete}>
            Add agent
          </Button>
        </div>
      </div>
    </form>
  )
}

/**
 * Offered while the General Employee is the only agent, which is how every new workspace starts:
 * the ready-made assistants are one click away instead of behind the Add dialog, because a blank
 * instructions box is the slowest way to a first useful agent.
 */
// @find: suggested assistants, ready-made assistants, POST /api/agents/from-template, starter agents
function SuggestedAssistants({ onAdded }: { onAdded: (result: FromTemplate) => void }) {
  const templates = useAgentTemplates()
  const [error, setError] = useState<string | null>(null)
  const adding = useAddAssistant(onAdded, setError)
  return (
    <Card as="section">
      <Eyebrow as="h2">Suggested assistants</Eyebrow>
      <p className="muted" style={{ maxWidth: '62ch', marginBottom: 'var(--space-5)' }}>
        This workspace has only the General Employee so far. Add a ready-made assistant to give it a head start; each
        one can be changed after it is added.
      </p>
      {error && (
        <div style={{ marginBottom: 'var(--space-4)' }}>
          <Notice tone="warning" live>
            {error}
          </Notice>
        </div>
      )}
      <ul
        aria-label="Ready-made assistants"
        style={{
          listStyle: 'none',
          margin: 0,
          padding: 0,
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(min(260px, 100%), 1fr))',
          gap: 'var(--space-4)',
        }}
      >
        {templates.map((template) => (
          <li key={template.key} style={{ display: 'grid' }}>
            <TemplateCard
              template={template}
              pending={adding.pendingKey === template.key}
              disabled={adding.busy}
              onAdd={() => {
                setError(null)
                void adding.add(template)
              }}
            />
          </li>
        ))}
      </ul>
    </Card>
  )
}

// @find: Agents component, agents page, list agents, create agent, /agents
export function Agents() {
  const query = useAgents()
  // The figures need run:read. If they cannot be had the cards say nothing rather than guess.
  const outcomeData = useAgentOutcomes({ enabled: can('run:read') })
  const outcomes: Outcomes = !can('run:read') || outcomeData.isError
    ? null
    : outcomeData.data
      ? { state: 'ready', byAgent: outcomeData.data }
      : { state: 'loading' }
  const [createOpen, setCreateOpen] = useState(false)
  // The assistant just added from a ready-made brief, until its "what is still to do" is closed.
  const [added, setAdded] = useState<FromTemplate | null>(null)
  const [retiredOpen, setRetiredOpen] = useState(false)
  const canCreate = can('agent:create')

  const filter = useListFilter({ rows: query.data, text: agentSearchText })
  // A workspace small enough to see at a glance gets no search, and a stale ?q= in a link must
  // not hide agents behind a field that is not on screen.
  const searchable = (query.data?.length ?? 0) > SEARCH_THRESHOLD

  return (
    <div className="page">
      <PageHeader
        eyebrow="Who works here"
        title="Agents"
        description="Each agent works from its own instructions and can act only in the connectors it has been given."
        action={canCreate ? <Button onClick={() => setCreateOpen(true)}>Add an agent</Button> : undefined}
      />

      {canCreate && <CreateAgentDialog open={createOpen} onClose={() => setCreateOpen(false)} onAdded={setAdded} />}
      {canCreate && <AddedAssistantDialog added={added} onClose={() => setAdded(null)} />}

      <QueryState
        query={query}
        permission="agent:read"
        what="the agents list"
        isEmpty={(data) => data.length === 0}
        empty={
          <div style={{ marginTop: 'var(--space-7)' }}>
            <Card>
              <EmptyState
                icon={<EmptyIcon kind="agent" />}
                title="No agents yet"
                body={
                  canCreate
                    ? 'Add your first agent to give the workforce something to do.'
                    : 'Nobody has added an agent to this workspace yet.'
                }
                action={canCreate ? <Button onClick={() => setCreateOpen(true)}>Add an agent</Button> : undefined}
              />
            </Card>
          </div>
        }
        rows={5}
      >
        {(agents) => {
          // A retired agent takes no work, so it sits in its own folded section, out of the way.
          const working = (searchable ? filter.filtered : agents).filter((agent) => agent.status !== 'retired')
          const retired = (searchable ? filter.filtered : agents).filter((agent) => agent.status === 'retired')
          const shown = working
          // Only the General Employee, which every workspace has from its first visit.
          const onlyFallback = agents.length === 1 && agents[0]?.fallback === true
          return (
            <div style={{ marginTop: 'var(--space-6)' }}>
              {canCreate && onlyFallback && (
                <div style={{ marginBottom: 'var(--space-6)' }}>
                  <SuggestedAssistants onAdded={setAdded} />
                </div>
              )}
              {searchable && (
                <FilterBar
                  searchLabel="Search agents"
                  query={filter.query}
                  onQueryChange={filter.setQuery}
                  placeholder="Name, instructions or connector"
                  shown={shown.length}
                  total={agents.length}
                  active={filter.active}
                  onClear={filter.clear}
                />
              )}
              {shown.length === 0 && retired.length === 0 ? (
                <Card>
                  <FilterEmpty onClear={filter.clear} what="agents" />
                </Card>
              ) : (
                <div
                  style={{
                    display: 'grid',
                    // min() lets a card shrink below 320px on the narrowest phones instead of
                    // pushing the page sideways.
                    gridTemplateColumns: 'repeat(auto-fill, minmax(min(320px, 100%), 1fr))',
                    gap: 'var(--space-5)',
                  }}
                >
                  {shown.map((agent) => (
                    <AgentCard key={agent.id} agent={agent} outcomes={outcomes} />
                  ))}
                </div>
              )}
              {retired.length > 0 && (
                <div style={{ marginTop: 'var(--space-6)' }}>
                  <Collapsible
                    title={`Retired (${retired.length})`}
                    summary="Kept for their history. Open one to restore it."
                    open={retiredOpen}
                    onToggle={setRetiredOpen}
                  >
                    <div
                      style={{
                        display: 'grid',
                        gridTemplateColumns: 'repeat(auto-fill, minmax(min(320px, 100%), 1fr))',
                        gap: 'var(--space-5)',
                      }}
                    >
                      {retired.map((agent) => (
                        <AgentCard key={agent.id} agent={agent} outcomes={outcomes} />
                      ))}
                    </div>
                  </Collapsible>
                </div>
              )}
            </div>
          )
        }}
      </QueryState>
    </div>
  )
}
