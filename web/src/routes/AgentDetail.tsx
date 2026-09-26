import { useState } from 'react'
import { Button, Card, DataTable, EmptyState, Eyebrow, Notice, PageHeader, StatRow, StatTile, StatusTag, Tag, Dialog, Textarea, Input } from '../components/ui'
import type { Column } from '../components/ui'
import { BackLink, EmptyIcon, QueryState } from '../components/ui/QueryState'
import { TaskDialog } from '../components/ui/TaskDialog'
import { useAgent, useUpdateAgent } from '../lib/queries'
import { useToast } from '../lib/toast'
import { can } from '../lib/session'
import type { AgentGrant } from '../lib/queries'

const GRANT_COLUMNS: Column<AgentGrant>[] = [
  { key: 'server', header: 'Server', render: (row) => <span className="mono">{row.server}</span> },
  {
    key: 'tools',
    header: 'Tools granted',
    render: (row) =>
      row.tools.length > 0 ? <span className="muted">{row.tools.join(', ')}</span> : <span className="muted">Every tool</span>,
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
    render: (row) => (
      <Tag tone={row.requireApproval ? 'warning' : 'neutral'}>{row.requireApproval ? 'Required' : 'Not required'}</Tag>
    ),
  },
  {
    key: 'limit',
    header: 'Call limit',
    numeric: true,
    render: (row) => row.maxCallsPerRun ?? 'No limit',
  },
]

const CATEGORY_LABEL: Record<string, string> = {
  operations: 'Operations',
  engineering: 'Engineering',
  growth: 'Growth',
  support: 'Support',
}

