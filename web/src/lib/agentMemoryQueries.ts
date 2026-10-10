// @find: agent memory, memories, remember, forget, pin memory, add note, agent documents, agent knowledge, upload agent document, delete agent document, GET /api/memory/agents, Agent detail page, memory card, documents card
// @what: Reads and changes what one agent remembers and the documents it keeps for itself.
// @flow: Called by AgentMemoryCard and AgentDocumentsCard on the Agent detail page; calls api() against /api/memory/agents and /api/knowledge/agents.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { QueryOptions, SourceDocument } from './queries'

/*
 * What one AI employee remembers, and the documents it keeps for itself. Both belong to the one
 * agent: only it recalls the notes and only it searches the documents. Reading takes agent:read and
 * changing takes agent:update. Every key starts with 'agents' so a change refreshes the lot.
 */

export type MemoryKind = 'fact' | 'preference' | 'instruction' | 'note'

/** One note, as GET /api/memory/agents/{id}/memories returns it. */
export type AgentMemory = {
  id: string
  kind: MemoryKind
  content: string
  /** `agent` when the agent kept it while working, `person` when somebody wrote it. */
  source: 'agent' | 'person'
  runId: string | null
  recallCount: number
  lastRecalledAt: string | null
  createdAt: string
  createdBy: string | null
  updatedAt: string
  /** Always read back to the agent, whatever the work is about, and listed first. */
  pinned?: boolean
}

export type AgentMemoryList = { memories: AgentMemory[]; total: number; limit: number }

export const MEMORY_KIND_LABEL: Record<MemoryKind, string> = {
  fact: 'Fact',
  preference: 'Preference',
  instruction: 'Standing instruction',
  note: 'Note',
}

/** Longest a note may be, matching the memory service. */
export const MEMORY_MAX_CHARS = 1_000

/** Who kept a note, in words. */
export function memorySourceLabel(source: AgentMemory['source']): string {
  return source === 'agent' ? 'Kept by the agent' : 'Written by a person'
}

// @find: list agent memories; route: GET /api/memory/agents/{id}/memories; used by: Agent detail page (AgentMemoryCard)
export function useAgentMemories(agentId: string, options: QueryOptions = {}) {
  return useQuery({
    queryKey: ['agents', agentId, 'memories'],
    queryFn: ({ signal }) => api<AgentMemoryList>(`/api/memory/agents/${agentId}/memories`, { signal }),
    enabled: Boolean(agentId) && (options.enabled ?? true),
  })
}

function useRefreshMemories(agentId: string) {
  const client = useQueryClient()
  return () => client.invalidateQueries({ queryKey: ['agents', agentId, 'memories'] })
}

// @find: add memory, remember something; route: POST /api/memory/agents/{id}/memories; used by: Agent detail page (AgentMemoryCard)
export function useAddMemory(agentId: string) {
  const refresh = useRefreshMemories(agentId)
  return useMutation({
    mutationFn: (input: { content: string; kind: MemoryKind }) =>
      api<AgentMemory>(`/api/memory/agents/${agentId}/memories`, { method: 'POST', body: input }),
    onSuccess: refresh,
  })
}

// @find: edit memory; route: PUT /api/memory/agents/{id}/memories/{memoryId}; used by: Agent detail page (AgentMemoryCard)
export function useUpdateMemory(agentId: string) {
  const refresh = useRefreshMemories(agentId)
  return useMutation({
    mutationFn: (input: { id: string; content: string; kind: MemoryKind }) =>
      api<AgentMemory>(`/api/memory/agents/${agentId}/memories/${input.id}`, {
        method: 'PUT',
        body: { content: input.content, kind: input.kind },
      }),
    onSuccess: refresh,
  })
}

