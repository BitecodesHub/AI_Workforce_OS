// @find: schedule card, scheduled task in chat, recurring task, created schedule, open schedules, Schedules page link
// @what: Card in the thread for a schedule made from chat.
// @flow: Used by MessageItem; links to /schedules.
import { formatDateTimeIn } from '../../lib/format'
import { useState } from 'react'
import { Button, Card, Eyebrow, Notice, Tag } from '../ui'
import { describeApiError } from '../../lib/api'
import { scheduleStateLabel } from '../../lib/labels'
import { readStored, writeStored } from '../../lib/persist'
import { useCreateSchedule, useSchedules } from '../../lib/queries'
import type { ChatMessage } from '../../lib/queries'
import { scheduleState } from '../../lib/schedules'
import { can } from '../../lib/session'

// @find: ScheduleCard, schedule card, schedule card, scheduled task in chat, recurring task, created schedule
/** A schedule read back in plain words, with next runs and a one-click way to save it (D10). */
export function ScheduleCard({ message }: { message: ChatMessage }) {
  const detail = message.detail
  const createSchedule = useCreateSchedule()
  const schedulesQuery = useSchedules()
  const [created, setCreated] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const canCreate = can('task:create')

  const saved = (schedulesQuery.data ?? []).find(
    (schedule) =>
      schedule.agentId === detail.agentId &&
      schedule.instruction === detail.instruction &&
      schedule.cron === (detail.cron ?? null) &&
      schedule.runAt === (detail.runAt ?? null),
  )
  const alreadySaved =
    created ||
    readStored(`chat.schedule.${message.id}`, false, (v): v is boolean => typeof v === 'boolean') ||
    saved !== undefined
  // The saved schedule's state, read by the same rule as the Schedules screen: a one-off that has
  // already run says Done rather than Paused.
  const savedState = saved ? scheduleState(saved) : null
  const savedLabel = savedState ? scheduleStateLabel(savedState === 'active', savedState === 'done') : null

  const nextRuns = detail.nextRuns ?? []

  async function handleCreate() {
    if (!detail.name || !detail.agentId || !detail.instruction || !detail.text) return
    setError(null)
    try {
      await createSchedule.mutateAsync({
        name: detail.name,
        agentId: detail.agentId,
        instruction: detail.instruction,
        text: detail.text,
      })
      setCreated(true)
      writeStored(`chat.schedule.${message.id}`, true)
    } catch (err) {
      setError(describeApiError(err))
    }
  }

  return (
    <Card as="article" className="chat-schedule-card">
      <Eyebrow as="h2">Schedule</Eyebrow>
      <p>{detail.description ?? message.content}</p>
      <dl className="chat-schedule-facts">
        {detail.agentName && (
          <div>
            <dt>Agent</dt>
            <dd>{detail.agentName}</dd>
          </div>
        )}
        {detail.timezone && (
          <div>
            <dt>Timezone</dt>
            <dd>{detail.timezone}</dd>
          </div>
        )}
      </dl>

      {nextRuns.length > 0 && (
        <>
          <p className="caption muted" style={{ marginTop: 'var(--space-4)', marginBottom: 'var(--space-2)' }}>
            Next runs
          </p>
          <ul className="chat-schedule-runs">
            {nextRuns.map((iso) => (
              <li key={iso}>
                <time dateTime={iso}>{formatDateTimeIn(iso, detail.timezone)}</time>
              </li>
            ))}
          </ul>
        </>
      )}

      {error && (
        <Notice tone="warning" live>
          {error}
        </Notice>
      )}

      <div className="row" style={{ gap: 'var(--space-3)', marginTop: 'var(--space-4)', flexWrap: 'wrap' }}>
        {alreadySaved ? (
          <p className="caption row" style={{ gap: 'var(--space-2)', flexWrap: 'wrap' }}>
            <span>Saved as a schedule.</span>
            {savedLabel && <Tag tone={savedLabel.tone}>{savedLabel.label}</Tag>}
            <a className="link" href="/schedules">
              Open Schedules
            </a>
          </p>
        ) : canCreate ? (
          <Button onClick={() => void handleCreate()} loading={createSchedule.isPending}>
            Create schedule
          </Button>
        ) : (
          <p className="caption muted">Creating a schedule needs a role that can create tasks.</p>
        )}
      </div>
    </Card>
  )
}
