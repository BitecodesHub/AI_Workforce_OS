import { useState } from 'react'
import {
  Button,
  CATEGORY_LABEL,
  Card,
  Dialog,
  EmptyState,
  Eyebrow,
  FilterBar,
  FilterEmpty,
  Input,
  PageHeader,
  Select,
  StatusTag,
  Textarea,
} from '../components/ui'
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
import { ApiError, describeApiError } from '../lib/api'
import { sentenceCase } from '../lib/format'
import { serverLabel } from '../lib/labels'
import { useAgents, useCreateAgent, type Agent } from '../lib/queries'
import { useToast } from '../lib/toast'
import { useRouter } from '../lib/router'
import { can } from '../lib/session'
import { useListFilter } from '../lib/useListFilter'

/** A search field is worth its space only once the grid no longer fits on a screen or two. */
const SEARCH_THRESHOLD = 8

/* Limits from AgentController.CreateAgentRequest, so the form refuses what the server would. */
const KEY_MAX = 60
const NAME_MAX = 120
const PROMPT_MAX = 20_000

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
    agent.summary ?? '',
    ...(agent.tools ?? []).map((server) => serverLabel(server)),
  ].join(' ')
}

function AgentCard({ agent }: { agent: Agent }) {
  const tools = agent.tools
  return (
    <Card href={`/agents/${agent.id}`}>
      <Eyebrow>{categoryLabel(agent.category)}</Eyebrow>
      <div
        className="row"
        style={{
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          gap: 'var(--space-3)',
          marginBottom: 'var(--space-3)',
        }}
      >
        <h2 className="section-heading" style={{ fontSize: '15px' }}>
          {agent.name}
        </h2>
        <StatusTag kind="agent" status={agent.status} />
      </div>
      {/* The summary is the first sentence of the agent's own instructions, usually written to the
          agent ("You handle..."), so it is labelled and quoted as an excerpt rather than passed off
          as a description of the agent. */}
      {agent.summary ? (
        <div style={{ marginBottom: 'var(--space-4)' }}>
          <p className="caption">From its instructions</p>
          <p className="muted">“{agent.summary}”</p>
        </div>
      ) : (
        // Only an agent with no saved revision has no instructions. A missing summary on one that
        // has a revision says nothing about its instructions, so nothing is claimed.
        agent.revision == null && (
          <p className="muted" style={{ marginBottom: 'var(--space-4)' }}>
            No instructions have been saved yet.
          </p>
        )
      )}
      {tools != null && (
        <p className="caption">
          {tools.length > 0 ? `Uses ${tools.map((server) => serverLabel(server)).join(', ')}` : 'No tools granted'}
        </p>
      )}
    </Card>
  )
}

function CreateAgentDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const createAgent = useCreateAgent()
  const [error, setError] = useState<string | null>(null)

  const close = () => {
    setError(null)
    createAgent.reset()
    onClose()
  }

  return (
    <Dialog
      open={open}
      onClose={close}
      eyebrow="New agent"
      title="Add an agent"
      description="Name the agent, choose its category and write the instructions it works from."
      dismissible={!createAgent.isPending}
      error={error}
    >
      {/* Mounted only while open, so a cancelled draft does not come back the next time. */}
      {open && <CreateAgentForm createAgent={createAgent} onError={setError} onDone={close} />}
    </Dialog>
  )
}

function CreateAgentForm({
  createAgent,
  onError,
  onDone,
}: {
  createAgent: ReturnType<typeof useCreateAgent>
  onError: (message: string | null) => void
  onDone: () => void
}) {
  const { navigate } = useRouter()
  const toast = useToast()
  const [name, setName] = useState('')
  // Until somebody types a key of their own, it follows the name.
  const [typedKey, setTypedKey] = useState<string | null>(null)
  const [category, setCategory] = useState('operations')
  const [systemPrompt, setSystemPrompt] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  /** A server's complaint about a field goes once the field is changed. */
  const clearError = (field: string) =>
    setFieldErrors((current) => {
      if (!(field in current)) return current
      const rest = { ...current }
      delete rest[field]
      return rest
    })

  const key = typedKey ?? keyFromName(name)
  const keyProblem =
    key && !KEY_PATTERN.test(key) ? 'Use lowercase letters and numbers, with single hyphens between words.' : null
  const saving = createAgent.isPending
  const complete = name.trim() !== '' && key !== '' && systemPrompt.trim() !== '' && !keyProblem

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!complete || saving) return
    setFieldErrors({})
    onError(null)
    try {
      const agent = await createAgent.mutateAsync({ key, name: name.trim(), category, systemPrompt })
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
        <Input
          label="Key"
          value={key}
          onChange={(e) => {
            setTypedKey(e.target.value)
            clearError('key')
          }}
          placeholder="e.g. people-ops"
          required
          maxLength={KEY_MAX}
          autoComplete="off"
          spellCheck={false}
          hint="Filled in from the name. Lowercase letters, numbers and hyphens. It cannot be changed later."
          error={fieldErrors.key ?? keyProblem}
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
      </div>
      <div className="dialog-footer" style={{ justifyContent: 'flex-end', gap: 'var(--space-3)' }}>
        <Button variant="outline" type="button" onClick={onDone} disabled={saving}>
          Cancel
        </Button>
        <Button type="submit" loading={saving} disabled={!complete}>
          Add agent
        </Button>
      </div>
    </form>
  )
}

export function Agents() {
  const query = useAgents()
  const [createOpen, setCreateOpen] = useState(false)
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
        description="Each agent works from its own instructions and can use only the tools it has been granted."
        action={canCreate ? <Button onClick={() => setCreateOpen(true)}>Add an agent</Button> : undefined}
      />

      {canCreate && <CreateAgentDialog open={createOpen} onClose={() => setCreateOpen(false)} />}

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
          const shown = searchable ? filter.filtered : agents
          return (
            <div style={{ marginTop: 'var(--space-6)' }}>
              {searchable && (
                <FilterBar
                  searchLabel="Search agents"
                  query={filter.query}
                  onQueryChange={filter.setQuery}
                  placeholder="Name, instructions or tool"
                  shown={shown.length}
                  total={agents.length}
                  active={filter.active}
                  onClear={filter.clear}
                />
              )}
              {shown.length === 0 ? (
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
                    <AgentCard key={agent.id} agent={agent} />
                  ))}
                </div>
              )}
            </div>
          )
        }}
      </QueryState>
    </div>
  )
}
