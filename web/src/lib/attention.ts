// @find: attention, needs you, pending approvals, agent questions, notification badge, browser notifications, polling, unseen items, tab title count, decidable approvals, useAttention
// @what: Polls for approvals and agent questions waiting on the signed-in person, drives the badge count and optional browser notifications.
// @flow: Used by the app shell, sidebar badge and tab title; reads /api/approvals and questions via api.ts
import { useEffect, useRef, useSyncExternalStore } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import { readableSummary } from './approvals'
import { setAttentionCount, useRouter } from './router'
import { can, isSignedIn, profile } from './session'

/*
 * Whatever is waiting on this person, wherever they are in the console, and whether or not the tab
 * is in front.
 *
 * Two things wait on a person: an approval they are allowed to decide, and a question an agent
 * asked about work they asked for. Agent work takes minutes, so people switch tabs, and the
 * ordinary queries pause while the tab is hidden. These two small ones do not: they poll every 30
 * seconds in the background, under their own keys, so the count in the tab title ("(2) Approvals")
 * and an opt-in browser notification still arrive for someone who has gone to another window.
 *
 * The count is the same on every screen. The shell calls useAttention once; it must not live in
 * the navigation bar, which is only drawn on some screens' behalf.
 */

/** The poll interval, in the background too. */
export const ATTENTION_POLL_MS = 30_000

/** The fields of an approval this reads (the rest of GET /api/approvals is not needed here). */
export type AttentionApproval = {
  id: string
  tool?: string | null
  summary: string
  /** Absent from a service that predates the field, which reads as "can decide". */
  canDecide?: boolean
}

/** The fields of a question this reads (GET /api/orchestrator/questions). */
export type AttentionQuestion = {
  id: string
  runId: string
  goalTitle?: string | null
  conversationId?: string | null
}

/** One thing waiting, as a notification would tell it. */
export type AttentionItem = {
  /** The approval's or question's own id, which is what "seen before" is judged on. */
  id: string
  kind: 'approval' | 'question'
  title: string
  body: string
  /** Where it is decided or answered. */
  url: string
  /** Notifications with the same tag replace each other, so a repeat never stacks. */
  tag: string
}

// @find: decidable approvals, approvals this person may decide
/** Approvals this person could decide now. One they could not (a four-eyes rule, a role) is not theirs to act on. */
export function decidable(approvals: readonly AttentionApproval[]): AttentionApproval[] {
  return approvals.filter((approval) => approval.canDecide !== false)
}

// @find: attention items, needs you list, waiting on you
/** Everything waiting on this person, approvals first, each with where to go. */
export function attentionItems(
  approvals: readonly AttentionApproval[],
  questions: readonly AttentionQuestion[],
): AttentionItem[] {
  const items: AttentionItem[] = decidable(approvals).map((approval) => ({
    id: approval.id,
    kind: 'approval',
    title: 'An agent needs your approval',
    body: readableSummary(approval),
    url: `/approvals#approval-${approval.id}`,
    tag: `approval-${approval.id}`,
  }))
  for (const question of questions) {
    items.push({
      id: question.id,
      kind: 'question',
      title: 'An agent has a question for you',
      body: question.goalTitle ?? 'Open it to answer.',
      // The conversation that raised it when there is one, otherwise the run that is waiting.
      url: question.conversationId
        ? `/chat?c=${question.conversationId}#question-${question.id}`
        : `/runs/${question.runId}`,
      tag: `question-${question.id}`,
    })
  }
  return items
}

// @find: unseen items, new since last look, notification trigger
/**
 * The items not seen before, and the set of ids to remember afterwards.
 *
 * What was already waiting when the console opened is not news, so the first look (`primed` false)
 * reports nothing and only remembers. After that an id is reported once, however many times it
 * is fetched, and one that is answered and later reappears under a new id is a new item.
 */
export function unseenItems(
  items: readonly AttentionItem[],
  seen: ReadonlySet<string>,
  primed: boolean,
): { fresh: AttentionItem[]; seen: Set<string> } {
  const next = new Set(seen)
  const fresh: AttentionItem[] = []
  for (const item of items) {
    if (!next.has(item.id)) {
      next.add(item.id)
      if (primed) fresh.push(item)
    }
  }
  return { fresh, seen: next }
}

/* ---- The opt-in browser notification ------------------------------------------------------------ */

const PREFERENCE_PREFIX = 'aiwos.notify.'

/** Raised on window when the preference changes in this tab, so the shell and the toggle agree. */
const PREFERENCE_EVENT = 'aiwos:notify'

const preferenceKey = (userId: string) => `${PREFERENCE_PREFIX}${userId}`

/** Whether this browser can show notifications at all. */
export function notificationsSupported(): boolean {
  return typeof Notification !== 'undefined'
}

/** What the browser says about permission: 'granted', 'denied', 'default' (not asked), or 'unsupported'. */
export function notificationPermission(): NotificationPermission | 'unsupported' {
  return notificationsSupported() ? Notification.permission : 'unsupported'
}

/** Whether this person has turned browser notifications on in this browser. False when storage is blocked. */
export function getBrowserNotifications(userId: string): boolean {
  if (!userId) return false
  try {
    return window.localStorage.getItem(preferenceKey(userId)) === '1'
  } catch {
    return false
  }
}

/** Remembers the choice. Returns false when storage refused it, so the caller can say it will not last. */
export function setBrowserNotifications(userId: string, on: boolean): boolean {
  if (!userId) return false
  try {
    if (on) window.localStorage.setItem(preferenceKey(userId), '1')
    else window.localStorage.removeItem(preferenceKey(userId))
  } catch {
    return false
  } finally {
    try {
      window.dispatchEvent(new Event(PREFERENCE_EVENT))
    } catch {
      /* No window to tell. */
    }
  }
  return true
}

