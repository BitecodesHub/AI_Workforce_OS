// @find: schedules, scheduled tasks, recurring task, run every day, cron, create schedule, pause schedule, run now, delete schedule, transfer owner, time zone, /schedules, Schedules page
// @what: The Schedules page: create and manage tasks that run automatically on a timetable.
// @flow: Routed from App.tsx at /schedules; uses the schedule queries in lib/queries
import { useState } from 'react'
import type { FormEvent } from 'react'
import {
  Button,
  Card,
  ConfirmDialog,
  DataTable,
  Dialog,
  EmptyState,
  PageHeader,
  Select,
  StatusTag,
  Tag,
  Time,
} from '../components/ui'
import type { Column } from '../components/ui'
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
import { useMemberDirectory } from '../components/orchestrator/useMemberDirectory'
import { ScheduleDialog } from '../components/schedules/ScheduleDialog'
import { ScheduleHistoryDialog } from '../components/schedules/ScheduleHistoryDialog'
import { OWNER_LEFT_REASON, SCHEDULE_EXAMPLES, scheduleStatus } from '../components/schedules/scheduleModel'
import { describeApiError } from '../lib/api'
import {
  useDeleteSchedule,
  useMembers,
  usePauseSchedule,
  useResumeSchedule,
  useRoles,
  useRunScheduleNow,
  useSchedules,
} from '../lib/queries'
import type { Schedule } from '../lib/queries'
import { useTransferScheduleOwner } from '../lib/scheduleQueries'
import {
  canManageSchedule,
  canTransferSchedule,
  isScheduleDone,
  scheduleOwnerLabel,
  rolesThatCanOwnSchedules,
  transferCandidates,
} from '../lib/schedules'
import { can, profile } from '../lib/session'
import { useToast } from '../lib/toast'

/*
 * Work that runs on its own timetable: recurring instructions and one-off runs set up ahead of
 * time, each echoed back in plain English before it is saved so nobody has to guess what a cron
 * string means.
 */

/** One row's actions, kept in its own component so each schedule's pause/resume/run-now mutations
 * are hooks of a stable, single row rather than hooks called a variable number of times in a loop.
 *
 * What is offered follows who may do it: the schedule's owner, or someone who can cancel work,
 * may run, pause, edit and delete it; only someone who can cancel work may hand it to somebody
 * else. A one-off that has already run offers no Pause or Resume - resuming it would only fire it
 * again at once - but can still be run now or given a new time. */
// @find: schedule row actions, pause, resume, run now, delete schedule, edit schedule
function ScheduleRowActions({
  schedule,
  canManage,
  canTransfer,
  onEdit,
  onTransfer,
  onDeleteRequest,
}: {
  schedule: Schedule
  canManage: boolean
  canTransfer: boolean
  onEdit: () => void
  onTransfer: () => void
  onDeleteRequest: () => void
}) {
  const toast = useToast()
  const pause = usePauseSchedule(schedule.id)
  const resume = useResumeSchedule(schedule.id)
  const runNow = useRunScheduleNow(schedule.id)

  const done = isScheduleDone(schedule)
  // A schedule whose owner left can only be resumed once someone has taken it on.
  const ownerLeft = !schedule.enabled && schedule.pausedReason === OWNER_LEFT_REASON
  const showToggle = canManage && !done && !ownerLeft

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

  if (!canManage && !canTransfer) {
    return (
      <span className="caption muted">
        {schedule.createdBy ? 'Only its owner can change it' : 'No owner on record, so only a manager can change it'}
      </span>
    )
  }

  return (
    <div className="row" style={{ gap: 'var(--space-2)', flexWrap: 'wrap' }}>
      {canManage && (
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
      )}
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
      {canManage && (
        <Button variant="outline" className="button-sm" aria-label={`Edit ${schedule.name}`} onClick={onEdit} disabled={busy}>
          Edit
        </Button>
      )}
      {canTransfer && (
        <Button
          variant="outline"
          className="button-sm"
          aria-label={`Transfer ${schedule.name} to another owner`}
          onClick={onTransfer}
          disabled={busy}
        >
          Transfer owner
        </Button>
      )}
      {canManage && (
        <Button variant="danger" className="button-sm" aria-label={`Delete ${schedule.name}`} onClick={onDeleteRequest} disabled={busy}>
          Delete
        </Button>
      )}
    </div>
  )
}

/**
 * Hands a schedule to another member. Later runs start in their name, so the choice is spelled
 * out before it is saved; a schedule paused because its owner left stays paused until resumed.
 */
/** Who a schedule runs as today, in a sentence: the owner label reads 'You' or 'Not recorded'. */
function currentOwnerSentence(ownerName: string): string {
  switch (ownerName) {
    case 'You':
      return 'It runs as you now.'
    case 'Not recorded':
      return 'Nobody is on record as its owner.'
    case 'Former member':
      return 'It runs as a former member now.'
    case 'Someone':
      return 'It runs as another member now.'
    default:
      return `It runs as ${ownerName} now.`
  }
}

