// @find: chat, conversation, earlier messages, show earlier messages, long conversation, message history paging, conversation visibility, private, workspace, add people, participants, share conversation, Chat page
// @what: Keeps earlier pages of a long conversation on screen and changes who can see or join a conversation.
// @flow: Called by Chat route and AddPeopleDialog; calls api() against /api/conversations.
import { useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { ChatMessage, MessagesPage } from './queries'

/*
 * A long conversation, read past its live window.
 *
 * The conversation detail (useConversation in queries.ts) carries only the newest messages, and
 * that window slides forward as messages arrive. This file keeps everything older the thread has
 * already shown or been asked for: messages that slid out of the window stay on screen, and
 * "Show earlier messages" loads the page before the oldest one loaded, whatever that is now. Each
 * page is anchored to the lowest position loaded at the moment it is asked for, never to a fixed
 * starting point, so no gap can open between the earlier pages and the live window.
 */

/** How many messages one "Show earlier messages" page asks for. */
export const EARLIER_PAGE_SIZE = 100

/**
 * The messages that slid out of the live window between two snapshots of it: the oldest ones
 * fall off the front as new ones arrive. Kept by the caller, so nothing on screen disappears.
 */
export function slidOut(previous: readonly ChatMessage[], current: readonly ChatMessage[]): ChatMessage[] {
  if (previous.length === 0 || current.length === 0) return []
  const lowest = lowestPosition(current)!
  const kept = new Set(current.map((message) => message.id))
  return previous.filter((message) => message.position < lowest && !kept.has(message.id))
}

/**
 * Several sources of the same thread merged into one: each message once (by id, the later source
 * winning, so the live window's copy is the one shown), in position order.
 */
export function mergeThread(...sources: ReadonlyArray<readonly ChatMessage[]>): ChatMessage[] {
  const byId = new Map<string, ChatMessage>()
  for (const source of sources) {
    for (const message of source) byId.set(message.id, message)
  }
  return [...byId.values()].sort((a, b) => a.position - b.position)
}

/** The oldest position among `messages`, or null for none. */
export function lowestPosition(messages: readonly ChatMessage[]): number | null {
  if (messages.length === 0) return null
  return messages.reduce((low, message) => Math.min(low, message.position), Number.POSITIVE_INFINITY)
}

/** A message as the platform sends it, with its optional ids made explicit (as queries.ts does). */
function messageRow(message: ChatMessage): ChatMessage {
  return {
    ...message,
    authorId: message.authorId ?? null,
    agentId: message.agentId ?? null,
    goalId: message.goalId ?? null,
    detail: message.detail ?? {},
  }
}

export type EarlierPages = {
  /** The thread as far as it is loaded: earlier pages and slid-out messages, then the live window. */
  messages: ChatMessage[]
  /** Whether the platform holds messages older than the oldest one loaded. */
  hasEarlier: boolean
  /** Whether a page is on its way. */
  loading: boolean
  /**
   * Loads the page before the oldest message loaded. `apply` wraps the state change, so the caller
   * can keep the reader's place while the page goes in above them (useStickToBottom's
   * keepPosition). Resolves to that page, or null when there is nothing earlier to load or the
   * conversation changed meanwhile; rejects with the ApiError when the page could not be loaded.
   */
  loadEarlier: (apply?: (update: () => void) => void) => Promise<MessagesPage | null>
}

type Loaded = { id: string | null; live: readonly ChatMessage[]; older: ChatMessage[]; exhausted: boolean }

const applyNow = (update: () => void) => update()

// @find: show earlier messages, load older messages; route: GET /api/conversations/{id}/messages?before=; used by: Chat page
/**
 * The whole loaded thread of `conversationId`, given its live window (`live`, which must keep its
 * identity between renders until it changes) and whether the platform said there is more before
 * that window (`liveHasEarlier`). Everything resets when the conversation changes.
 */
export function useEarlierPages(
  conversationId: string | null,
  live: readonly ChatMessage[],
  liveHasEarlier: boolean,
): EarlierPages {
  const client = useQueryClient()
  const [loaded, setLoaded] = useState<Loaded>({ id: conversationId, live, older: [], exhausted: false })
  const [loading, setLoading] = useState(false)

  // Adjusted during render (the "previous value" pattern, 0.2): a new conversation starts empty,
  // and a new snapshot of the live window keeps whatever slid out of the front of it.
  let current = loaded
  if (loaded.id !== conversationId) {
    current = { id: conversationId, live, older: [], exhausted: false }
    setLoaded(current)
  } else if (loaded.live !== live) {
    const out = slidOut(loaded.live, live)
    current = { ...loaded, live, older: out.length > 0 ? mergeThread(loaded.older, out) : loaded.older }
    setLoaded(current)
  }

  const older = current.older
  const messages = useMemo(() => (older.length > 0 ? mergeThread(older, live) : [...live]), [older, live])
  // Positions count up from 0, so a thread loaded back to 0 has nothing earlier, page or not.
  const lowest = lowestPosition(messages)
  const hasEarlier = liveHasEarlier && !current.exhausted && lowest !== null && lowest > 0

  // What the next page is anchored to. Read by loadEarlier, which may run several times in a row
  // (a deep link paging back to its message) before React renders the pages already loaded, so it
  // also moves forward on its own as each page arrives.
  const latest = useRef({ id: conversationId, messages, hasEarlier })
  useLayoutEffect(() => {
    latest.current = { id: conversationId, messages, hasEarlier }
  }, [conversationId, messages, hasEarlier])

  async function loadEarlier(apply: (update: () => void) => void = applyNow): Promise<MessagesPage | null> {
    const { id, messages: shown, hasEarlier: more } = latest.current
    const before = lowestPosition(shown)
    if (!id || !more || before === null) return null
    setLoading(true)
    try {
      const page = await client.fetchQuery({
        queryKey: ['conversations', id, 'earlier', before],
        queryFn: async () => {
          const result = await api<MessagesPage>(
            `/api/conversations/${id}/messages?before=${before}&limit=${EARLIER_PAGE_SIZE}`,
          )
          return { hasEarlier: result.hasEarlier, messages: result.messages.map(messageRow) }
        },
        // A page of past messages never changes once written.
        staleTime: Number.POSITIVE_INFINITY,
      })
      if (latest.current.id !== id) return null
      latest.current = {
        id,
        messages: mergeThread(page.messages, latest.current.messages),
        hasEarlier: latest.current.hasEarlier && page.hasEarlier,
      }
      apply(() =>
        setLoaded((state) =>
          state.id !== id
            ? state
            : { ...state, older: mergeThread(state.older, page.messages), exhausted: state.exhausted || !page.hasEarlier },
        ),
      )
      return page
    } finally {
      setLoading(false)
    }
  }

  return { messages, hasEarlier, loading, loadEarlier }
}

/* ---- Who can read a conversation ------------------------------------------------------------------ */

// @find: make conversation private or workspace; route: PUT /api/conversations/{id}/visibility; used by: Chat page
/** Opens a conversation to the whole workspace, or makes it private again. */
export function useSetConversationVisibility() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, visibility }: { id: string; visibility: 'private' | 'workspace' }) =>
      api<unknown>(`/api/conversations/${id}/visibility`, { method: 'PUT', body: { visibility } }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['conversations'] }),
  })
}