// @find: delete memory, forget one; route: DELETE /api/memory/agents/{id}/memories/{memoryId}; used by: Agent detail page (AgentMemoryCard)
export function useDeleteMemory(agentId: string) {
  const refresh = useRefreshMemories(agentId)
  return useMutation({
    mutationFn: (id: string) => api<void>(`/api/memory/agents/${agentId}/memories/${id}`, { method: 'DELETE' }),
    onSuccess: refresh,
  })
}

// @find: pin memory, unpin memory; route: PUT (pin) or DELETE (unpin) /api/memory/agents/{id}/memories/{memoryId}/pin; used by: Agent detail page (AgentMemoryCard)
/** Pins a note so the agent always reads it, or unpins it (agent:update). */
export function usePinMemory(agentId: string) {
  const refresh = useRefreshMemories(agentId)
  return useMutation({
    mutationFn: (input: { id: string; pinned: boolean }) =>
      api<AgentMemory>(`/api/memory/agents/${agentId}/memories/${input.id}/pin`, {
        method: input.pinned ? 'PUT' : 'DELETE',
      }),
    onSuccess: refresh,
  })
}

// @find: forget everything, clear all memories; route: DELETE /api/memory/agents/{id}/memories; used by: Agent detail page (AgentMemoryCard)
/** Forgets every note the agent keeps, pinned ones too (agent:update). Its run history stays. */
export function useForgetAllMemories(agentId: string) {
  const refresh = useRefreshMemories(agentId)
  return useMutation({
    mutationFn: () => api<{ removed: number }>(`/api/memory/agents/${agentId}/memories`, { method: 'DELETE' }),
    onSuccess: refresh,
  })
}

/* ---- The agent's own documents -------------------------------------------------------------------- */

// @find: list agent documents; route: GET /api/knowledge/agents/{id}/documents; used by: Agent detail page (AgentDocumentsCard)
export function useAgentDocuments(agentId: string, options: QueryOptions = {}) {
  return useQuery({
    queryKey: ['agents', agentId, 'documents'],
    queryFn: ({ signal }) => api<SourceDocument[]>(`/api/knowledge/agents/${agentId}/documents`, { signal }),
    enabled: Boolean(agentId) && (options.enabled ?? true),
    // A file is read in the background; while one is still pending the list asks again, so
    // "Being read" turns into a searchable document (or a reason it is not) without a reload.
    refetchInterval: (query) => (query.state.data?.some((document) => document.status === 'pending') ? 3_000 : false),
  })
}

function useRefreshDocuments(agentId: string) {
  const client = useQueryClient()
  return () => client.invalidateQueries({ queryKey: ['agents', agentId, 'documents'] })
}

// @find: upload agent document; route: POST /api/knowledge/agents/{id}/documents; used by: Agent detail page (AgentDocumentsCard)
export function useUploadAgentDocument(agentId: string) {
  const refresh = useRefreshDocuments(agentId)
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return api<{ status: string; detail?: string | null }>(`/api/knowledge/agents/${agentId}/documents`, {
        method: 'POST',
        form,
      })
    },
    onSuccess: refresh,
  })
}

// @find: add agent note, paste text for agent; route: POST /api/knowledge/agents/{id}/notes; used by: Agent detail page (AgentDocumentsCard)
export function useAddAgentNote(agentId: string) {
  const refresh = useRefreshDocuments(agentId)
  return useMutation({
    mutationFn: (input: { title: string; text: string }) =>
      api<{ status: string; detail?: string | null }>(`/api/knowledge/agents/${agentId}/notes`, {
        method: 'POST',
        body: input,
      }),
    onSuccess: refresh,
  })
}

// @find: delete agent document; route: DELETE /api/knowledge/agents/{id}/documents/{docId}; used by: Agent detail page (AgentDocumentsCard)
export function useDeleteAgentDocument(agentId: string) {
  const refresh = useRefreshDocuments(agentId)
  return useMutation({
    mutationFn: (documentId: string) =>
      api<void>(`/api/knowledge/agents/${agentId}/documents/${documentId}`, { method: 'DELETE' }),
    onSuccess: refresh,
  })
}
