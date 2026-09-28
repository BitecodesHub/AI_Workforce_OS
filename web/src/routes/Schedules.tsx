import { useState } from 'react'
import { Button, Card, ConfirmDialog, DataTable, EmptyState, PageHeader, StatusTag, Tag, Time } from '../components/ui'
import type { Column } from '../components/ui'
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
import { ScheduleDialog } from '../components/schedules/ScheduleDialog'
import { ScheduleHistoryDialog } from '../components/schedules/ScheduleHistoryDialog'
import { SCHEDULE_EXAMPLES, scheduleStatus } from '../components/schedules/scheduleModel'
import { describeApiError } from '../lib/api'
import { useDeleteSchedule, usePauseSchedule, useResumeSchedule, useRunScheduleNow, useSchedules } from '../lib/queries'
import type { Schedule } from '../lib/queries'
import { can } from '../lib/session'
import { useToast } from '../lib/toast'

/*
 * Work that runs on its own timetable: recurring instructions and one-off runs set up ahead of
 * time, each echoed back in plain English before it is saved so nobody has to guess what a cron
 * string means.
 */

/** One row's actions, kept in its own component so each schedule's pause/resume/run-now mutations
 * are hooks of a stable, single row rather than hooks called a variable number of times in a loop. */
function ScheduleRowActions({
  schedule,
  onEdit,
  onDeleteRequest,
}: {
  schedule: Schedule
  onEdit: () => void
  onDeleteRequest: () => void
}) {
  const toast = useToast()
  const pause = usePauseSchedule(schedule.id)
  const resume = useResumeSchedule(schedule.id)
  const runNow = useRunScheduleNow(schedule.id)

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

  const busy = pause.isPending || resume.isPending || runNow.isPending

  return (
    <div className="row" style={{ gap: 'var(--space-2)', flexWrap: 'wrap' }}>
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
      <Button variant="outline" className="button-sm" aria-label={`Edit ${schedule.name}`} onClick={onEdit} disabled={busy}>
        Edit
      </Button>
      <Button variant="danger" className="button-sm" aria-label={`Delete ${schedule.name}`} onClick={onDeleteRequest} disabled={busy}>
        Delete
      </Button>
    </div>
  )
}

function EmptySchedules({ canCreate, onCreate }: { canCreate: boolean; onCreate: () => void }) {
  return (
    <Card as="section">
      <EmptyState
        icon={<EmptyIcon kind="task" />}
        title="No schedules yet"
        body={
          <>
            Set one up from a plain-English phrase, such as{' '}
            {SCHEDULE_EXAMPLES.slice(0, 3).map((example, index) => (
              <span key={example}>
                {index > 0 && ', '}
                <q>{example}</q>
              </span>
            ))}
            . You can also say it in{' '}
            <a className="link" href="/chat">
              Chat
            </a>
            .
          </>
        }
        action={canCreate ? <Button onClick={onCreate}>New schedule</Button> : undefined}
      />
    </Card>
  )
}

export function Schedules() {
  const canManage = can('task:create')
  const [dialogTarget, setDialogTarget] = useState<'create' | Schedule | null>(null)
  const [historyFor, setHistoryFor] = useState<Schedule | null>(null)
  const [deleting, setDeleting] = useState<Schedule | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const toast = useToast()
  const deleteSchedule = useDeleteSchedule()

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

  const columns: Column<Schedule>[] = [
    {
      key: 'name',
      header: 'Name',
      sortValue: (row) => row.name,
      render: (row) => (
        <button
          type="button"
          className="link"
          style={{ cursor: 'pointer', textAlign: 'left' }}
          onClick={() => setHistoryFor(row)}
        >
          {row.name}
        </button>
      ),
    },
    { key: 'agent', header: 'Agent', sortValue: (row) => row.agentName, render: (row) => <Tag>{row.agentName}</Tag> },
    { key: 'when', header: 'When', render: (row) => <span className="muted">{row.description}</span> },
    {
      key: 'nextRun',
      header: 'Next run',
      sortValue: (row) => (row.enabled ? row.nextRunAt : null),
      render: (row) => (row.enabled ? <Time iso={row.nextRunAt} /> : <span className="muted">Paused</span>),
    },
    {
      key: 'lastRun',
      header: 'Last run',
      sortValue: (row) => row.lastRunAt,
      render: (row) => {
        if (!row.lastRunAt) return <span className="muted">Never</span>
        const body = (
          <span className="row" style={{ gap: 'var(--space-2)' }}>
            <StatusTag kind="goal" status={row.lastStatus} />
            <Time iso={row.lastRunAt} />
          </span>
        )
        return row.lastGoalId ? (
          <a className="link" href={`/tasks?goal=${row.lastGoalId}`}>
            {body}
          </a>
        ) : (
          body
        )
      },
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => {
        const status = scheduleStatus(row)
        return (
          <Tag tone={status.tone} title={status.note}>
            {status.label}
          </Tag>
        )
      },
    },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: 'Actions',
      render: (row) => (
        <ScheduleRowActions
          schedule={row}
          onEdit={() => setDialogTarget(row)}
          onDeleteRequest={() => {
            setDeleting(row)
            setDeleteError(null)
          }}
        />
      ),
    })
  }

  return (
    <div className="page">
      <PageHeader
        eyebrow="Work on a timetable"
        title="Schedules"
        description="Agents can start work on their own: every weekday at nine, once tomorrow afternoon, or every fifteen minutes. Times follow the workspace timezone."
        action={canManage ? <Button onClick={() => setDialogTarget('create')}>New schedule</Button> : undefined}
      />

      <ScheduleListCard canManage={canManage} onCreate={() => setDialogTarget('create')} columns={columns} />

      <ScheduleDialog
        open={dialogTarget !== null}
        onClose={() => setDialogTarget(null)}
        schedule={dialogTarget === 'create' ? null : dialogTarget}
      />

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
    </div>
  )
}

/** Split out only so the data hook sits beside the table it feeds, rather than at the top of the
 * whole page component. */
function ScheduleListCard({
  canManage,
  onCreate,
  columns,
}: {
  canManage: boolean
  onCreate: () => void
  columns: Column<Schedule>[]
}) {
  const query = useSchedules()
  return (
    <QueryState
      query={query}
      permission="task:read"
      what="the schedules list"
      isEmpty={(rows) => rows.length === 0}
      empty={<EmptySchedules canCreate={canManage} onCreate={onCreate} />}
      rows={4}
    >
      {(rows) => (
        <Card as="section">
          <DataTable
            columns={columns}
            rows={rows}
            getKey={(row) => row.id}
            caption="Every schedule in the workspace, what it does, when it next runs and how its last run went."
          />
        </Card>
      )}
    </QueryState>
  )
}
