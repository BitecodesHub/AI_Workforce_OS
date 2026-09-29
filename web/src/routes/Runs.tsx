import { useCallback, useMemo, useState } from 'react'
import {
  Button,
  Card,
  DataTable,
  EmptyState,
  LoadingState,
  Notice,
  PageHeader,
  StatusTag,
  Tag,
  Time,
} from '../components/ui'
import type { Column } from '../components/ui'
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
import { TaskDialog } from '../components/ui/TaskDialog'
// Imported from its own module, not the ../components/ui barrel: this screen is lazy-loaded, and
// the barrel is also part of the main bundle, so going through it created a circular chunk
// dependency (Rollup warned of a "broken execution order").
import { FilterBar, FilterEmpty } from '../components/ui/FilterBar'
import { formatCount, formatDateTime, formatRunElapsed, truncateWords } from '../lib/format'
import { categoryTone, startedByLabel, statusLabel } from '../lib/labels'
import { isRunActive, useAgentNames, useRunList, useTaskIndex, type Run } from '../lib/queries'
import { useRouter } from '../lib/router'
import { can } from '../lib/session'
import { useListFilter } from '../lib/useListFilter'
import { useNow } from '../lib/useNow'

/*
 * Every run, newest first.
 *
 * The Command Map shows what is happening now; this is where an operator finds yesterday's
 * failed run, or an evaluator sees everything one agent has done. The status and agent filters
 * are sent to the server, so "failed" means every failed run in the workspace, not the failures
 * among the rows already loaded. The text search is the one filter that runs in the browser, over
 * the loaded pages, and it says so while older runs remain unloaded.
 *
 * Filters live in the URL (?status=failed&agent=<id>&q=invoice), so a filtered list survives
 * opening a run and coming Back, and can be shared as a link.
 */

const RUN_STATUSES = ['running', 'waiting_approval', 'waiting_input', 'completed', 'failed', 'cancelled', 'abandoned']

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** The first value of a comma-separated URL parameter, as useListFilter writes them. */
function firstValue(raw: string | null): string | null {
  return raw?.split(',')[0]?.trim() || null
}

/** How long a run took, or has taken so far, in milliseconds; for sorting only. */
function elapsedMs(run: Run, now: number): number | null {
  const started = Date.parse(run.startedAt)
  if (Number.isNaN(started)) return null
  if (isRunActive(run)) return now - started
  const completed = run.completedAt ? Date.parse(run.completedAt) : Number.NaN
  return Number.isNaN(completed) ? null : completed - started
}

/*
 * Cells stay on one line: on a phone the table scrolls sideways inside its own region, and a
 * wrapped "Started from" made every row several lines tall.
 */
const ONE_LINE = { whiteSpace: 'nowrap' } as const

/** A running run's clock, ticking each second. Split out so only active rows subscribe to it. */
function LiveDuration({ run }: { run: Run }) {
  const now = useNow(1000)
  return <>{formatRunElapsed(run, now)}</>
}

function RunDuration({ run }: { run: Run }) {
  return <span style={ONE_LINE}>{isRunActive(run) ? <LiveDuration run={run} /> : formatRunElapsed(run)}</span>
}

