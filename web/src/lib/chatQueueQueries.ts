// @find: chat queue, queued message, edit queued, cancel queued, start now, message waiting, one answer at a time, resource in use, DELETE /api/conversations queue, Chat page
// @what: Edit, cancel or start-now actions for messages waiting in a conversation's queue, and the sentence shown when an answer is still in progress.
// @flow: Called by Chat route; calls api() against /api/conversations/{id}/queue.
import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { api, ApiError, describeApiError } from './api'
import { invalidateWork, queuedMessageRow, type ChatMessage, type ConversationDetail, type QueuedMessage } from './queries'

/*
 * The chat queue: one answer at a time per conversation. A message sent while an answer is still
 * being worked on waits in the queue, where the person can change it, cancel it, or (when the work
 * in progress is parked on a decision) start it now, which stops that work.
 */

/** What the server says when another answer is still being worked on; used when it sends no words of its own. */
export const IN_USE_MESSAGE = 'An answer is still being worked on in this conversation. Wait for it to finish or stop it first.'

/**
 * One sentence for a failed chat action. A conflict with work in progress (RESOURCE_IN_USE) shows
 * the server's own plain sentence, never a generic failure.
 */
export function describeChatActionError(error: unknown): string {
  if (error instanceof ApiError && error.status === 409 && error.code.toUpperCase() === 'RESOURCE_IN_USE') {
    return error.message && error.message.trim().length > 0 ? error.message : IN_USE_MESSAGE
  }
  return describeApiError(error)
}

function patchQueue(client: QueryClient, conversationId: string, update: (queue: QueuedMessage[]) => QueuedMessage[]) {
  client.setQueryData<ConversationDetail>(['conversations', conversationId], (detail) =>
    detail ? { ...detail, queued: update(detail.queued ?? []) } : detail,
  )
}

const queuePath = (conversationId: string, queuedId: string) =>
  `/api/conversations/${conversationId}/queue/${encodeURIComponent(queuedId)}`

// @find: edit queued message; route: PATCH /api/conversations/{id}/queue/{queuedId}; used by: Chat page
/** Changes the words of a queued message. Refused (409) once it has started. */
export function useEditQueued(conversationId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { queuedId: string; text: string }) =>
      queuedMessageRow(
        await api<QueuedMessage>(queuePath(conversationId, input.queuedId), { method: 'PATCH', body: { text: input.text } }),
      ),
    onSuccess: (item) => patchQueue(client, conversationId, (queue) => queue.map((row) => (row.id === item.id ? item : row))),
    onError: () => void client.invalidateQueries({ queryKey: ['conversations', conversationId] }),
  })
}

// @find: cancel queued message; route: DELETE /api/conversations/{id}/queue/{queuedId}; used by: Chat page
/** Removes a queued message. One already gone (404) counts as removed. */
export function useCancelQueued(conversationId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { queuedId: string }) => {
      try {
        await api<void>(queuePath(conversationId, input.queuedId), { method: 'DELETE' })
      } catch (error) {
        if (!(error instanceof ApiError && error.isNotFound)) throw error
      }
      return input.queuedId
    },
    onSuccess: (queuedId) => {
      patchQueue(client, conversationId, (queue) => queue.filter((row) => row.id !== queuedId))
      void client.invalidateQueries({ queryKey: ['conversations', conversationId] })
    },
  })
}

// @find: start queued message now, stop current work; route: POST /api/conversations/{id}/queue/{queuedId}/start-now; used by: Chat page
/** Stops the work in progress in this conversation and starts the queued message at once. */
export function useStartQueuedNow(conversationId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { queuedId: string }) => {
      const result = await api<{ messages?: ChatMessage[] }>(`${queuePath(conversationId, input.queuedId)}/start-now`, {
        method: 'POST',
      })
      return { queuedId: input.queuedId, messages: result?.messages ?? [] }
    },
    onSuccess: ({ queuedId }) => {
      patchQueue(client, conversationId, (queue) => queue.filter((row) => row.id !== queuedId))
      void client.invalidateQueries({ queryKey: ['questions'] })
      invalidateWork(client)
    },
  })
}
