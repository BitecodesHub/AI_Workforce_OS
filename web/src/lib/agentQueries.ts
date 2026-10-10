// @find: agents, AI employees, agent outcomes, success rate, cost, pause all, resume all, bulk pause, revisions, configuration history, restore revision, model policy, model routing, set model, Agents page, Agent detail page
// @what: Agent page reads and actions beyond the basics: outcomes, configuration revisions and restore, bulk pause and resume, and per-agent model policy.
// @flow: Called by Agents and AgentDetail routes; calls api() against /api/agents and /api/orchestrator/insights/agents.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import { formatCount, formatMoney, plural } from './format'
import type { Agent, AgentStatusAction, ModelPolicy, ModelPolicyInput, QueryOptions } from './queries'

/*
 * What the agent pages read and do beyond the basics in lib/queries.ts: how each agent has done
 * lately, the history of its configuration and putting an old revision back, and its own model
 * routing. The plain agent list, an agent's detail and pausing one stay in lib/queries.ts, where
 * the Orchestrator and Chat read them too.
 *
 * Every key starts with 'agents' (except the outcomes, which come from the insights service), so
 * any change to an agent made anywhere refreshes all of these together.
 */

/* ---- Bulk pause and resume ------------------------------------------------------------------------ */

/**
 * The agents a "Pause all" or "Resume all" acts on: only those not already in the state it asks
 * for, so no request is wasted and the count in the toast is a count of real changes. Resuming
 * takes every paused agent, the General Employee included, because pausing everyone may have
 * paused it too and chat stops answering unmatched requests while it is held. Pausing takes the
 * active ones, leaving the General Employee out only when asked. A retired agent is neither: it
 * cannot be paused or resumed.
 */
export function bulkTargets<T extends Pick<Agent, 'status' | 'fallback'>>(
  agents: readonly T[],
  action: AgentStatusAction,
  includeGeneral: boolean,
): T[] {
  return agents.filter((agent) =>
    action === 'resume' ? agent.status === 'paused' : agent.status === 'active' && (includeGeneral || !agent.fallback),
  )
}

/** The toast after a bulk action: what changed, and what could not. */
export function bulkMessage(action: AgentStatusAction, succeeded: number, failed: number): string {
  const verb = action === 'pause' ? 'Paused' : 'Resumed'
  if (succeeded === 0 && failed === 0) {
    return action === 'pause' ? 'There was no active agent to pause.' : 'There was no paused agent to resume.'
  }
  if (failed === 0) return `${verb} ${plural(succeeded, 'agent', 'agents')}.`
  const done = action === 'pause' ? 'paused' : 'resumed'
  const left = succeeded === 0 ? '' : `${verb} ${plural(succeeded, 'agent', 'agents')}. `
  return `${left}${plural(failed, 'agent', 'agents')} could not be ${done}.`
}

/* ---- Outcomes ---------------------------------------------------------------------------------------- */

/** The window the agent pages read outcomes over (the insights service accepts 7d, 30d and 90d). */
export const OUTCOME_WINDOW = '30d'

/** Fewer finished runs than this say nothing about an agent: 3 of 3 is not "100%". */
export const MIN_FINISHED_RUNS = 5

/**
 * One agent's outcomes over the window, as GET /api/orchestrator/insights/agents returns them
 * (run:read). The service leaves out what it does not know: no success rate below five finished
 * runs, no cost when every run was unpriced.
 */
export type AgentOutcome = {
  agentId: string
  runs: number
  finishedRuns?: number | undefined
  completed: number
  failed: number
  cancelled?: number | undefined
  enoughRuns?: boolean | undefined
  /** Completed over finished, 0 to 1. */
  successRate?: number | null | undefined
  totalCost?: number | null | undefined
  avgCostPerCompleted?: number | null | undefined
  /** Runs on a real model with no catalogue price. */
  unpricedRuns?: number | undefined
  /** Runs only the offline sandbox answered. */
  sandboxRuns?: number | undefined
  /** Runs that cost nothing because every model they used is free in the catalogue. */
  freeRuns?: number | undefined
  rejectedApprovals?: number | undefined
  lastActive?: string | null | undefined
}

type OutcomeResponse = { agents?: AgentOutcome[] | null }

