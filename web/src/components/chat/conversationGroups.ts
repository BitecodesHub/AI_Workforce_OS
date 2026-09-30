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
  if (diffDays <= 30) return 'month'
  return `month:${date.getFullYear()}-${String(date.getMonth()).padStart(2, '0')}`
}

function monthLabel(bucket: string, now: Date): string {
  const [, key] = bucket.split(':')
  const [year, month] = (key ?? '').split('-').map(Number)
  if (year === undefined || month === undefined) return 'Earlier'
  const date = new Date(year, month, 1)
  const label = date.toLocaleDateString('en-AU', { month: 'long' })
  return date.getFullYear() === now.getFullYear() ? label : `${label} ${date.getFullYear()}`
}

/**
 * Every conversation grouped for the sidebar, in order: Pinned, Needs you, Today, Yesterday,
 * Previous 7 days, Previous 30 days, then one group per month. A conversation appears once only -
 * pinned wins over needing the viewer, which wins over its date bucket - and empty groups are left
 * out entirely, so the caller need not hide them itself.
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
  const monthOrder: string[] = []
  for (const row of rows) {
    if (claimed.has(row.id)) continue
    claimed.add(row.id)
    const bucket = bucketFor(row.updatedAt, now)
    if (!byBucket.has(bucket)) {
      byBucket.set(bucket, [])
      if (bucket.startsWith('month:')) monthOrder.push(bucket)
    }
    byBucket.get(bucket)!.push(row)
  }

  const fixed: Array<{ key: string; label: string }> = [
    { key: 'today', label: 'Today' },
    { key: 'yesterday', label: 'Yesterday' },
    { key: 'week', label: 'Previous 7 days' },
    { key: 'month', label: 'Previous 30 days' },
  ]
  for (const { key, label } of fixed) {
    const conversations = byBucket.get(key)
    if (conversations && conversations.length > 0) groups.push({ key, label, conversations })
  }
  // Newest month first: bucket keys sort lexically in that order (YYYY-MM).
  for (const bucket of [...monthOrder].sort().reverse()) {
    const conversations = byBucket.get(bucket)
    if (conversations && conversations.length > 0) groups.push({ key: bucket, label: monthLabel(bucket, now), conversations })
  }

  return groups
}

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

/** The row to select after removing `removedId`: the one after it in `order`, or else the one before. */
export function nextAfterRemoval(order: readonly string[], removedId: string): string | null {
  const index = order.indexOf(removedId)
  if (index === -1) return null
  if (index + 1 < order.length) return order[index + 1]!
  if (index > 0) return order[index - 1]!
  return null
}
