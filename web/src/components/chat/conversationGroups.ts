// @find: conversation groups, sidebar grouping, pinned, needs you, today, yesterday, older, search highlight, status text, archive restore
// @what: Pure rules for grouping and searching the sidebar's conversations.
// @flow: Used by ChatSidebar.
import type { Conversation } from '../../lib/queries'

/*
 * How the sidebar's conversation list is grouped and searched (B1.3). Pure and tested apart from
 * the rows and polling that drive it, so a boundary around midnight or a month end is a one-line
 * assertion rather than something only visible by watching the sidebar for a day.
 */

export type ConversationGroup = { key: string; label: string; conversations: Conversation[] }

const DAY_MS = 24 * 60 * 60 * 1000

function startOfDay(date: Date): number {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
}

/** Which bucket a conversation's `updatedAt` falls into, relative to `now`'s calendar day. */
function bucketFor(updatedAt: string, now: Date): string {
  const date = new Date(updatedAt)
  if (Number.isNaN(date.getTime())) return 'older'
  const diffDays = Math.round((startOfDay(now) - startOfDay(date)) / DAY_MS)
  if (diffDays <= 0) return 'today'
  if (diffDays === 1) return 'yesterday'
  if (diffDays <= 7) return 'week'
  return 'older'
}

const DATE_GROUPS: ReadonlyArray<{ key: string; label: string }> = [
  { key: 'today', label: 'Today' },
  { key: 'yesterday', label: 'Yesterday' },
  { key: 'week', label: 'Previous 7 days' },
  { key: 'older', label: 'Older' },
]

// @find: groupConversations, group conversations, conversation groups, sidebar grouping, pinned, needs you
/**
 * Every conversation grouped for the sidebar, in order: Pinned, Needs you, Today, Yesterday,
 * Previous 7 days, then Older. A conversation appears once only - pinned wins over needing the
 * viewer, which wins over its date bucket - and empty groups are left out entirely, so the caller
 * need not hide them itself.
 */
export function groupConversations(
  pinned: readonly Conversation[],
  needsYou: readonly Conversation[],
  rows: readonly Conversation[],
  now: Date,
): ConversationGroup[] {
  const claimed = new Set<string>()
  const groups: ConversationGroup[] = []

  const pinnedRows = pinned.filter((row) => !claimed.has(row.id))
  for (const row of pinnedRows) claimed.add(row.id)
  if (pinnedRows.length > 0) groups.push({ key: 'pinned', label: 'Pinned', conversations: pinnedRows })

  const needsYouRows = needsYou.filter((row) => !claimed.has(row.id))
  for (const row of needsYouRows) claimed.add(row.id)
  if (needsYouRows.length > 0) groups.push({ key: 'needs-you', label: 'Needs you', conversations: needsYouRows })

  const byBucket = new Map<string, Conversation[]>()
  for (const row of rows) {
    if (claimed.has(row.id)) continue
    claimed.add(row.id)
    const bucket = bucketFor(row.updatedAt, now)
    const list = byBucket.get(bucket) ?? []
    list.push(row)
    byBucket.set(bucket, list)
  }

  for (const { key, label } of DATE_GROUPS) {
    const conversations = byBucket.get(key)
    if (conversations && conversations.length > 0) groups.push({ key, label, conversations })
  }

  return groups
}

// @find: conversationStatusText, conversation status text, conversation groups, sidebar grouping, pinned, needs you
/** The status dot's caption, from a conversation's own activity summary. */
export function conversationStatusText(activity: Conversation['activity']): string {
  switch (activity) {
    case 'needs_answer':
      return 'Needs your answer'
    case 'waiting_answer':
      return 'Waiting for an answer'
    case 'needs_approval':
      return 'Needs your approval'
    case 'waiting_approval':
      return 'Waiting for approval'
    case 'working':
      return 'Working'
    default:
      // Nothing is happening: the time alone says enough, and "Idle" read like a fault.
      return ''
  }
}

export type HighlightPart = { text: string; match: boolean }

// @find: highlightParts, highlight parts, conversation groups, sidebar grouping, pinned, needs you
/** Splits `text` around the first case-insensitive occurrence of `q`, for `<mark>`-style highlighting. */
export function highlightParts(text: string, q: string): HighlightPart[] {
  const query = q.trim()
  if (!query) return [{ text, match: false }]
  const index = text.toLowerCase().indexOf(query.toLowerCase())
  if (index === -1) return [{ text, match: false }]
  const parts: HighlightPart[] = []
  if (index > 0) parts.push({ text: text.slice(0, index), match: false })
  parts.push({ text: text.slice(index, index + query.length), match: true })
  if (index + query.length < text.length) parts.push({ text: text.slice(index + query.length), match: false })
  return parts
}

// @find: nextAfterRemoval, next after removal, conversation groups, sidebar grouping, pinned, needs you
/** The row to select after removing `removedId`: the one after it in `order`, or else the one before. */
export function nextAfterRemoval(order: readonly string[], removedId: string): string | null {
  const index = order.indexOf(removedId)
  if (index === -1) return null
  if (index + 1 < order.length) return order[index + 1]!
  if (index > 0) return order[index - 1]!
  return null
}

// @find: undoArchive, undo archive, conversation groups, sidebar grouping, pinned, needs you
/**
 * Puts an archived conversation back as it was. Archiving also unpins it (the server keeps the
 * Pinned group to what is in use), so a conversation that was pinned is pinned again; without
 * this, "Undo" brought it back into the list but quietly out of Pinned.
 */
export async function undoArchive(
  conversation: { id: string; pinned: boolean },
  actions: { unarchive: (id: string) => Promise<unknown>; pin: (id: string) => Promise<unknown> },
): Promise<void> {
  await actions.unarchive(conversation.id)
  if (conversation.pinned) await actions.pin(conversation.id)
}

