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

export function useAddMemory(agentId: string) {
  const refresh = useRefreshMemories(agentId)
  return useMutation({
    mutationFn: (input: { content: string; kind: MemoryKind }) =>
      api<AgentMemory>(`/api/memory/agents/${agentId}/memories`, { method: 'POST', body: input }),
    onSuccess: refresh,
  })
}

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

export function useDeleteMemory(agentId: string) {
  const refresh = useRefreshMemories(agentId)
  return useMutation({
    mutationFn: (id: string) => api<void>(`/api/memory/agents/${agentId}/memories/${id}`, { method: 'DELETE' }),
    onSuccess: refresh,
  })
}

/* ---- The agent's own documents -------------------------------------------------------------------- */

export function useAgentDocuments(agentId: string, options: QueryOptions = {}) {
  return useQuery({
    queryKey: ['agents', agentId, 'documents'],
    queryFn: ({ signal }) => api<SourceDocument[]>(`/api/knowledge/agents/${agentId}/documents`, { signal }),
    enabled: Boolean(agentId) && (options.enabled ?? true),
  })
}

function useRefreshDocuments(agentId: string) {
  const client = useQueryClient()
  return () => client.invalidateQueries({ queryKey: ['agents', agentId, 'documents'] })
}

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

export function useDeleteAgentDocument(agentId: string) {
  const refresh = useRefreshDocuments(agentId)
  return useMutation({
    mutationFn: (documentId: string) =>
      api<void>(`/api/knowledge/agents/${agentId}/documents/${documentId}`, { method: 'DELETE' }),
    onSuccess: refresh,
  })
}
