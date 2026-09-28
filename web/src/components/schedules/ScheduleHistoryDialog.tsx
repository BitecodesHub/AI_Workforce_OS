import { Dialog, EmptyState, StatusTag, Time } from '../ui'
import { EmptyIcon, QueryState } from '../ui/QueryState'
import { useScheduleRuns } from '../../lib/queries'
import type { Schedule } from '../../lib/queries'

/**
 * Every goal a schedule has fired, newest first: what a schedule has actually done, not only what
 * it is set to do next.
 */
export function ScheduleHistoryDialog({ schedule, onClose }: { schedule: Schedule | null; onClose: () => void }) {
  const runs = useScheduleRuns(schedule?.id ?? '', { enabled: schedule !== null })

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
          query={runs}
          permission="task:read"
          what="this schedule's history"
          rows={3}
          isEmpty={(goals) => goals.length === 0}
          empty={
            <EmptyState
              titleAs="h3"
              icon={<EmptyIcon kind="task" />}
              title="It has not run yet"
              body="Once it fires, on its own timetable or from “Run now”, the goal it starts appears here."
            />
          }
        >
          {(goals) => (
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
          )}
        </QueryState>
      )}
    </Dialog>
  )
}
