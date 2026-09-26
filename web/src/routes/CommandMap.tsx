import { useState } from 'react'
import { Card, DataTable, EmptyState, Eyebrow, Notice, PageHeader, StatRow, StatTile, Tag, Button } from '../components/ui'
import { QueryState, EmptyIcon } from '../components/ui/QueryState'
import { TaskDialog } from '../components/ui/TaskDialog'
import { useRuns, useAgentNames } from '../lib/queries'
import { useRouter } from '../lib/router'
import { can } from '../lib/session'

const KIND_TONE = {
  running: 'blue',
  waiting: 'warning',
  completed: 'success',
  failed: 'danger',
} as const

const KIND_LABEL = {
  running: 'Running',
  waiting: 'Waiting for approval',
  completed: 'Completed',
  failed: 'Failed',
} as const

export function CommandMap() {
  const { navigate } = useRouter()
  const runsQuery = useRuns()
  const agents = useAgentNames()
  const [taskDialogOpen, setTaskDialogOpen] = useState(false)
  const canCreate = can('task:create')

  return (
    <div className="page">
      <PageHeader
        eyebrow="Your workforce, in focus"
        title="Command Map"
        description="Every agent, every task in flight, and everything waiting on a decision from you."
        action={canCreate ? <Button onClick={() => setTaskDialogOpen(true)}>New run</Button> : undefined}
      />

      {canCreate && (
        <TaskDialog open={taskDialogOpen} onClose={() => setTaskDialogOpen(false)} onSuccess={(runId) => runId && navigate(`/runs/${runId}`)} />
      )}

      <QueryState
        query={runsQuery}
        permission="run:read"
        what="the runs list"
        isEmpty={(data) => data.length === 0}
        empty={
          <div style={{ marginTop: 'var(--space-7)' }}>
            <Card>
              <EmptyState
                icon={<EmptyIcon kind="task" />}
                title="No runs yet"
                body={
                  canCreate
                    ? 'Start a run from an agent detail page, or create a new run here.'
                    : 'Start a run from an agent detail page.'
                }
                action={canCreate ? <Button onClick={() => setTaskDialogOpen(true)}>New run</Button> : undefined}
              />
            </Card>
          </div>
        }
        rows={4}
      >
        {(runs) => {
          // useAgentNames() already returns the id-keyed map, not a query object - reading
          // `.data` off it (as this did) silently produced `undefined` every time, so the agent
          // column always fell back to showing the raw id instead of a name.
          const agentMap = agents
          return (
            <>
              {/* Disclosed once, at the top, and never repeated beside a figure. */}
              <Notice tone="info">
                No model provider is configured, so every agent is answering on the offline sandbox model.
                Add a provider key in Settings to route this workspace to a live model.
              </Notice>

              <div style={{ marginTop: 'var(--space-6)' }}>
                <StatRow>
                  <StatTile label="Runs total" value={runs.length} />
                  <StatTile label="Running" value={runs.filter((r) => r.status === 'running').length} />
                  <StatTile label="Completed" value={runs.filter((r) => r.status === 'completed').length} />
                  <StatTile label="Failed" value={runs.filter((r) => r.status === 'failed').length} />
                </StatRow>
                <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
                  Source: run records from the orchestrator.
                </p>
              </div>

              <section style={{ marginTop: 'var(--space-7)' }}>
                <Card as="section">
                  <Eyebrow>Live activity</Eyebrow>
                  <DataTable
                    columns={[
                      {
                        key: 'agent',
                        header: 'Agent',
                        render: (run) => {
                          const agent = agentMap[run.agentId]
                          const category = agent?.category ?? 'operations'
                          return (
                            <Tag tone={category as 'operations' | 'engineering' | 'growth' | 'support'} withDot>
                              {agent?.name ?? run.agentId}
                            </Tag>
                          )
                        },
                      },
                      { key: 'trigger', header: 'Trigger', render: (run) => <span className="muted">{run.trigger}</span> },
                      {
                        key: 'status',
                        header: 'Status',
                        render: (run) => <Tag tone={KIND_TONE[run.status as keyof typeof KIND_TONE] ?? 'neutral'}>{KIND_LABEL[run.status as keyof typeof KIND_LABEL] ?? run.status}</Tag>,
                      },
                      { key: 'taskId', header: 'Source', render: (run) => <span className="mono">{run.taskId ? `task:${run.taskId}` : 'direct'}</span> },
                      {
                        key: 'duration',
                        header: 'Duration',
                        numeric: true,
                        render: (run) =>
                          run.completedAt
                            ? `${Math.round((new Date(run.completedAt).getTime() - new Date(run.startedAt).getTime()) / 1000)}s`
                            : `${Math.round((Date.now() - new Date(run.startedAt).getTime()) / 1000)}s`,
                      },
                    ]}
                    rows={runs}
                    getKey={(row) => row.id}
                    getRowHref={(row) => `/runs/${row.id}`}
                    caption="Agent runs in the last hour, from the orchestrator's run records."
                  />
                </Card>
              </section>
            </>
          )
        }}
      </QueryState>
    </div>
  )
}