// @find: transfer schedule owner dialog, change who owns a schedule
function TransferOwnerDialog({
  schedule,
  ownerName,
  onClose,
}: {
  schedule: Schedule | null
  ownerName: string
  onClose: () => void
}) {
  const toast = useToast()
  const members = useMembers({ enabled: schedule !== null && can('member:read') })
  const transfer = useTransferScheduleOwner(schedule?.id ?? 'unselected')
  const [userId, setUserId] = useState('')
  const [error, setError] = useState<string | null>(null)

  // Each opening starts blank, so a cancelled choice never carries over to the next schedule.
  const [openedFor, setOpenedFor] = useState<string | null>(null)
  if ((schedule?.id ?? null) !== openedFor) {
    setOpenedFor(schedule?.id ?? null)
    setUserId('')
    setError(null)
  }

  const roles = useRoles({ enabled: schedule !== null && can('role:read') })
  const ownerRoles = rolesThatCanOwnSchedules(roles.data)
  const candidates = transferCandidates(members.data ?? [], schedule?.createdBy ?? null, ownerRoles)
  const chosen = candidates.find((member) => member.userId === userId)

  const close = () => {
    if (!transfer.isPending) onClose()
  }

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (!schedule || !chosen) return
    setError(null)
    try {
      await transfer.mutateAsync({ userId: chosen.userId })
      toast.success(`${schedule.name} now runs as ${chosen.displayName}`)
      onClose()
    } catch (err) {
      setError(describeApiError(err))
    }
  }

  return (
    <Dialog
      open={schedule !== null}
      onClose={close}
      dismissible={!transfer.isPending}
      error={error}
      eyebrow="Transfer owner"
      title={schedule ? `Who should “${schedule.name}” run as?` : 'Transfer owner'}
      description={`${currentOwnerSentence(ownerName)} Every later run starts in the new owner’s name, and they can change or delete it.`}
      footer={
        <>
          <Button variant="outline" type="button" onClick={close} disabled={transfer.isPending}>
            Cancel
          </Button>
          <Button type="submit" form="schedule-owner-form" loading={transfer.isPending} disabled={!chosen}>
            Transfer
          </Button>
        </>
      }
    >
      <form id="schedule-owner-form" onSubmit={handleSubmit}>
        <Select
          label="New owner"
          value={userId}
          onChange={(event) => setUserId(event.target.value)}
          required
          disabled={members.isLoading || roles.isLoading}
          data-autofocus
          hint={
            schedule && !schedule.enabled
              ? 'It stays paused until someone resumes it.'
              : 'Only current members who can start work are listed, so viewers are not offered.'
          }
        >
          {(members.isLoading || roles.isLoading) && <option value="">Loading members…</option>}
          {!members.isLoading && !roles.isLoading && (
            <option value="" disabled>
              {candidates.length > 0 ? 'Choose a member' : 'No other members to choose from'}
            </option>
          )}
          {candidates.map((member) => (
            <option key={member.userId} value={member.userId}>
              {member.displayName}
            </option>
          ))}
        </Select>
      </form>
    </Dialog>
  )
}

// @find: empty schedules state, create first schedule
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

// @find: Schedules component, schedules page, create schedule, POST /api/schedules, /schedules
export function Schedules() {
  const canCreate = can('task:create')
  const canTransfer = canTransferSchedule(can)
  const me = profile()?.userId ?? null
  const directory = useMemberDirectory()
  const [dialogTarget, setDialogTarget] = useState<'create' | Schedule | null>(null)
  const [historyFor, setHistoryFor] = useState<Schedule | null>(null)
  const [transferring, setTransferring] = useState<Schedule | null>(null)
  const [deleting, setDeleting] = useState<Schedule | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const toast = useToast()
  const deleteSchedule = useDeleteSchedule()

  const ownerOf = (schedule: Schedule) => scheduleOwnerLabel(schedule.createdBy, directory, me)
  // Every change goes through task:create first; the owner rule then narrows which rows it covers.
  const mayManage = (schedule: Schedule) => canCreate && canManageSchedule(schedule, me, can)

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
    { key: 'owner', header: 'Owner', sortValue: ownerOf, render: (row) => <span>{ownerOf(row)}</span> },
    {
      key: 'when',
      header: 'When',
      // The zone is the schedule's own, saved with it, so a later change to the workspace's zone
      // is not mistaken for when this one fires.
      render: (row) => (
        <span className="muted">
          {row.description}
          {row.timezone && <span className="caption"> ({row.timezone.replace(/_/g, ' ')})</span>}
        </span>
      ),
    },
    {
      key: 'nextRun',
      header: 'Next run',
      sortValue: (row) => (row.enabled ? row.nextRunAt : null),
      render: (row) => {
        if (isScheduleDone(row)) return <span className="muted">Done</span>
        return row.enabled ? <Time iso={row.nextRunAt} /> : <span className="muted">Paused</span>
      },
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

  if (canCreate || canTransfer) {
    columns.push({
      key: 'actions',
      header: 'Actions',
      render: (row) => (
        <ScheduleRowActions
          schedule={row}
          canManage={mayManage(row)}
          canTransfer={canTransfer}
          onEdit={() => setDialogTarget(row)}
          onTransfer={() => setTransferring(row)}
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
        description="Agents can start work on their own: every weekday at nine, once tomorrow afternoon, or every fifteen minutes. Times follow the workspace timezone. Each schedule runs as its owner."
        action={canCreate ? <Button onClick={() => setDialogTarget('create')}>New schedule</Button> : undefined}
      />

      <ScheduleListCard canManage={canCreate} onCreate={() => setDialogTarget('create')} columns={columns} />

      <ScheduleDialog
        open={dialogTarget !== null}
        onClose={() => setDialogTarget(null)}
        schedule={dialogTarget === 'create' ? null : dialogTarget}
      />

      <ScheduleHistoryDialog schedule={historyFor} onClose={() => setHistoryFor(null)} />

      <TransferOwnerDialog
        schedule={transferring}
        ownerName={transferring ? ownerOf(transferring) : ''}
        onClose={() => setTransferring(null)}
      />

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
// @find: schedule list card, next run, last run
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
