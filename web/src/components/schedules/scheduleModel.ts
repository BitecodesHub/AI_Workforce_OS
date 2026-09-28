import type { TagTone } from '../../lib/labels'

/*
 * Pure rules for the Schedules screen: no React, no network, so they can be tested directly.
 *
 * A schedule carries no status column of its own (see lib/labels.ts's scheduleStateLabel) - only
 * `enabled` and, when the platform paused it after repeated failures, `pausedReason`. The table's
 * Status column needs one more state than that label gives it ("Paused after failures", with the
 * reason on hand for a title), so it lives here instead of duplicating scheduleStateLabel.
 */

export type ScheduleStatus = {
  tone: TagTone
  label: string
  /** Why it stopped on its own, shown as the tag's title. Present only when that is the reason. */
  note?: string
}

/** A schedule's status for the table and the dialog: Active, Paused, or Paused after failures. */
export function scheduleStatus(schedule: { enabled: boolean; pausedReason?: string | null }): ScheduleStatus {
  if (schedule.enabled) return { tone: 'success', label: 'Active' }
  const reason = schedule.pausedReason?.trim()
  if (reason) return { tone: 'warning', label: 'Paused after failures', note: reason }
  return { tone: 'neutral', label: 'Paused' }
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
