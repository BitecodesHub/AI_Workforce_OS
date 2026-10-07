import { scheduleStateLabel } from '../../lib/labels'
import type { TagTone } from '../../lib/labels'
import { isScheduleDone } from '../../lib/schedules'
import type { ScheduleLike } from '../../lib/schedules'

/*
 * Pure rules for the Schedules screen: no React, no network, so they can be tested directly.
 *
 * A schedule carries no status column of its own (see lib/labels.ts's scheduleStateLabel) - only
 * `enabled`, the derived done state of a one-off that already ran, and, when something other than
 * a person paused it, `pausedReason`. The table's Status column says why it stopped, with the
 * reason on hand for a title, so it lives here on top of scheduleStateLabel.
 */

export type ScheduleStatus = {
  tone: TagTone
  label: string
  /** Why it stopped on its own, shown as the tag's title. Present only when that is the reason. */
  note?: string
}

/** Set by the platform when the person a schedule runs as leaves the workspace (ScheduleService). */
export const OWNER_LEFT_REASON = 'Owner is no longer a member'

/** Set by the platform when Stop everything paused the schedule (ScheduleService). */
export const STOPPED_EVERYTHING_REASON = 'Paused when all agent work was stopped.'

type StatusInput = Partial<Pick<ScheduleLike, 'kind' | 'nextRunAt' | 'completed' | 'state'>> & {
  enabled: boolean
  pausedReason?: string | null
}

/**
 * A schedule's status for the table and the dialog: Active, Done (a one-off that already ran),
 * Paused, Paused, owner left, or Paused after failures.
 */
export function scheduleStatus(schedule: StatusInput): ScheduleStatus {
  const done = isScheduleDone({
    kind: schedule.kind ?? 'recurring',
    enabled: schedule.enabled,
    nextRunAt: schedule.nextRunAt ?? null,
    createdBy: null,
    completed: schedule.completed ?? null,
    state: schedule.state ?? null,
  })
  if (done) return { ...scheduleStateLabel(false, true), note: 'This one-off has already run.' }
  if (schedule.enabled) return scheduleStateLabel(true)
  const reason = schedule.pausedReason?.trim()
  if (reason === OWNER_LEFT_REASON) return { tone: 'warning', label: 'Paused, owner left', note: reason }
  if (reason === STOPPED_EVERYTHING_REASON) return { ...scheduleStateLabel(false), note: reason }
  if (reason) return { tone: 'warning', label: 'Paused after failures', note: reason }
  return scheduleStateLabel(false)
}

/**
 * Plain-English phrases offered as a starting point for the "When" field, both as chips under it
 * and in the empty state before any schedule exists.
 */
export const SCHEDULE_EXAMPLES: readonly string[] = [
  'every weekday at 9am',
  'every monday at 2pm',
  'tomorrow at 3pm',
  'every 30 minutes',
  'on the 1st of every month at 9am',
]

/**
 * The key that decides when the live preview's 300 ms debounce should restart.
 *
 * Typed text is compared on what would actually change the preview - its words, not incidental
 * whitespace or capitalisation - so pausing mid-word, or a trim that undoes itself, does not
 * restart the wait or send a request that would only come back with the same answer.
 */
export function scheduleDebounceKey(text: string): string {
  return text.trim().toLowerCase().replace(/\s+/g, ' ')
}