// @find: who is in a conversation; route: GET /api/conversations/{id}/participants; used by: Chat page (AddPeopleDialog)
/** The people added to a conversation besides the person who started it. */
export function useConversationPeople(id: string | null, enabled = true) {
  return useQuery({
    queryKey: ['conversations', id, 'participants'],
    queryFn: ({ signal }) => api<{ userIds: string[] }>(`/api/conversations/${id}/participants`, { signal }),
    enabled: Boolean(id) && enabled,
    select: (result) => result.userIds,
  })
}

// @find: add people to conversation; route: POST /api/conversations/{id}/participants; used by: Chat page (AddPeopleDialog)
export function useAddConversationPeople() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, userIds }: { id: string; userIds: string[] }) =>
      api<{ userIds: string[] }>(`/api/conversations/${id}/participants`, { method: 'POST', body: { userIds } }),
    onSuccess: (_result, { id }) => client.invalidateQueries({ queryKey: ['conversations', id, 'participants'] }),
  })
}

// @find: remove person from conversation; route: DELETE /api/conversations/{id}/participants/{userId}; used by: Chat page (AddPeopleDialog)
export function useRemoveConversationPerson() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, userId }: { id: string; userId: string }) =>
      api<void>(`/api/conversations/${id}/participants/${encodeURIComponent(userId)}`, { method: 'DELETE' }),
    onSuccess: (_result, { id }) => client.invalidateQueries({ queryKey: ['conversations', id, 'participants'] }),
  })
}

/** What the header says about who can read a conversation. */
export function visibilityLabel(visibility: 'private' | 'workspace'): string {
  return visibility === 'private' ? 'Only you and people you add' : 'Everyone in this workspace'
}
