// @find: embedding model, embeddings, search by meaning, re-index, reindex, resume reindex, choose embedding, embedding status, embedding models, knowledge settings, PUT /api/knowledge/embedding, Knowledge page
// @what: Reads and changes the workspace embedding model and follows the progress of re-indexing every source.
// @flow: Called by EmbeddingSettingsCard and setupQueries; calls api() against /api/knowledge/embedding and /api/providers/embedding-models.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'

/*
 * The workspace's choice of embedding model, which decides whether documents are searched by
 * meaning as well as by keyword, and the progress of re-indexing every source after it changes.
 */

export type EmbeddingProgressState = 'idle' | 'running' | 'done' | 'failed' | 'interrupted'

export type EmbeddingProgress = {
  state: EmbeddingProgressState
  sourcesTotal: number
  sourcesDone: number
  passagesTotal: number
  passagesDone: number
  /** A plain sentence for the page; absent before the first change. */
  message?: string | null
  startedAt?: string | null
  finishedAt?: string | null
}

export type EmbeddingStatus = {
  provider: string
  model: string
  dimension: number
  searchMode: 'keyword' | 'keyword+meaning'
  progress: EmbeddingProgress
}

export type EmbeddingOption = {
  providerId: string
  providerName: string
  id: string
  displayName: string
  free: boolean
  contextLength: number
  pricePerMTokIn: number | null
  pricingNote: string | null
}

export type EmbeddingCatalogue = { models: EmbeddingOption[]; notes: string[] }

/** The value a picker option carries: provider and model together, as both are needed. */
export const KEYWORD_ONLY_VALUE = 'sandbox'

export function optionValue(providerId: string, modelId: string): string {
  return providerId === KEYWORD_ONLY_VALUE ? KEYWORD_ONLY_VALUE : `${providerId}::${modelId}`
}

export function parseOptionValue(value: string): { providerId: string; modelId: string | null } {
  if (value === KEYWORD_ONLY_VALUE) return { providerId: KEYWORD_ONLY_VALUE, modelId: null }
  const at = value.indexOf('::')
  return at < 0 ? { providerId: value, modelId: null } : { providerId: value.slice(0, at), modelId: value.slice(at + 2) }
}

/** Whether the page should keep asking how re-indexing is going. */
export function isReindexing(status: EmbeddingStatus | undefined): boolean {
  return status?.progress.state === 'running'
}

/** Share of the passages re-embedded so far, from 0 to 1; 1 when there is nothing to do. */
export function progressShare(progress: EmbeddingProgress): number {
  if (progress.passagesTotal <= 0) return progress.state === 'running' ? 0 : 1
  return Math.min(1, Math.max(0, progress.passagesDone / progress.passagesTotal))
}

/** The models grouped by provider, in the order the service sent them (free first). */
export function groupByProvider(models: readonly EmbeddingOption[]): { providerName: string; models: EmbeddingOption[] }[] {
  const groups = new Map<string, EmbeddingOption[]>()
  for (const model of models) {
    const list = groups.get(model.providerName) ?? []
    list.push(model)
    groups.set(model.providerName, list)
  }
  return [...groups.entries()].map(([providerName, list]) => ({ providerName, models: list }))
}

// @find: embedding status, re-index progress; route: GET /api/knowledge/embedding; used by: Knowledge page (EmbeddingSettingsCard), setup checklist
export function useEmbeddingStatus(options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: ['knowledge', 'embedding'],
    enabled: options.enabled ?? true,
    queryFn: () => api<EmbeddingStatus>('/api/knowledge/embedding'),
    // Every two seconds while sources are being re-embedded, so the progress moves on its own.
    refetchInterval: (query) => (isReindexing(query.state.data) ? 2_000 : false),
  })
}

// @find: list embedding models; route: GET /api/providers/embedding-models; used by: Knowledge page (EmbeddingSettingsCard)
/** Embedding models of the providers with a stored key (provider:read). */
export function useEmbeddingModels(enabled: boolean) {
  return useQuery({
    queryKey: ['providers', 'embedding-models'],
    queryFn: () => api<EmbeddingCatalogue>('/api/providers/embedding-models'),
    enabled,
    staleTime: 5 * 60_000,
  })
}

// @find: choose embedding model; route: PUT /api/knowledge/embedding; used by: Knowledge page (EmbeddingSettingsCard)
/** Chooses a model (or keyword search only) and starts re-indexing (knowledge:source_manage). */
export function useChooseEmbedding() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { providerId: string; modelId: string | null }) =>
      api<EmbeddingStatus>('/api/knowledge/embedding', { method: 'PUT', body: input }),
    onSuccess: (status) => {
      client.setQueryData(['knowledge', 'embedding'], status)
      client.invalidateQueries({ queryKey: ['sources'] })
    },
  })
}

// @find: resume re-index; route: POST /api/knowledge/embedding/reindex; used by: Knowledge page (EmbeddingSettingsCard)
/** Runs re-indexing again for the current model, after a failure or an interruption. */
export function useResumeReindex() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api<EmbeddingStatus>('/api/knowledge/embedding/reindex', { method: 'POST' }),
    onSuccess: (status) => client.setQueryData(['knowledge', 'embedding'], status),
  })
}