function EditInstructionsDialog({
  open,
  onClose,
  agent,
  onSave,
}: {
  open: boolean
  onClose: () => void
  agent: { systemPrompt: string | null; goals: string | null; maxSteps: number | null; sealed: boolean }
  onSave: (input: { systemPrompt: string; goals: string; maxSteps: number }) => void
}) {
  const [systemPrompt, setSystemPrompt] = useState(agent.systemPrompt ?? '')
  const [goals, setGoals] = useState(agent.goals ?? '')
  const [maxSteps, setMaxSteps] = useState(String(agent.maxSteps ?? ''))
  const [isSubmitting, setIsSubmitting] = useState(false)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (isSubmitting) return
    setIsSubmitting(true)
    onSave({ systemPrompt, goals, maxSteps: maxSteps ? parseInt(maxSteps, 10) : 0 })
  }

  if (agent.sealed) {
    return null
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      eyebrow="Edit instructions"
      title="Update agent configuration"
      description="Changes create a new revision. A sealed revision cannot be edited."
    >
      <form onSubmit={handleSubmit}>
        <Textarea
          label="System prompt"
          value={systemPrompt}
          onChange={(e) => setSystemPrompt(e.target.value)}
          placeholder="You are an HR agent that screens applications..."
          rows={8}
          hint="The instructions this agent works from."
          style={{ marginBottom: 'var(--space-4)' }}
        />
        <Textarea
          label="Goals"
          value={goals}
          onChange={(e) => setGoals(e.target.value)}
          placeholder="Screen applications, draft onboarding email and schedule interviews."
          rows={4}
          hint="What this agent is responsible for achieving."
          style={{ marginBottom: 'var(--space-4)' }}
        />
        <Input
          label="Max steps per run"
          type="number"
          value={maxSteps}
          onChange={(e) => setMaxSteps(e.target.value)}
          placeholder="e.g. 20"
          hint="Optional. Leave empty for no limit."
          style={{ marginBottom: 'var(--space-6)' }}
        />
        <div className="dialog-footer" style={{ justifyContent: 'flex-end', gap: 'var(--space-3)' }}>
          <Button variant="outline" type="button" onClick={onClose} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button type="submit" loading={isSubmitting}>
            Save configuration
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

export function AgentDetail({ id }: { id: string }) {
  const query = useAgent(id)
  const updateAgent = useUpdateAgent(id)
  const toast = useToast()
  const [editOpen, setEditOpen] = useState(false)
  const [taskDialogOpen, setTaskDialogOpen] = useState(false)

  const handleSave = async (input: { systemPrompt: string; goals: string; maxSteps: number }) => {
    try {
      await updateAgent.mutateAsync(input)
      toast.success('Configuration saved')
      setEditOpen(false)
      query.refetch()
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to save'
      toast.error(message)
    }
  }

  return (
    <div className="page">
      <BackLink href="/agents" label="Back to agents" />
      <QueryState query={query} permission="agent:read" what="this agent" rows={5}>
        {(agent) => (
          <>
            <PageHeader
              eyebrow={CATEGORY_LABEL[agent.category] ?? agent.category}
              title={agent.name}
              description={agent.goals?.trim() ?? ''}
              action={
                <>
                  <StatusTag status={agent.status} />
                  {!agent.sealed && can('agent:update') && (
                    <Button
                      variant="outline"
                      style={{ marginLeft: 'var(--space-3)' }}
                      onClick={() => setEditOpen(true)}
                    >
                      Edit instructions
                    </Button>
                  )}
                  {can('agent:run') && (
                    <Button
                      style={{ marginLeft: 'var(--space-3)' }}
                      onClick={() => setTaskDialogOpen(true)}
                    >
                      Give it a task
                    </Button>
                  )}
                </>
              }
            />

            <Notice tone={agent.sealed ? 'info' : 'success'}>
              {agent.revision === null
                ? 'This agent has no saved configuration revision.'
                : `Revision ${agent.revision} is ${agent.sealed ? 'sealed because a run used it' : 'current and editable'}.`}
            </Notice>

            <div style={{ marginTop: 'var(--space-6)' }}>
              <StatRow>
                <StatTile label="Category" value={CATEGORY_LABEL[agent.category] ?? agent.category} />
                <StatTile label="Revision" value={agent.revision?.toString() ?? 'None'} />
                <StatTile label="Step limit" value={agent.maxSteps?.toString() ?? 'None'} />
                <StatTile label="Tool grants" value={agent.grants.length.toString()} />
              </StatRow>
            </div>

            <section style={{ marginTop: 'var(--space-6)' }}>
              <Card as="section">
                <Eyebrow>Persona</Eyebrow>
                <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
                  The instructions this agent works from.
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
                      color: 'var(--ink)',
                      lineHeight: 1.7,
                    }}
                  >
                    {agent.systemPrompt}
                  </pre>
                ) : (
                  <p className="muted">No system prompt has been saved for this agent.</p>
                )}
              </Card>
            </section>

            <section style={{ marginTop: 'var(--space-6)' }}>
              <Card as="section">
                <Eyebrow>Goals</Eyebrow>
                {agent.goals?.trim() ? (
                  <p style={{ whiteSpace: 'pre-wrap' }}>{agent.goals}</p>
                ) : (
                  <p className="muted">No goals have been saved for this agent.</p>
                )}
              </Card>
            </section>

            <section style={{ marginTop: 'var(--space-6)' }}>
              <Card as="section">
                <Eyebrow>Tools granted</Eyebrow>
                <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
                  The tools, scopes and approval requirements available to this agent.
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
                    icon={<EmptyIcon kind="agent" />}
                    title="No tools are granted"
                    body="This agent cannot call connected tools until a tool grant is added."
                  />
                )}
              </Card>
            </section>

            <section style={{ marginTop: 'var(--space-6)' }}>
              <Card as="section">
                <Eyebrow>Revisions</Eyebrow>
                {agent.revision !== null ? (
                  <p className="muted">
                    Current revision: <strong>{agent.revision}</strong>{' '}
                    {agent.sealed ? <span className="muted">(sealed)</span> : <span className="muted">(editable)</span>}
                  </p>
                ) : (
                  <p className="muted">No configuration revisions saved yet.</p>
                )}
              </Card>
            </section>

            <EditInstructionsDialog
              open={editOpen}
              onClose={() => setEditOpen(false)}
              agent={agent}
              onSave={handleSave}
            />

            <TaskDialog
              open={taskDialogOpen}
              onClose={() => setTaskDialogOpen(false)}
              agentId={agent.id}
            />
          </>
        )}
      </QueryState>
    </div>
  )
}