/** A number as the service wrote it, or null for anything else. */
function figure(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

/** One row from the wire, with its numbers as numbers (a decimal may arrive as a string). */
function outcomeRow(row: AgentOutcome): AgentOutcome {
  return {
    ...row,
    runs: figure(row.runs) ?? 0,
    completed: figure(row.completed) ?? 0,
    failed: figure(row.failed) ?? 0,
    finishedRuns: figure(row.finishedRuns) ?? undefined,
    cancelled: figure(row.cancelled) ?? undefined,
    successRate: figure(row.successRate),
    totalCost: figure(row.totalCost),
    avgCostPerCompleted: figure(row.avgCostPerCompleted),
    unpricedRuns: figure(row.unpricedRuns) ?? undefined,
    sandboxRuns: figure(row.sandboxRuns) ?? undefined,
    freeRuns: figure(row.freeRuns) ?? undefined,
    rejectedApprovals: figure(row.rejectedApprovals) ?? undefined,
  }
}

// @find: agent outcomes, success rate, cost per agent; route: GET /api/orchestrator/insights/agents; used by: Agents page, Agent detail page
/**
 * How every agent has done over the last 30 days, by agent id (run:read). Pass
 * `enabled: can('run:read')`. An agent missing from the answer has had no run in the window.
 */
export function useAgentOutcomes(options: QueryOptions = {}) {
  return useQuery({
    queryKey: ['agent-outcomes', OUTCOME_WINDOW],
    queryFn: ({ signal }) =>
      api<OutcomeResponse>(`/api/orchestrator/insights/agents?window=${OUTCOME_WINDOW}`, { signal }),
    select: (response): Record<string, AgentOutcome> =>
      Object.fromEntries((response.agents ?? []).map((row) => [row.agentId, outcomeRow(row)])),
    enabled: options.enabled ?? true,
    // A figure over 30 days moves slowly; a minute is fresh enough for a page somebody keeps open.
    staleTime: 60_000,
  })
}

/** Finished runs behind a figure: completed, failed and cancelled. */
function finishedOf(outcome: AgentOutcome | undefined): number {
  if (!outcome) return 0
  return outcome.finishedRuns ?? outcome.completed + outcome.failed + (outcome.cancelled ?? 0)
}

export const NOT_ENOUGH_RUNS = 'Not enough runs yet'

/**
 * The success rate as a person reads it. Below five finished runs it says so, instead of a
 * percentage a single run could swing from 0 to 100, and runs still going do not count as failures.
 */
export function successRateFigure(outcome: AgentOutcome | undefined): { value: string; note: string } {
  const finished = finishedOf(outcome)
  const enough = outcome?.enoughRuns ?? finished >= MIN_FINISHED_RUNS
  if (!outcome || !enough) {
    return {
      value: NOT_ENOUGH_RUNS,
      note: finished === 0 ? 'It needs five finished runs.' : `${plural(finished, 'run', 'runs')} finished so far, five are needed.`,
    }
  }
  const rate = outcome.successRate ?? (finished > 0 ? outcome.completed / finished : null)
  if (rate == null) return { value: NOT_ENOUGH_RUNS, note: 'It needs five finished runs.' }
  return {
    value: `${Math.round(rate * 100)}%`,
    note: `${formatCount(outcome.completed)} of ${plural(finished, 'finished run', 'finished runs')} completed.`,
  }
}

/**
 * What the agent's runs cost over the window. A run on a model with no catalogue price is named as
 * unpriced, never counted as US$0: when nothing was priced the figure is "Unpriced", and when some
 * was, the note says the total leaves those runs out.
 */
export function costFigure(outcome: AgentOutcome | undefined): { value: string; note: string } {
  if (!outcome) return { value: formatMoney(0), note: 'No runs in this window.' }
  const unpriced = outcome.unpricedRuns ?? 0
  if (outcome.totalCost == null) {
    return unpriced > 0
      ? { value: 'Unpriced', note: `${plural(unpriced, 'run', 'runs')} on a model with no catalogue price.` }
      : { value: formatMoney(0), note: 'No runs in this window.' }
  }
  if (unpriced > 0) {
    return { value: formatMoney(outcome.totalCost), note: `Leaves out ${plural(unpriced, 'run', 'runs')} with no catalogue price.` }
  }
  const onlySandbox = (outcome.sandboxRuns ?? 0) > 0 && outcome.sandboxRuns === outcome.runs
  const free = outcome.freeRuns ?? 0
  // Nothing spent, and the runs were on free models (or the sandbox): free is known, not a priced zero.
  if (outcome.totalCost === 0 && free > 0) {
    return { value: 'Free', note: `${plural(free, 'run', 'runs')} on models that are free in the catalogue.` }
  }
  if (outcome.totalCost === 0 && onlySandbox) return { value: 'Free', note: 'Offline sandbox runs cost nothing.' }
  return {
    value: formatMoney(outcome.totalCost),
    note: onlySandbox ? 'Offline sandbox runs cost nothing.' : 'At catalogue prices.',
  }
}

/* ---- Revisions ---------------------------------------------------------------------------------------- */

/**
 * One revision of an agent's configuration (AgentRevisionsController.RevisionView), newest first
 * in the list. `changedFields` names what differs from the revision before it.
 */
export type AgentRevision = {
  id: string
  revision: number
  createdAt: string
  /** The person who saved it; "system" for one the platform wrote. */
  createdBy?: string | null
  changedFields: string[]
  initial: boolean
  /** The agent works from this revision now. */
  current: boolean
  /** A run has used it, which seals it: it is never edited, so those traces stay accurate. */
  usedByRuns: boolean
  systemPrompt?: string | null
  goals?: string | null
  temperature?: number | null
  maxOutputTokens?: number | null
  maxSteps: number
}

/** The field names the revisions endpoint uses, in the words the edit form uses. */
export const REVISION_FIELD_LABEL: Record<string, string> = {
  systemPrompt: 'Instructions',
  goals: 'Goals',
  temperature: 'Temperature',
  maxOutputTokens: 'Output limit',
  maxSteps: 'Step limit',
}

/** What changed in a revision, in words: "Instructions and goals", "First revision". */
export function changeSummary(revision: Pick<AgentRevision, 'initial' | 'changedFields'>): string {
  if (revision.initial) return 'First revision'
  // "Instructions, goals and step limit changed": the first word capitalised, the rest as words in a sentence.
  const names = revision.changedFields.map((field, index) => {
    const label = REVISION_FIELD_LABEL[field] ?? field
    return index === 0 ? label : label.charAt(0).toLowerCase() + label.slice(1)
  })
  if (names.length === 0) return 'Saved with no change'
  const text = names.length === 1 ? names[0]! : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
  return `${text} changed`
}

// @find: agent revisions, configuration history; route: GET /api/agents/{id}/revisions; used by: Agent detail page
/** An agent's revisions, newest first (agent:read). */
export const useAgentRevisions = (id: string, options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['agents', id, 'revisions'],
    queryFn: ({ signal }) => api<AgentRevision[]>(`/api/agents/${id}/revisions`, { signal }),
    enabled: Boolean(id) && (options.enabled ?? true),
  })

