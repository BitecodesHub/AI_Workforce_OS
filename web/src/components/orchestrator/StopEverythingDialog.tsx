// @find: stop everything, stop all work, emergency stop, kill switch, cancel all running queued tasks, pause schedules, confirm dialog, useStopAll, StopEverythingDialog
// @what: The confirmation dialog that stops all running and queued work and optionally every schedule.
// @flow: Opened from the Orchestrator header; calls useStopAll
import { useState } from 'react'
import { ConfirmDialog } from '../ui'
import { describeApiError } from '../../lib/api'
import { plural } from '../../lib/format'
import { useStopAll } from '../../lib/queries'
import type { Board } from '../../lib/queries'
import { can } from '../../lib/session'
import { useToast } from '../../lib/toast'

/*
 * The one button that stops the whole workspace at once (B2.10): every running and queued task,
 * every question and approval waiting on a person, and, on request, every schedule too, so nothing
 * quietly starts new work right after. The preview counts come from the board already on screen;
 * the toast afterwards reports what the server actually did, which can differ from that preview.
 */
// @find: stop everything dialog, stop all work
export function StopEverythingDialog({ open, onClose, board }: { open: boolean; onClose: () => void; board: Board }) {
  const toast = useToast()
  const stopAll = useStopAll()
  const [pauseSchedules, setPauseSchedules] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const running = board.stats.running
  const waitingForPerson = board.stats.waitingApproval + board.stats.waitingInput
  const queued = board.stats.queued
  const approvals = board.approvals.length
  const questions = board.questions.filter((question) => question.status === 'pending').length

  const confirm = async () => {
    setError(null)
    try {
      const result = await stopAll.mutateAsync({ pauseSchedules })
      onClose()
      let message = `Stopped ${plural(result.runsCancelled, 'run', 'runs')} and ${plural(result.tasksCancelled, 'task', 'tasks')}, withdrew ${plural(result.approvalsWithdrawn, 'approval', 'approvals')} and ${plural(result.questionsWithdrawn, 'question', 'questions')}.`
      if (pauseSchedules) message = message.replace(/\.$/, `, and paused ${plural(result.schedulesPaused, 'schedule', 'schedules')}.`)
      if (result.goalsSkipped > 0) message += ` ${plural(result.goalsSkipped, 'goal', 'goals')} had already finished.`
      if (result.runsSkipped > 0) message += ` ${plural(result.runsSkipped, 'run', 'runs')} had already finished.`
      toast.info(message)
    } catch (thrown) {
      setError(describeApiError(thrown))
    }
  }

  return (
    <ConfirmDialog
      open={open}
      onClose={onClose}
      onConfirm={confirm}
      eyebrow="Stop everything"
      title="Stop all agent work?"
      description={`This stops ${plural(running, 'running task', 'running tasks')} and ${plural(waitingForPerson, 'task', 'tasks')} waiting for a person, cancels ${plural(queued, 'queued task', 'queued tasks')}, and withdraws ${plural(approvals, 'approval', 'approvals')} and ${plural(questions, 'question', 'questions')}. This cannot be undone.`}
      confirmLabel="Stop everything"
      tone="danger"
      loading={stopAll.isPending}
      error={error}
    >
      {can('task:create') && (
        <label className="question-option" style={{ marginTop: 'var(--space-3)' }}>
          <input type="checkbox" checked={pauseSchedules} onChange={(event) => setPauseSchedules(event.target.checked)} />
          <span className="question-option-label">Also pause every schedule, so no new work starts on its own</span>
        </label>
      )}
    </ConfirmDialog>
  )
}