export function Runs() {
  const { search } = useRouter()
  const agents = useAgentNames()
  const canReadTasks = can('task:read')
  const tasks = useTaskIndex({ enabled: canReadTasks })
  // The dialog creates a goal with one task, which is what task:create allows.
  const canGiveTask = can('task:create')
  const [dialogOpen, setDialogOpen] = useState(false)
  // Only for sorting by duration; each running row keeps its own second-by-second clock.
  const now = useNow(60_000)

  // The server filters, read from the URL. A value the server would refuse (a mistyped status, an
  // id that is not an id) is not sent; the list below then shows nothing matches, with a way out.
  const wantedStatus = firstValue(search.get('status'))
  const wantedAgent = firstValue(search.get('agent'))
  const runs = useRunList({
    status: wantedStatus && RUN_STATUSES.includes(wantedStatus) ? wantedStatus : null,
    agentId: wantedAgent && UUID_PATTERN.test(wantedAgent) ? wantedAgent : null,
  })
  const loaded = useMemo(() => runs.data?.pages.flat(), [runs.data])
  // While a new status or agent loads, the rows from before stay on screen, narrowed by the
  // filter below to the new choice, rather than the table and its count dropping to nothing and
  // the control just pressed disappearing with the bar. Nothing is kept from an empty list, so
  // "No runs yet" never flashes up while a wider filter loads.
  const [lastLoaded, setLastLoaded] = useState(loaded)
  if (loaded && loaded !== lastLoaded) setLastLoaded(loaded)
  const switching = !loaded && runs.isLoading && (lastLoaded?.length ?? 0) > 0
  const rows = loaded ?? (switching ? lastLoaded : undefined)

  const agentName = useCallback(
    (run: Run) => agents[run.agentId]?.name ?? (Object.keys(agents).length > 0 ? 'Unknown agent' : ''),
    [agents],
  )

  /** Where the run came from, in words, with the task's goal to link to when it is known. */
  const startedFrom = useCallback(
    (run: Run): { label: string; goalId?: string; goalTitle?: string } => {
      const entry = run.taskId ? tasks[run.taskId] : undefined
      const label = startedByLabel(run, entry?.task.title)
      return entry ? { label, goalId: entry.goal.id, goalTitle: entry.goal.title } : { label }
    },
    [tasks],
  )

  const searchText = useCallback(
    (run: Run) => [agentName(run), run.id, startedFrom(run).label, run.failureReason ?? ''].join(' '),
    [agentName, startedFrom],
  )
  // The facets echo the server filters, so the selection is kept in the URL and "Clear filters"
  // clears it; every loaded row already matches them.
  const facets = useMemo(
    () => ({ status: (run: Run) => run.status, agent: (run: Run) => run.agentId }),
    [],
  )
  const filter = useListFilter<Run>({ rows, text: searchText, facets })

  const selectedStatus = filter.selected.status?.[0] ?? ''
  const selectedAgent = filter.selected.agent?.[0] ?? ''

  const agentOptions = useMemo(() => {
    const options = Object.values(agents)
      .map((agent) => ({ value: agent.id, label: agent.name }))
      .sort((a, b) => a.label.localeCompare(b.label))
    // An agent in the link that is not in the list still shows as chosen, so the filter is visible.
    if (selectedAgent && !agents[selectedAgent]) options.unshift({ value: selectedAgent, label: 'Unknown agent' })
    return [{ value: '', label: 'All agents' }, ...options]
  }, [agents, selectedAgent])

  const searching = filter.query.trim() !== ''
  const scopeNote = runs.hasNextPage
    ? searching
      ? `The search covers the ${formatCount(filter.total)} runs loaded so far; show older runs to search further back.`
      : 'Older runs are not loaded yet.'
    : undefined

  const columns: Column<Run>[] = [
    {
      key: 'started',
      header: 'Started',
      render: (run) => (
        <span style={ONE_LINE}>
          <Time iso={run.startedAt} />
        </span>
      ),
      sortValue: (run) => Date.parse(run.startedAt),
    },
    {
      key: 'agent',
      header: 'Agent',
      render: (run) => {
        const agent = agents[run.agentId]
        if (!agent) return <span className="muted">{agentName(run) || '—'}</span>
        return (
          <Tag tone={categoryTone(agent.category)} withDot title={agent.name}>
            {agent.name}
          </Tag>
        )
      },
      sortValue: (run) => agentName(run),
    },
    {
      key: 'status',
      header: 'Status',
      render: (run) => <StatusTag kind="run" status={run.status} withDot />,
      sortValue: (run) => statusLabel('run', run.status).label,
    },
    {
      key: 'source',
      header: 'Started from',
      render: (run) => {
        const source = startedFrom(run)
        const text = truncateWords(source.label, 48)
        if (source.goalId && canReadTasks) {
          return (
            <a
              className="link"
              style={ONE_LINE}
              href={`/tasks?goal=${encodeURIComponent(source.goalId)}`}
              title={source.goalTitle ? `${source.label}, in the goal "${source.goalTitle}"` : source.label}
            >
              {text}
            </a>
          )
        }
        return (
          <span className="muted" style={ONE_LINE} title={text === source.label ? undefined : source.label}>
            {text}
          </span>
        )
      },
      sortValue: (run) => startedFrom(run).label,
    },
    {
      key: 'duration',
      header: 'Duration',
      numeric: true,
      render: (run) => <RunDuration run={run} />,
      sortValue: (run) => elapsedMs(run, now),
    },
    {
      key: 'steps',
      header: 'Steps',
      numeric: true,
      render: (run) => formatCount(run.stepCount),
      sortValue: (run) => run.stepCount,
    },
  ]

  return (
    <div className="page">
      <PageHeader
        eyebrow="Everything agents have done"
        title="Runs"
        description="Each run is one agent working through one instruction, newest first. The status and agent filters search every run; the text search looks through the runs loaded below."
      />

      {canGiveTask && <TaskDialog open={dialogOpen} onClose={() => setDialogOpen(false)} />}

      {/* Hidden during the first load, and while there are no runs at all, when a search box
          over nothing would only be noise. Once a filter is set the bar stays while the filtered
          list loads, so the control just pressed keeps focus. */}
      {((rows && rows.length > 0) || filter.active) && (
        <FilterBar
          searchLabel="Search runs"
          query={filter.query}
          onQueryChange={filter.setQuery}
          placeholder="Agent, task or run id"
          facets={[
            {
              param: 'status',
              label: 'Status',
              // One status at a time, because the server filters by one; pressing the chosen one
              // again shows every status.
              options: RUN_STATUSES.map((status) => ({ value: status, label: statusLabel('run', status).label })),
              selected: filter.selected.status ?? [],
              onToggle: (value) => filter.setOnly('status', value === selectedStatus ? null : value),
            },
          ]}
          selects={[
            {
              label: 'Agent',
              value: selectedAgent,
              options: agentOptions,
              onChange: (value) => filter.setOnly('agent', value || null),
            },
          ]}
          shown={filter.filtered.length}
          total={filter.total}
          scopeNote={scopeNote}
          active={filter.active}
          onClear={filter.clear}
        />
      )}

      <QueryState
        query={{
          data: rows,
          // A failure to load an older page keeps the rows already shown; it is reported below.
          error: loaded ? null : runs.error,
          isLoading: runs.isLoading && !switching,
          refetch: runs.refetch,
        }}
        permission="run:read"
        what="the runs list"
        rows={6}
      >
        {(shownRows) =>
          shownRows.length === 0 && !filter.active ? (
            <Card>
              <EmptyState
                icon={<EmptyIcon kind="task" />}
                title="No runs yet"
                body="A run starts when an agent is given an instruction, directly or as a task in a goal. Each one appears here and opens to its full trace."
                action={
                  canGiveTask ? <Button onClick={() => setDialogOpen(true)}>Give an agent a task</Button> : undefined
                }
              />
            </Card>
          ) : filter.filtered.length === 0 && switching ? (
            // None of the rows already shown match the new choice, but the server may still
            // return some: say it is loading rather than that nothing matches.
            <LoadingState rows={6} label="Loading the runs list" />
          ) : filter.filtered.length === 0 ? (
            <Card>
              <FilterEmpty onClear={filter.clear} what="runs" />
            </Card>
          ) : (
            <Card>
              <DataTable
                columns={columns}
                rows={filter.filtered}
                getKey={(run) => run.id}
                getRowHref={(run) => `/runs/${run.id}`}
                getRowLabel={(run) =>
                  `Run by ${agentName(run) || 'an agent'}, ${statusLabel('run', run.status).label.toLowerCase()}, started ${formatDateTime(run.startedAt)}`
                }
                caption="Runs, newest first, from the orchestrator's run records."
              />
            </Card>
          )
        }
      </QueryState>

      {runs.isFetchNextPageError && (
        <div style={{ marginTop: 'var(--space-5)' }}>
          <Notice tone="warning" live>
            Older runs could not be loaded. Try again.
          </Notice>
        </div>
      )}

      {loaded && runs.hasNextPage && (
        <div className="row" style={{ justifyContent: 'center', marginTop: 'var(--space-5)' }}>
          <Button variant="outline" loading={runs.isFetchingNextPage} onClick={() => void runs.fetchNextPage()}>
            Show older runs
          </Button>
        </div>
      )}
    </div>
  )
}
