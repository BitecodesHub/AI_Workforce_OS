// @find: agent scheduled tasks, schedules on agent page, agent schedule list, schedule run history on agent, pause schedule, run now, edit schedule, delete schedule, view trace, AgentSchedulesCard, Agent detail page
// @what: The "Scheduled tasks" card on an agent's page: its schedules, with actions and a run history under each.
// @flow: Rendered by routes/AgentDetail.tsx; uses useAgentSchedules, useRecentScheduleRuns, ScheduleDialog and ScheduleHistoryDialog
import { useId, useState } from 'react'
import { Button, Card, ConfirmDialog, EmptyState, Eyebrow, StatusTag, Tag, Time } from '../ui'
import { EmptyIcon, QueryState } from '../ui/QueryState'
import { useMemberDirectory } from '../orchestrator/useMemberDirectory'
import { describeApiError } from '../../lib/api'
import { formatCount, formatDateTimeIn } from '../../lib/format'
import {
  useAgentSchedules,
  useDeleteSchedule,
  usePauseSchedule,
  useResumeSchedule,
  useRunScheduleNow,
} from '../../lib/queries'
import type { Schedule } from '../../lib/queries'
import { useRecentScheduleRuns } from '../../lib/scheduleQueries'
import { canManageSchedule, isScheduleDone, scheduleOwnerLabel } from '../../lib/schedules'
import { can, profile } from '../../lib/session'
import { useToast } from '../../lib/toast'
import { ScheduleDialog } from './ScheduleDialog'
import { ScheduleHistoryDialog } from './ScheduleHistoryDialog'
import { OWNER_LEFT_REASON, scheduleStatus } from './scheduleModel'

/*
 * What an agent has been set to do on its own timetable, on the agent's own page. The same rules
 * as the Schedules screen decide who may change a schedule (lib/schedules.ts); the server checks
 * them again on every request.
 */

/** Readable timezone name: underscores replaced by spaces; empty for none. */
const zoneLabel = (timezone: string | null | undefined) => (timezone ? timezone.replace(/_/g, ' ') : '')

/** When a schedule next fires, as its own timezone's clock reads, or why it will not. */
function nextRunText(schedule: Schedule): string {
  if (isScheduleDone(schedule)) return 'Done, nothing left to run'
  if (!schedule.enabled) return 'Paused'
  if (!schedule.nextRunAt) return 'Not set'
  return formatDateTimeIn(schedule.nextRunAt, schedule.timezone)
}

// @find: agent schedule run history panel, run counts, last five runs, view trace link
/**
 * The history inside an open schedule row. Reads useRecentScheduleRuns(schedule.id) through QueryState
 * (permission task:read). Shows the totals (times run, succeeded, failed when counts exist), the last run with its
 * status, up to the last few runs each with a View trace link (or Open in Tasks when no run id exists) and a button
 * that opens the full history dialog (onOpenHistory). Empty state: "It has not run yet".
 */
function ScheduleRunHistory({ schedule, onOpenHistory }: { schedule: Schedule; onOpenHistory: () => void }) {
  const query = useRecentScheduleRuns(schedule.id)
  return (
    <QueryState
      query={query}
      permission="task:read"
      what="this schedule's runs"
      rows={2}
      isEmpty={(page) => page.runs.length === 0 && (page.total ?? 0) === 0}
      empty={<p className="muted caption">It has not run yet. Once it fires, its runs appear here.</p>}
    >
      {(page) => {
        const total = page.counts?.total ?? page.total
        return (
          <div className="agent-schedule-history">
            <dl className="agent-schedule-counts">
              <div>
                <dt>Times run</dt>
                <dd>{formatCount(total)}</dd>
              </div>
              {page.counts && (
                <>
                  <div>
                    <dt>Succeeded</dt>
                    <dd>{formatCount(page.counts.succeeded)}</dd>
                  </div>
                  <div>
                    <dt>Failed</dt>
                    <dd>{formatCount(page.counts.failed)}</dd>
                  </div>
                </>
              )}
              <div>
                <dt>Last run</dt>
                <dd className="row" style={{ gap: 'var(--space-2)', flexWrap: 'wrap' }}>
                  {schedule.lastRunAt ? (
                    <>
                      <span>{formatDateTimeIn(schedule.lastRunAt, schedule.timezone)}</span>
                      {schedule.lastStatus && <StatusTag kind="goal" status={schedule.lastStatus} />}
                    </>
                  ) : (
                    <span className="muted">Never</span>
                  )}
                </dd>
              </div>
            </dl>
            <h4 className="caption muted agent-schedule-subheading">
              {page.runs.length === 1 ? 'Last run' : `Last ${page.runs.length} runs`}
            </h4>
            <ul className="agent-schedule-runs">
              {page.runs.map((run) => (
                <li key={run.id} className="agent-schedule-run">
                  <span className="row" style={{ gap: 'var(--space-2)', minWidth: 0 }}>
                    <StatusTag kind="goal" status={run.status} />
                    <Time iso={run.completedAt ?? run.createdAt} className="caption muted" />
                  </span>
                  {run.runId ? (
                    <a className="link caption" href={`/runs/${run.runId}`} aria-label={`View trace of ${run.title}`}>
                      View trace
                    </a>
                  ) : (
                    <a className="link caption" href={`/tasks?goal=${run.id}`} aria-label={`Open ${run.title} in Tasks`}>
                      Open in Tasks
                    </a>
                  )}
                </li>
              ))}
            </ul>
            <div>
              <Button variant="outline" className="button-sm" onClick={onOpenHistory}>
                Open full history
              </Button>
            </div>
          </div>
        )
      }}
    </QueryState>
  )
}

