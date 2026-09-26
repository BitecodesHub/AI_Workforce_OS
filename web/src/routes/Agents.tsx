import { useState } from 'react'
import { Button, Card, EmptyState, Eyebrow, PageHeader, Tag, Dialog, Input, Select, Textarea } from '../components/ui'
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
import { useAgents, useCreateAgent } from '../lib/queries'
import { useToast } from '../lib/toast'
import { useRouter } from '../lib/router'
import { can } from '../lib/session'
import type { TagTone } from '../components/ui'

const CATEGORY_LABEL: Record<string, string> = {
  operations: 'Operations',
  engineering: 'Engineering',
  growth: 'Growth',
  support: 'Support',
}

const CATEGORIES: { value: string; label: string }[] = [
  { value: 'operations', label: 'Operations' },
  { value: 'engineering', label: 'Engineering' },
  { value: 'growth', label: 'Growth' },
  { value: 'support', label: 'Support' },
]

function AgentCard({ agent }: { agent: { id: string; key: string; name: string; category: string; purpose?: string; model?: string; status: string } }) {
  return (
    <Card href={`/agents/${agent.id}`}>
      <Eyebrow>{CATEGORY_LABEL[agent.category] ?? agent.category}</Eyebrow>
      <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-3)', marginBottom: 'var(--space-3)' }}>
        <h2 className="section-heading" style={{ fontSize: '15px' }}>
          {agent.name}
        </h2>
        <Tag tone={(agent.status === 'active' ? 'success' : 'neutral') as TagTone}>
          {agent.status === 'active' ? 'Active' : 'Paused'}
        </Tag>
      </div>
      {agent.purpose && (
        <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
          {agent.purpose}
        </p>
      )}
      {agent.model && (
        <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-3)' }}>
          <span className="mono muted">{agent.model}</span>
        </div>
      )}
    </Card>
  )
}

function CreateAgentDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { navigate } = useRouter()
  const toast = useToast()
  const createAgent = useCreateAgent()
  const [key, setKey] = useState('')
  const [name, setName] = useState('')
  const [category, setCategory] = useState('operations')
  const [systemPrompt, setSystemPrompt] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!key.trim() || !name.trim() || !systemPrompt.trim()) return
    if (isSubmitting) return

    setIsSubmitting(true)

    try {
      const agent = await createAgent.mutateAsync({ key, name, category, systemPrompt })
      toast.success('Agent created')
      onClose()
      navigate(`/agents/${agent.id}`)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to create agent'
      toast.error(message)
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      eyebrow="New agent"
      title="Create an agent"
      description="Each agent has a key, a name, a category and the instructions it works from."
    >
      <form onSubmit={handleSubmit}>
        <Input
          label="Key"
          value={key}
          onChange={(e) => setKey(e.target.value)}
          placeholder="e.g. hr, engineering-manager"
          required
          maxLength={64}
          hint="Lowercase, hyphens only. Used in API calls."
          style={{ marginBottom: 'var(--space-4)' }}
        />
        <Input
          label="Name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="e.g. HR, Engineering Manager"
          required
          maxLength={128}
          style={{ marginBottom: 'var(--space-4)' }}
        />
        <Select
          label="Category"
          value={category}
          onChange={(e) => setCategory(e.target.value)}
          style={{ marginBottom: 'var(--space-4)' }}
        >
          {CATEGORIES.map((c) => (
            <option key={c.value} value={c.value}>
              {c.label}
            </option>
          ))}
        </Select>
        <Textarea
          label="System prompt"
          value={systemPrompt}
          onChange={(e) => setSystemPrompt(e.target.value)}
          placeholder="You are an HR agent that screens applications..."
          required
          rows={8}
          hint="The instructions this agent works from."
          style={{ marginBottom: 'var(--space-6)' }}
        />
        <div className="dialog-footer" style={{ justifyContent: 'flex-end', gap: 'var(--space-3)' }}>
          <Button variant="outline" type="button" onClick={onClose} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button type="submit" loading={isSubmitting} disabled={!key.trim() || !name.trim() || !systemPrompt.trim()}>
            Create agent
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

export function Agents() {
  const query = useAgents()
  const [createOpen, setCreateOpen] = useState(false)
  const canCreate = can('agent:create')

  return (
    <div className="page">
      <PageHeader
        eyebrow="Who works here"
        title="Agents"
        description="Each agent has a defined role, a model routing policy and only the tools it has been granted."
        action={
          canCreate ? (
            <Button onClick={() => setCreateOpen(true)}>
              Add an agent
            </Button>
          ) : undefined
        }
      />

      <CreateAgentDialog open={createOpen} onClose={() => setCreateOpen(false)} />

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
                    ? 'Create your first agent to give the workforce something to do.'
                    : 'Nobody has configured an agent for this workspace yet.'
                }
                action={canCreate ? <Button onClick={() => setCreateOpen(true)}>Add an agent</Button> : undefined}
              />
            </Card>
          </div>
        }
        rows={5}
      >
        {(agents) => (
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))',
              gap: 'var(--space-5)',
              marginTop: 'var(--space-6)',
            }}
          >
            {agents.map((agent) => (
              <AgentCard key={agent.id} agent={agent} />
            ))}
          </div>
        )}
      </QueryState>
    </div>
  )
}