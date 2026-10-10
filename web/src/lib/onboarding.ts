// @find: onboarding, getting started guide, guide steps, Command Map guide, hide guide, step done, localStorage progress
// @what: Remembers per-user progress of the getting-started guide on the Command Map in localStorage.
// @flow: Used by the Command Map page guide card
/*
 * Memory for the getting-started guide on the Command Map.
 *
 * Kept in localStorage per user, so progress survives signing out but one person's ticks never
 * appear for another person on the same browser. Every access is wrapped: storage can be blocked
 * or full (private windows, strict browser settings), and a guide that throws takes the Command
 * Map down with it. When storage fails, a step reads as not done and the guide as not hidden,
 * which is the safe way to be wrong.
 *
 * A step's completion should come from real data wherever the platform has it (a run exists, an
 * approval was decided, a document was indexed). markStepDone is for the steps with no server
 * evidence, such as reading a trace or seeing what a role allows, so the checklist never ticks a
 * task the person has not done. Steps a role cannot perform are filtered out with can() by the
 * guide itself.
 */

export type GuideStep =
  | 'meet-agent'
  | 'give-task'
  | 'read-trace'
  | 'review-approvals'
  | 'add-document'
  | 'search-documents'
  | 'connect-model'
  | 'see-role'

const PREFIX = 'aiwos.gs'

const stepKey = (userId: string, step: GuideStep) => `${PREFIX}.${userId}.${step}`
const hiddenKey = (userId: string) => `${PREFIX}.${userId}.hidden`

function read(key: string): boolean {
  try {
    return window.localStorage.getItem(key) === '1'
  } catch {
    return false
  }
}

/** Dispatched on window after any change made in this tab. */
export const GUIDE_CHANGE_EVENT = 'aiwos:guide'

function write(key: string, value: boolean): boolean {
  try {
    if (value) window.localStorage.setItem(key, '1')
    else window.localStorage.removeItem(key)
  } catch {
    return false
  }
  // Tells a guide that is already on screen to re-read, for example when the account menu's
  // "Show getting started" is used while the Command Map is open.
  window.dispatchEvent(new Event(GUIDE_CHANGE_EVENT))
  return true
}

// @find: is step done, guide progress
/** True once the step has been marked done for this user. False when unknown or unreadable. */
export function isStepDone(userId: string, step: GuideStep): boolean {
  if (!userId) return false
  return read(stepKey(userId, step))
}

// @find: mark step done, tick guide step
/** Records the step as done. Returns false when storage refused the write. */
export function markStepDone(userId: string, step: GuideStep): boolean {
  if (!userId) return false
  return write(stepKey(userId, step), true)
}

// @find: guide hidden, dismissed guide
/** True when this user has hidden the guide. False when unknown or unreadable. */
export function isGuideHidden(userId: string): boolean {
  if (!userId) return false
  return read(hiddenKey(userId))
}

// @find: hide guide, dismiss getting started
/** Hides or shows the guide for this user. Returns false when storage refused the write. */
export function setGuideHidden(userId: string, hidden: boolean): boolean {
  if (!userId) return false
  return write(hiddenKey(userId), hidden)
}

/**
 * Calls `listener` whenever the guide's stored state changes, in this tab or another one.
 * Returns the unsubscribe function, so it can be handed to useEffect or useSyncExternalStore.
 */
export function onGuideChange(listener: () => void): () => void {
  const onStorage = (event: StorageEvent) => {
    if (event.key === null || event.key.startsWith(`${PREFIX}.`)) listener()
  }
  window.addEventListener(GUIDE_CHANGE_EVENT, listener)
  window.addEventListener('storage', onStorage)
  return () => {
    window.removeEventListener(GUIDE_CHANGE_EVENT, listener)
    window.removeEventListener('storage', onStorage)
  }
}