// @find: one schedule row on agent page, schedule disclosure, schedule actions
/**
 * One schedule as a list item with a disclosure for its history.
 * Shows the name toggle (aria-expanded), a status tag (scheduleStatus), when it fires, the next run in the
 * schedule's own timezone (nextRunText) and the owner. When canManage is true it offers: Pause or Resume
 * (usePauseSchedule and useResumeSchedule; not for a finished schedule or one paused because its owner left
 * the workspace, which must first be taken over on the Schedules page), Run now (useRunScheduleNow), Edit and
 * Delete (both delegated to the card through callbacks). Each request reports through a toast. The history
 * panel is mounted only while open, so the history is requested only when someone looks at it.
 */
function AgentScheduleRow({
  schedule,
  ownerName,
  canManage,
  onEdit,
  onDeleteRequest,
  onOpenHistory,
}: {
  schedule: Schedule
  ownerName: string
  canManage: boolean
  onEdit: () => void
  onDeleteRequest: () => void
  onOpenHistory: () => void
}) {
  const [open, setOpen] = useState(false)
  const panelId = useId()
  const toast = useToast()
  const pause = usePauseSchedule(schedule.id)
  const resume = useResumeSchedule(schedule.id)
  const runNow = useRunScheduleNow(schedule.id)

  const done = isScheduleDone(schedule)
  // A schedule whose owner left can only be resumed once someone has taken it on (Schedules page).
  const ownerLeft = !schedule.enabled && schedule.pausedReason === OWNER_LEFT_REASON
  const showToggle = canManage && !done && !ownerLeft
  const busy = pause.isPending || resume.isPending || runNow.isPending
  const status = scheduleStatus(schedule)

  const handlePauseToggle = async () => {
    try {
      if (schedule.enabled) {
        await pause.mutateAsync()
        toast.success(`${schedule.name} is paused`)
      } else {
        await resume.mutateAsync()
        toast.success(`${schedule.name} is active again`)
      }
    } catch (err) {
      toast.error(describeApiError(err))
    }
  }

  const handleRunNow = async () => {
    try {
      await runNow.mutateAsync()
      toast.success(`${schedule.name} started. It will appear on the board.`)
    } catch (err) {
      toast.error(describeApiError(err))
    }
  }

  return (
    <li className="agent-schedule">
      <div className="agent-schedule-head">
        <h3 className="agent-schedule-title">
          <button
            type="button"
            className="agent-schedule-toggle"
            aria-expanded={open}
            aria-controls={panelId}
            onClick={() => setOpen((value) => !value)}
          >
            <svg
              className="collapsible-chevron"
              data-open={open}
              width="12"
              height="12"
              viewBox="0 0 12 12"
              fill="none"
              aria-hidden="true"
            >
              <path d="M4 2.5 8 6l-4 3.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            <span>{schedule.name}</span>
          </button>
        </h3>
        <Tag tone={status.tone} title={status.note}>
          {status.label}
        </Tag>
      </div>

      <dl className="agent-schedule-meta">
        <div>
          <dt>When</dt>
          <dd>
            {schedule.description}
            {schedule.timezone && <span className="caption muted"> ({zoneLabel(schedule.timezone)})</span>}
          </dd>
        </div>
        <div>
          <dt>Next run</dt>
          <dd>{nextRunText(schedule)}</dd>
        </div>
        <div>
          <dt>Owner</dt>
          <dd>{ownerName}</dd>
        </div>
      </dl>

      {canManage && (
        <div className="row agent-schedule-actions">
          {showToggle && (
            <Button
              variant="outline"
              className="button-sm"
              aria-label={`${schedule.enabled ? 'Pause' : 'Resume'} ${schedule.name}`}
              onClick={() => void handlePauseToggle()}
              loading={pause.isPending || resume.isPending}
              disabled={busy && !(pause.isPending || resume.isPending)}
            >
              {schedule.enabled ? 'Pause' : 'Resume'}
            </Button>
          )}
          <Button
            variant="outline"
            className="button-sm"
            aria-label={`Run ${schedule.name} now`}
            onClick={() => void handleRunNow()}
            loading={runNow.isPending}
            disabled={busy && !runNow.isPending}
          >
            Run now
          </Button>
          <Button variant="outline" className="button-sm" aria-label={`Edit ${schedule.name}`} onClick={onEdit} disabled={busy}>
            Edit
          </Button>
          <Button
            variant="danger"
            className="button-sm"
            aria-label={`Delete ${schedule.name}`}
            onClick={onDeleteRequest}
            disabled={busy}
          >
            Delete
          </Button>
        </div>
      )}

      <div id={panelId} className="agent-schedule-panel" hidden={!open}>
        {/* Mounted only while open, so a schedule's history is asked for only when someone looks. */}
        {open && <ScheduleRunHistory schedule={schedule} onOpenHistory={onOpenHistory} />}
      </div>
    </li>
  )
}