// @find: restore revision, put back old configuration; route: PUT /api/agents/{id}/configuration; used by: Agent detail page
/**
 * Puts an old revision back by saving its instructions, goals and limits as a new revision
 * (agent:update). Nothing is rewritten: the history gains revision N+1, and every run keeps the
 * revision it used.
 */
export function useRestoreRevision(id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (revision: AgentRevision) =>
      api<Agent>(`/api/agents/${id}/configuration`, {
        method: 'PUT',
        body: {
          systemPrompt: revision.systemPrompt ?? '',
          goals: revision.goals ?? '',
          temperature: revision.temperature ?? null,
          maxOutputTokens: revision.maxOutputTokens ?? null,
          maxSteps: revision.maxSteps,
        },
      }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['agents'] }),
  })
}

/* ---- Routing ------------------------------------------------------------------------------------------- */

// @find: set agent model policy, choose model for agent; route: PUT /api/agents/{id}/model-policy; used by: Agent detail page
/** Saves an agent's own chain of models (agent:set_model_policy), replacing the one it had. */
export function useSetAgentModelPolicy(id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: ModelPolicyInput) =>
      api<ModelPolicy>(`/api/agents/${id}/model-policy`, { method: 'PUT', body: input }),
    onSuccess: (policy) => {
      // Shown at once, so the editor does not flash the chain it just replaced while a read runs.
      client.setQueryData(['agents', id, 'model-policy'], policy)
      void client.invalidateQueries({ queryKey: ['agents', id, 'model-policy'] })
    },
  })
}

// @find: clear agent model policy, use default model; route: DELETE /api/agents/{id}/model-policy; used by: Agent detail page
/** Gives the agent back to the workspace routing policy by deleting its own (agent:set_model_policy). */
export function useClearAgentModelPolicy(id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api<ModelPolicy>(`/api/agents/${id}/model-policy`, { method: 'DELETE' }),
    onSuccess: (policy) => {
      client.setQueryData(['agents', id, 'model-policy'], policy)
      void client.invalidateQueries({ queryKey: ['agents', id, 'model-policy'] })
    },
  })
}
