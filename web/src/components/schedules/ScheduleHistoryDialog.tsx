import { Button, Dialog, EmptyState, Notice, StatusTag, Time } from '../ui'
import { EmptyIcon, QueryState } from '../ui/QueryState'
import { describeApiError } from '../../lib/api'
import type { Schedule } from '../../lib/queries'
import { flattenRunPages, useScheduleRunPages } from '../../lib/scheduleQueries'

/**
 * Every goal a schedule has fired, newest first: what a schedule has actually done, not only what
 * it is set to do next. One page at a time, since a schedule that fires every few minutes has
 * thousands; older ones load when asked for.
 */
export function ScheduleHistoryDialog({ schedule, onClose }: { schedule: Schedule | null; onClose: () => void }) {
  const runs = useScheduleRunPages(schedule?.id ?? '', { enabled: schedule !== null })
  // A failed older page keeps the runs already shown, with its own notice beneath them, rather
  // than replacing the whole list with an error.
  const olderPageFailed = runs.isFetchNextPageError
  const query = {
    data: runs.data,
    error: olderPageFailed ? null : runs.error,
    isLoading: runs.isLoading,
    refetch: () => runs.refetch(),
  }

  return (
    <Dialog
      open={schedule !== null}
      onClose={onClose}
      eyebrow="Schedule history"
      title={schedule ? `Runs of “${schedule.name}”` : 'Runs'}
      description="Every goal this schedule has started, most recent first."
    >
      {schedule && (
        <QueryState
          query={query}
          permission="task:read"
          what="this schedule's history"
          rows={3}
          isEmpty={(data) => flattenRunPages(data.pages).length === 0}
          empty={
            <EmptyState
              titleAs="h3"
              icon={<EmptyIcon kind="task" />}
              title="It has not run yet"
              body="Once it fires, on its own timetable or from “Run now”, the goal it starts appears here."
            />
          }
        >
          {(data) => {
            const goals = flattenRunPages(data.pages)
            const total = data.pages[data.pages.length - 1]?.total ?? goals.length
            return (
              <div className="stack" style={{ gap: 'var(--space-3)' }}>
                <ul className="stack" style={{ gap: 'var(--space-3)', margin: 0, padding: 0, listStyle: 'none' }}>
                  {goals.map((goal) => (
                    <li key={goal.id}>
                      <a
                        href={`/tasks?goal=${goal.id}`}
                        className="row"
                        style={{
                          justifyContent: 'space-between',
                          gap: 'var(--space-3)',
                          padding: 'var(--space-3) 0',
                          borderBottom: '1px solid var(--line)',
                        }}
                      >
                        <span className="stack" style={{ gap: 'var(--space-1)' }}>
                          <span>{goal.title}</span>
                          <Time iso={goal.completedAt ?? goal.createdAt} className="caption" />
                        </span>
                        <StatusTag kind="goal" status={goal.status} />
                      </a>
                    </li>
                  ))}
                </ul>
                <p className="caption muted" role="status">
                  Showing {goals.length} of {total} {total === 1 ? 'run' : 'runs'}.
                </p>
                {olderPageFailed && (
                  <Notice tone="warning" live>
                    Older runs could not be loaded. {describeApiError(runs.error)}
                  </Notice>
                )}
                {runs.hasNextPage && (
                  <div>
                    <Button
                      variant="outline"
                      className="button-sm"
                      onClick={() => void runs.fetchNextPage()}
                      loading={runs.isFetchingNextPage}
                    >
                      Show older runs
                    </Button>
                  </div>
                )}
              </div>
            )
          }}
        </QueryState>
      )}
    </Dialog>
  )
}
