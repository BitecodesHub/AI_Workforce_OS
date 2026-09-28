/*
 * @mentions in the chat composer.
 *
 * Pure text functions only, so the composer's own key handling can call them on every keystroke
 * without worrying about React state or timing. The rules mirror the backend's MentionParser
 * (case-insensitive, spaces and hyphens ignored) closely enough that what the composer highlights
 * is what the coordinator will actually match, without the two having to share code across the
 * network boundary.
 */

/** The minimal shape a mention needs to match against: an agent's identity, not its whole record. */
export type MentionCandidate = { id: string; name: string; key: string }

export type MentionQuery = { query: string; start: number }

function normalise(text: string): string {
  return text.trim().toLowerCase()
}

/** Case- and hyphen/space-insensitive key, for comparing a typed word against a name or key. */
function foldedKey(text: string): string {
  return normalise(text).replace(/[\s-]+/g, '')
}

/**
 * The @query being typed at the caret, or null when the caret is not inside one.
 *
 * The nearest '@' before the caret starts the query; a space, newline or another '@' between it
 * and the caret ends it, so "email @rese" mid-word offers a query but "@research done, @" does
 * not reopen the first mention.
 */
export function findMentionQuery(text: string, caret: number): MentionQuery | null {
  const before = text.slice(0, Math.max(0, Math.min(caret, text.length)))
  const at = before.lastIndexOf('@')
  if (at === -1) return null
  const between = before.slice(at + 1)
  if (/[\s@]/.test(between)) return null
  return { query: between, start: at }
}

/** How well `query` completes an agent's name or key: exact, prefix, contains, or no match at all. */
function matchScore(agent: MentionCandidate, q: string): number | null {
  if (!q) return 0
  const name = foldedKey(agent.name)
  const key = foldedKey(agent.key)
  if (name === q || key === q) return 3
  if (name.startsWith(q) || key.startsWith(q)) return 2
  if (name.includes(q) || key.includes(q)) return 1
  return null
}

/**
 * Agents whose name or key could complete `query`, best match first: an exact match, then a
 * prefix match, then anywhere in the name, ties broken alphabetically. An empty query returns
 * every agent in that order, so the popover has something to show the moment '@' is typed.
 */
export function matchAgents<T extends MentionCandidate>(query: string, agents: readonly T[]): T[] {
  const q = foldedKey(query)
  const scored = agents
    .map((agent) => {
      const score = matchScore(agent, q)
      return score === null ? null : { agent, score }
    })
    .filter((entry): entry is { agent: T; score: number } => entry !== null)
  return scored
    .sort((a, b) => b.score - a.score || a.agent.name.localeCompare(b.agent.name))
    .map((entry) => entry.agent)
}

/**
 * Replaces the '@query' the caret sits in with a mention of `agent`, by its key (one word, so it
 * round-trips through the backend's own parser unambiguously), followed by a space. Returns the
 * new text and where the caret lands, just after that space.
 */
export function insertMention(
  text: string,
  start: number,
  caret: number,
  agent: MentionCandidate,
): { text: string; caret: number } {
  const before = text.slice(0, start)
  const after = text.slice(caret)
  const inserted = `@${agent.key} `
  return { text: `${before}${inserted}${after}`, caret: before.length + inserted.length }
}

/** Up to three words after an '@', the shape a mention can take (a name written as several words). */
const MENTION_TOKEN = /@([^\s@]+(?:\s+[^\s@]+){0,2})/g

/**
 * Every agent mentioned in `text`, in the order their mention first appears, each id once. Tries
 * the longest run of words after each '@' first, so "@Research Analyst, go" matches a two-word
 * agent name rather than stopping at "Research".
 */
export function extractMentions(text: string, agents: readonly MentionCandidate[]): string[] {
  const ids: string[] = []
  const seen = new Set<string>()
  for (const match of text.matchAll(MENTION_TOKEN)) {
    const words = match[1]!.trim().split(/\s+/)
    for (let take = words.length; take >= 1; take -= 1) {
      const candidate = foldedKey(words.slice(0, take).join(' '))
      const agent = agents.find((a) => foldedKey(a.key) === candidate || foldedKey(a.name) === candidate)
      if (agent) {
        if (!seen.has(agent.id)) {
          seen.add(agent.id)
          ids.push(agent.id)
        }
        break
      }
    }
  }
  return ids
}
