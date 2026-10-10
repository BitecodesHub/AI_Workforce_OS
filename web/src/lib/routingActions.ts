// @find: test now, check provider, remove model from agent chain, remove provider from all routing, routing usage, who lists a provider, POST /api/providers/{id}/check, DELETE /api/agents/{id}/model-policy/candidates, GET /api/model-policy/usage, POST /api/model-policy/remove, Model Routing page
// @what: Immediate model routing actions: test a provider, remove a model from one agent, remove from all routing.
// @flow: Used by components/routing/TestNow.tsx, routes/AgentDetail.tsx and routes/ModelRouting.tsx.
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, describeApiError } from './api'
import type { ModelPolicy } from './queries'

/*
 * Actions on model routing that act at once, outside the chain editor's draft:
 *
 * - "Test now" makes one small real call to a provider (or one of its models) with the stored key
 *   and says in words how it went. Nothing is ever set aside after a failure: every run tries every
 *   model again, so this is only a way to find out now rather than on the next run.
 * - Removing one model from an agent's own chain, at any time, also while the agent is working.
 * - Removing a provider or a model from all routing, the workspace's and every agent's, after
 *   asking who lists it.
 */

export type ProviderCheckResult = {
  providerId: string
  modelId: string
  modelName: string
  result: 'ok' | 'rejected' | 'no_credit' | 'not_found' | 'busy' | 'timeout' | 'unreachable' | 'no_key' | 'error'
  message: string
  latencyMs: number | null
  checkedAt: string
}

export type RoutingUsage = { workspace: boolean; agents: Array<{ id: string; name: string }> }

export type RoutingRemoval = {
  workspace: boolean
  agents: Array<{ id: string; name: string; nowEmpty: boolean }>
  warning: string | null
}

const q = encodeURIComponent

// @find: query string naming a provider and model
/** The query string naming a provider, and a model of it when given. Model ids contain "/". */
export function routingTarget(providerId: string, modelId?: string | null): string {
  return `providerId=${q(providerId)}${modelId ? `&modelId=${q(modelId)}` : ''}`
}

// @find: warning sentence from a response
/** The service's `warning` sentence on a response, when it has one. */
export function warningOf(value: unknown): string | null {
  if (typeof value !== 'object' || value === null) return null
  const warning = (value as { warning?: unknown }).warning
  return typeof warning === 'string' && warning.trim() ? warning : null
}

// @find: test latency wording, tested in 840 ms
/** "Tested in 840 ms" or "1.2 s"; nothing when the call never got an answer. */
export function latencyText(latencyMs: number | null | undefined): string | null {
  if (latencyMs == null || !Number.isFinite(latencyMs)) return null
  return latencyMs < 1000 ? `${Math.round(latencyMs)} ms` : `${(latencyMs / 1000).toFixed(1)} s`
}

// @find: failed test request sentence
/** The sentence for a failed test request itself (not a failed model, which is a normal result). */
export function checkFailureText(error: unknown): string {
  if (error instanceof ApiError && error.status === 429) {
    return 'Too many tests in the last minute. Wait a moment, then try again.'
  }
  return describeApiError(error)
}

// @find: test now, check provider key, test model; route: POST /api/providers/{id}/check; used by: Test now button (components/routing/TestNow.tsx), Model Routing page
/** POST /api/providers/{id}/check (provider:manage). Omitting the model tests its cheapest one. */
export function useCheckProvider() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { providerId: string; modelId?: string | null }) =>
      api<ProviderCheckResult>(`/api/providers/${q(input.providerId)}/check`, {
        method: 'POST',
        body: input.modelId ? { modelId: input.modelId } : {},
      }),
    // A check refreshes what the service knows about the key, so the key status may have moved.
    onSuccess: () => void client.invalidateQueries({ queryKey: ['providers'] }),
  })
}

// @find: remove model from agent, agent routing chain; route: DELETE /api/agents/{id}/model-policy/candidates?providerId&modelId; used by: Agent detail page
/** DELETE one model from an agent's own chain. Emptied, the agent follows the workspace default. */
export function useRemoveAgentCandidate(agentId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { providerId: string; modelId: string }) =>
      api<ModelPolicy>(`/api/agents/${q(agentId)}/model-policy/candidates?${routingTarget(input.providerId, input.modelId)}`, {
        method: 'DELETE',
      }),
    onSuccess: (policy) => {
      client.setQueryData(['agents', agentId, 'model-policy'], policy)
      void client.invalidateQueries({ queryKey: ['agents', agentId, 'model-policy'] })
    },
  })
}

// @find: who uses this provider or model, routing usage; route: GET /api/model-policy/usage; used by: Model Routing page remove dialog
/** Who lists a provider, or one of its models: the workspace chain and agents' own chains. */
export function fetchRoutingUsage(providerId: string, modelId?: string | null): Promise<RoutingUsage> {
  return api<RoutingUsage>(`/api/model-policy/usage?${routingTarget(providerId, modelId)}`)
}

// @find: remove provider or model from all routing; route: POST /api/model-policy/remove; used by: Model Routing page
/** POST /api/model-policy/remove: drops a provider, or one model, from all routing. */
export function useRemoveFromAllRouting() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { providerId: string; modelId?: string | null }) =>
      api<RoutingRemoval>('/api/model-policy/remove', {
        method: 'POST',
        body: input.modelId ? { providerId: input.providerId, modelId: input.modelId } : { providerId: input.providerId },
      }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['model-policy'] })
      void client.invalidateQueries({ queryKey: ['agents'] })
    },
  })
}

// @find: join names A, B and C
/** "A, B and C". */
export function joinNames(names: readonly string[]): string {
  if (names.length <= 1) return names[0] ?? ''
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}

// @find: what removing will change, confirm dialog wording; used by: Model Routing page
/** What removing from all routing will change, in plain words, for the confirm dialog. */
export function usageSentence(usage: RoutingUsage, what: string): string {
  const names = usage.agents.map((agent) => agent.name)
  const parts: string[] = []
  if (usage.workspace) parts.push(`The workspace routing lists ${what}, and it will be taken out.`)
  if (names.length > 0) {
    parts.push(
      names.length === 1
        ? `This agent lists ${what} and will stop using it: ${names[0]}.`
        : `These agents list ${what} and will stop using it: ${joinNames(names)}.`,
    )
  }
  if (parts.length === 0) return `No routing lists ${what} right now, so nothing changes.`
  return parts.join(' ')
}