/** How turning notifications on ended. */
export type EnableOutcome = 'on' | 'denied' | 'unsupported' | 'not-saved'

// @find: enable browser notifications, desktop alerts, permission prompt
/**
 * Turns browser notifications on, asking the browser for permission only now, because the person
 * just asked for them. A refusal leaves the setting off, and says so, rather than showing a toggle
 * that is on and does nothing.
 */
export async function enableBrowserNotifications(userId: string): Promise<EnableOutcome> {
  if (!notificationsSupported()) return 'unsupported'
  let permission = Notification.permission
  if (permission === 'default') {
    try {
      permission = await Notification.requestPermission()
    } catch {
      return 'denied'
    }
  }
  if (permission !== 'granted') return 'denied'
  return setBrowserNotifications(userId, true) ? 'on' : 'not-saved'
}

function subscribeToPreference(listener: () => void): () => void {
  window.addEventListener(PREFERENCE_EVENT, listener)
  window.addEventListener('storage', listener)
  return () => {
    window.removeEventListener(PREFERENCE_EVENT, listener)
    window.removeEventListener('storage', listener)
  }
}

// @find: browser notifications setting, notifications on or off
/** The person's browser-notification choice, kept in step with changes made in this tab or another. */
export function useBrowserNotifications(userId: string): boolean {
  return useSyncExternalStore(
    subscribeToPreference,
    () => getBrowserNotifications(userId),
    () => false,
  )
}

/** Shows one notification, and takes the person to the item when it is clicked. Never throws. */
function show(item: AttentionItem, open: (url: string) => void) {
  try {
    const notification = new Notification(item.title, { body: item.body, tag: item.tag })
    notification.onclick = () => {
      window.focus()
      open(item.url)
      notification.close()
    }
  } catch {
    /* Some browsers refuse a page-created notification (mobile Chrome); the title count still shows. */
  }
}

/* ---- The hook ------------------------------------------------------------------------------------ */

// @find: use attention, pending count, approvals and questions count, sidebar badge; route: GET /api/approvals; used by: app shell
/**
 * Counts what is waiting on this person, keeps the tab title in step, and fires a browser
 * notification for what is new while the tab is hidden, when the person has opted in. Mounted once,
 * by the signed-in shell.
 */
export function useAttention(): { count: number; approvals: number; questions: number } {
  const { navigate } = useRouter()
  const client = useQueryClient()
  const me = profile()
  const userId = me?.userId ?? ''
  const signedIn = isSignedIn()
  const canApprovals = signedIn && can('approval:read')
  // The questions this person asked for (mine=true), so a manager's count does not grow with every
  // employee's question.
  const canQuestions = signedIn && can('run:read')

  const approvalsQuery = useQuery({
    queryKey: ['attention', 'approvals'],
    queryFn: ({ signal }) => api<AttentionApproval[]>('/api/approvals', { signal }),
    enabled: canApprovals,
    refetchInterval: ATTENTION_POLL_MS,
    // The point of this query: it keeps asking while the tab is in the background.
    refetchIntervalInBackground: true,
  })
  const questionsQuery = useQuery({
    queryKey: ['attention', 'questions'],
    queryFn: ({ signal }) =>
      api<AttentionQuestion[]>('/api/orchestrator/questions?status=pending&mine=true', { signal }),
    enabled: canQuestions,
    refetchInterval: ATTENTION_POLL_MS,
    refetchIntervalInBackground: true,
  })

  // Deciding or answering invalidates the ordinary lists; this brings the count down with them
  // instead of leaving it for the next poll. Only an invalidation counts: the navigation bar's own
  // polls update those lists every half minute, and following them would double the requests.
  useEffect(() => {
    const cache = client.getQueryCache()
    return cache.subscribe((event) => {
      if (event.type !== 'updated' || event.action.type !== 'invalidate') return
      const root = event.query.queryKey[0]
      if (root === 'approvals' || root === 'questions') {
        void client.invalidateQueries({ queryKey: ['attention'] })
      }
    })
  }, [client])

  const approvals = canApprovals ? decidable(approvalsQuery.data ?? []).length : 0
  const questions = canQuestions ? (questionsQuery.data?.length ?? 0) : 0
  const count = approvals + questions

  useEffect(() => {
    setAttentionCount(count)
  }, [count])
  // Leaving the signed-in shell (signing out) takes the count off the public pages' titles.
  useEffect(() => () => setAttentionCount(0), [])

  // What has been seen is kept for as long as the shell is mounted. Reloading starts again, which
  // is right: whatever was waiting then is not news either.
  const seen = useRef<Set<string>>(new Set())
  const primed = useRef(false)
  const notifyOn = useBrowserNotifications(userId)
  const loaded =
    (!canApprovals || approvalsQuery.data !== undefined) && (!canQuestions || questionsQuery.data !== undefined)

  useEffect(() => {
    if (!loaded) return
    const items = attentionItems(canApprovals ? (approvalsQuery.data ?? []) : [], canQuestions ? (questionsQuery.data ?? []) : [])
    const result = unseenItems(items, seen.current, primed.current)
    seen.current = result.seen
    primed.current = true
    // Only for someone who is not looking: with the tab in front, the title and the badge say it.
    if (!notifyOn || notificationPermission() !== 'granted' || !document.hidden) return
    for (const item of result.fresh) show(item, navigate)
  }, [loaded, canApprovals, canQuestions, approvalsQuery.data, questionsQuery.data, notifyOn, navigate])

  return { count, approvals, questions }
}
