import { useState } from 'react'
import { Button, Card, DataTable, EmptyState, Eyebrow, PageHeader, StatusTag, Tag } from '../components/ui'
import type { Column } from '../components/ui'
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
import { TaskDialog } from '../components/ui/TaskDialog'
import { useGoals, useCancelGoal } from '../lib/queries'
import { useToast } from '../lib/toast'
import { useRouter } from '../lib/router'

interface Goal {
  id: string
  title: string
  description: string
  status: string
  createdAt: string
  completedAt: string | null
  tasks: Array<{
    id: string
    agentId: string | null
    title: string
    status: string
    attempt: number
    maxAttempts: number
    result: string | null
    failureReason: string | null
    startedAt: string | null
    completedAt: string | null
    runId: string | null
  }>
}

interface GoalCardProps {
  goal: Goal
  query: ReturnType<typeof useGoals>
}

function GoalCard({ goal, query }: GoalCardProps) {
  const cancelGoal = useCancelGoal(goal.id)
  const toast = useToast()

  return (
    <div style={{ marginTop: 'var(--space-5)' }}>
      <Card as="section" className="goal-card">
        <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-4)', marginBottom: 'var(--space-4)' }}>
          <div>
            <Eyebrow>Tasks for: {goal.title}</Eyebrow>
            <div style={{ marginTop: 'var(--space-2)' }}>
              <StatusTag status={goal.status} />
            </div>
          </div>
          {goal.status !== 'completed' && goal.status !== 'cancelled' && goal.status !== 'failed' && (
            <Button
              variant="outline"
              onClick={async () => {
                try {
                  await cancelGoal.mutateAsync()
                  toast.success('Goal cancelled')
                  query.refetch()
                } catch (error) {
                  const message = error instanceof Error ? error.message : 'Failed to cancel goal'
                  toast.error(message)
                }
              }}
            >
              Cancel goal
            </Button>
          )}
        </div>

        {goal.tasks.length > 0 ? (
          <DataTable
            columns={TASK_COLUMNS}
            rows={goal.tasks}
            getKey={(row) => row.id}
            getRowHref={(row) => (row.runId ? `/runs/${row.runId}` : undefined)}
            caption={`Tasks for goal "${goal.title}".`}
          />
        ) : (
          <EmptyState
            icon={<EmptyIcon kind="task" />}
            title="No tasks yet"
            body="The orchestrator has not decomposed this goal into tasks."
          />
        )}
      </Card>
    </div>
  )
}

const AGENT_CATEGORY: Record<string, 'operations' | 'engineering' | 'growth' | 'support'> = {
  hr: 'operations',
  'engineering-manager': 'engineering',
  research: 'growth',
  support: 'support',
}

function getCategoryForAgent(agentId: string | null): 'operations' | 'engineering' | 'growth' | 'support' {
  if (!agentId) return 'operations'
  return AGENT_CATEGORY[agentId] ?? 'operations'
}

const TASK_COLUMNS: Column<Goal['tasks'][0]>[] = [
  { key: 'title', header: 'Task', render: (row) => row.title },
  {
    key: 'agentId',
    header: 'Agent',
    render: (row) => (
      <Tag tone={getCategoryForAgent(row.agentId)} withDot>
        {row.agentId ?? 'Unassigned'}
      </Tag>
    ),
  },
  {
    key: 'status',
    header: 'Status',
    render: (row) => <StatusTag status={row.status} />,
  },
  {
    key: 'attempt',
    header: 'Attempt',
    render: (row) => <span className="caption">{row.attempt} of {row.maxAttempts}</span>,
  },
  {
    key: 'duration',
    header: 'Duration',
    numeric: true,
    render: (row) => {
      if (!row.startedAt) return '—'
      const end = row.completedAt ? new Date(row.completedAt).getTime() : Date.now()
      const seconds = Math.max(0, Math.round((end - new Date(row.startedAt).getTime()) / 1000))
      if (seconds < 60) return `${seconds}s`
      return `${Math.floor(seconds / 60)}m ${String(seconds % 60).padStart(2, '0')}s`
    },
  },
]

const GOAL_COLUMNS: Column<Goal>[] = [
  { key: 'title', header: 'Goal', render: (row) => <span className="muted">{row.title}</span> },
  {
    key: 'status',
    header: 'Status',
    render: (row) => <StatusTag status={row.status} />,
  },
  {
    key: 'createdAt',
    header: 'Created',
    render: (row) => new Intl.DateTimeFormat('en-AU', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(row.createdAt)),
  },
  {
    key: 'tasks',
    header: 'Tasks',
    numeric: true,
    render: (row) => row.tasks.length.toString(),
  },
]

export function Tasks() {
  const { navigate } = useRouter()
  const query = useGoals()
  const [taskDialogOpen, setTaskDialogOpen] = useState(false)

  return (
    <div className="page">
      <PageHeader
        eyebrow="What the workforce is doing"
        title="Tasks"
        description="A goal is broken into tasks, and a task starts only once the tasks it depends on have finished."
        action={<Button onClick={() => setTaskDialogOpen(true)}>New goal</Button>}
      />

      <TaskDialog open={taskDialogOpen} onClose={() => setTaskDialogOpen(false)} onSuccess={() => navigate('/tasks')} />

      <QueryState
        query={query}
        permission="task:read"
        what="the goals list"
        isEmpty={(data) => data.length === 0}
        empty={
          <div style={{ marginTop: 'var(--space-7)' }}>
            <Card>
              <EmptyState
                icon={<EmptyIcon kind="task" />}
                title="No goals yet"
                body="Create a goal to give the workforce something to work towards."
                action={<Button onClick={() => setTaskDialogOpen(true)}>New goal</Button>}
              />
            </Card>
          </div>
        }
        rows={5}
      >
        {(goals) => (
          <div style={{ marginTop: 'var(--space-6)' }}>
            <Card as="section">
              <Eyebrow>Goals</Eyebrow>
              <DataTable
                columns={GOAL_COLUMNS}
                rows={goals}
                getKey={(row) => row.id}
                caption="Goals created in this workspace, with their task counts and status."
              />
            </Card>

            {goals.map((goal) => (
              <GoalCard key={goal.id} goal={goal} query={query} />
            ))}
          </div>
        )}
      </QueryState>
    </div>
  )
}