// @find: agent schedules card component, scheduled tasks section
/**
 * "Scheduled tasks" card on an agent's page, rendered by routes/AgentDetail.tsx.
 * Reads useAgentSchedules(agentId) (filtered again client-side by agent id) through QueryState (permission
 * task:read), with owner names from useMemberDirectory. An empty list explains how to create one from the
 * Schedules page or Chat. A row's actions need task:create plus the owner rules in canManageSchedule
 * (lib/schedules.ts); the server checks them again. Edit opens ScheduleDialog (for viewers holding task:create),
 * "Open full history" opens ScheduleHistoryDialog, and Delete opens a ConfirmDialog that calls useDeleteSchedule,
 * with a success toast or an inline error.
 */
export function AgentSchedulesCard({ agentId, agentName }: { agentId: string; agentName: string }) {
  const query = useAgentSchedules(agentId)
  const directory = useMemberDirectory()
  const me = profile()?.userId ?? null
  const canCreate = can('task:create')
  const toast = useToast()
  const deleteSchedule = useDeleteSchedule()
  const [editing, setEditing] = useState<Schedule | null>(null)
  const [historyFor, setHistoryFor] = useState<Schedule | null>(null)
  const [deleting, setDeleting] = useState<Schedule | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  const closeDelete = () => {
    setDeleting(null)
    setDeleteError(null)
  }

  const handleDelete = async () => {
    if (!deleting) return
    setDeleteError(null)
    try {
      await deleteSchedule.mutateAsync(deleting.id)
      toast.success(`${deleting.name} was deleted`)
      closeDelete()
    } catch (err) {
      setDeleteError(describeApiError(err))
    }
  }

  // The server filters by agent; checking again keeps another agent's schedule off this page
  // should a service ever ignore the filter.
  const rows = query.data?.filter((schedule) => schedule.agentId === agentId)

  return (
    <Card as="section">
      <Eyebrow as="h2">Scheduled tasks</Eyebrow>
      <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
        Work this agent starts on its own timetable. Open one to see how its runs have gone.
      </p>
      <QueryState
        query={{ data: rows, error: query.error, isLoading: query.isLoading, refetch: query.refetch }}
        permission="task:read"
        what="this agent's schedules"
        rows={2}
        isEmpty={(list) => list.length === 0}
        empty={
          <EmptyState
            titleAs="h3"
            icon={<EmptyIcon kind="task" />}
            title="Nothing scheduled"
            body={
              <>
                Set one up on the{' '}
                <a className="link" href="/schedules">
                  Schedules
                </a>{' '}
                page, or ask in{' '}
                <a className="link" href="/chat">
                  Chat
                </a>
                , for example <q>@{agentName} every Monday at 9am send the weekly summary</q>.
              </>
            }
          />
        }
      >
        {(list) => (
          <ul className="agent-schedules">
            {list.map((schedule) => (
              <AgentScheduleRow
                key={schedule.id}
                schedule={schedule}
                ownerName={scheduleOwnerLabel(schedule.createdBy, directory, me)}
                // Every change goes through task:create first; the owner rule then narrows it.
                canManage={canCreate && canManageSchedule(schedule, me, can)}
                onEdit={() => setEditing(schedule)}
                onDeleteRequest={() => {
                  setDeleting(schedule)
                  setDeleteError(null)
                }}
                onOpenHistory={() => setHistoryFor(schedule)}
              />
            ))}
          </ul>
        )}
      </QueryState>

      {canCreate && <ScheduleDialog open={editing !== null} onClose={() => setEditing(null)} schedule={editing} />}

      <ScheduleHistoryDialog schedule={historyFor} onClose={() => setHistoryFor(null)} />

      <ConfirmDialog
        open={deleting !== null}
        onClose={closeDelete}
        onConfirm={handleDelete}
        eyebrow="Delete schedule"
        title={`Delete “${deleting?.name ?? ''}”?`}
        description="It stops firing at once. Goals it already started are not affected and stay in Tasks."
        confirmLabel="Delete schedule"
        cancelLabel="Keep it"
        tone="danger"
        loading={deleteSchedule.isPending}
        error={deleteError}
      />
    </Card>
  )
}
