// @find: workflows queries, list workflows, get workflow, create workflow, save workflow, update workflow, delete workflow, run workflow, workflow runs, workflow palette, goal timeline, live polling, GET /api/workflows, POST /api/workflows, PUT /api/workflows/{id}, DELETE /api/workflows/{id}, POST /api/workflows/{id}/run, GET /api/workflows/{id}/runs, GET /api/workflows/palette, GET /api/goals/{id}/timeline, canEditWorkflows
// @what: Reads and changes workflows, and reads a goal's timeline for the live view and the replay, through the orchestrator.
// @flow: Used by routes/Workflows.tsx, routes/WorkflowEditor.tsx and routes/WorkflowRun.tsx; talks to the orchestrator through lib/api
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import { profile } from './session'
import { clearDraft, draftKey } from './workflowDraft'
import type { QueryOptions } from './queries'

/*
 * A workflow is a canvas of agent steps. Anyone who can read tasks (task:read) can see workflows
 * and watch their runs; running needs task:create, the same permission that starts any goal, and
 * changing or deleting one also needs to be its maker or to hold task:cancel (the server says which
 * in canChange). A run is one goal, so its live view and its replay read the goal's timeline.
 */

/** One problem the server found with a stored workflow. */
export type WorkflowProblem = { code: string; message: string; nodeId: string | null; edgeId: string | null }

/** A workflow's last run, as the list shows it. */
export type WorkflowLastRun = { goalId: string; status: string | null; startedAt: string; completedAt: string | null }

/** A workflow as the list shows it. */
export type WorkflowSummary = {
  id: string
  name: string
  description: string
  steps: number
  connections: number
  version: number
  createdBy: string | null
  updatedBy: string | null
  createdAt: string
  updatedAt: string
  lastRun: WorkflowLastRun | null
  /** Whether the signed-in person may change and delete it: its maker, or someone who can cancel work. */
  canChange?: boolean
}

/** A workflow as the editor reads it. `graph` is normalised by lib/workflowGraph before use. */
export type Workflow = {
  id: string
  name: string
  description: string
  graph: unknown
  version: number
  createdBy: string | null
  updatedBy: string | null
  createdAt: string
  updatedAt: string
  /** The {{names}} the steps use; a run asks a value for each. */
  placeholders: string[]
  errors: WorkflowProblem[]
  warnings: WorkflowProblem[]
  /** Whether the signed-in person may save over it and delete it. */
  canChange: boolean
}

/** What POST /api/workflows and PUT /api/workflows/{id} take. `version` is required for an update. */
export type WorkflowInput = { name: string; description: string; graph: unknown; version?: number }

/** One run of a workflow, with its goal's state. */
export type WorkflowRunView = {
  id: string
  workflowId: string
  workflowVersion: number
  goalId: string
  status: string | null
  startedBy: string | null
  startedAt: string
  completedAt: string | null
}

/** One agent on the palette. */
export type PaletteAgent = {
  id: string
  name: string
  category: string
  status: string
  fallback: boolean
  description: string | null
  /** Connector server ids the agent is granted; null for a role that cannot read agent configuration (agent:read). */
  connectors: string[] | null
}

/** One connector on the palette. */
export type PaletteConnector = { id: string; name: string; category: string; description: string | null }

export type WorkflowPalette = { agents: PaletteAgent[]; connectors: PaletteConnector[] }

/** One task on a goal's timeline. */
export type TimelineTask = {
  id: string
  title: string
  agentId: string | null
  agentName: string | null
  agentCategory: string | null
  position: number
  dependsOn: string[]
  /** True for a workflow's task: dependsOn is complete, so an empty list waits for nothing. */
  explicitDependencies?: boolean
  status: string
  startedAt: string | null
  completedAt: string | null
  runId: string | null
}

/** One attempt at a task. */
export type TimelineRun = { id: string; taskId: string | null; status: string; startedAt: string; completedAt: string | null }

/** One wait on a person: an approval or a question. `endedAt` is null while it still waits. */
export type TimelineWait = {
  runId: string
  taskId: string | null
  kind: 'approval' | 'question' | string
  startedAt: string
  endedAt: string | null
  outcome: string
}

/** The canvas a goal was started from, as it ran. */
export type TimelineWorkflow = {
  /** Null once the workflow has been deleted; its runs are kept for the replay. */
  workflowId: string | null
  workflowRunId: string
  name: string | null
  version: number
  graph: unknown
  /** Node id by task position. */
  nodeIds: string[]
}

/** GET /api/goals/{id}/timeline. */
export type GoalTimeline = {
  goalId: string
  title: string
  status: string
  createdAt: string
  completedAt: string | null
  /** The server's clock when this was read. */
  now: string
  tasks: TimelineTask[]
  runs: TimelineRun[]
  waits: TimelineWait[]
  workflow: TimelineWorkflow | null
}

/** How often the live view reads the timeline while the goal is under way and the tab is visible. */
export const LIVE_POLL_MS = 3_000

const ACTIVE_GOAL = new Set(['planning', 'running', 'waiting'])

/** Fills in what an older or partial answer leaves out, so screens never read undefined. */
function workflowRow(workflow: Workflow): Workflow {
  return {
    ...workflow,
    description: workflow.description ?? '',
    placeholders: Array.isArray(workflow.placeholders) ? workflow.placeholders : [],
    errors: Array.isArray(workflow.errors) ? workflow.errors : [],
    warnings: Array.isArray(workflow.warnings) ? workflow.warnings : [],
    canChange: workflow.canChange ?? true,
  }
}

/** Fills in a timeline's lists, so a partial answer still draws. */
function timelineRow(timeline: GoalTimeline): GoalTimeline {
  return {
    ...timeline,
    tasks: (timeline.tasks ?? []).map((task) => ({
      ...task,
      dependsOn: task.dependsOn ?? [],
      explicitDependencies: task.explicitDependencies ?? false,
    })),
    runs: timeline.runs ?? [],
    waits: timeline.waits ?? [],
    workflow: timeline.workflow ?? null,
  }
}

// @find: can edit workflows, task:create
/** Whether the signed-in role may create and run workflows (and change its own). */
export function canEditWorkflows(can: (permission: string) => boolean): boolean {
  return can('task:create')
}

// @find: list workflows; route: GET /api/workflows; used by: Workflows page
/** Every workflow in the workspace, most recently changed first (task:read). */
export const useWorkflows = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['workflows'],
    queryFn: ({ signal }) => api<WorkflowSummary[]>('/api/workflows', { signal }),
    enabled: options.enabled ?? true,
  })

// @find: one workflow; route: GET /api/workflows/{id}; used by: workflow editor
/** One workflow with its canvas and its current problems (task:read). Skipped without an id. */
export const useWorkflow = (id: string | null, options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['workflows', id ?? ''],
    queryFn: async ({ signal }) => workflowRow(await api<Workflow>(`/api/workflows/${id ?? ''}`, { signal })),
    enabled: Boolean(id) && (options.enabled ?? true),
    // The editor holds its own copy while a person works; a refetch on focus would only race it.
    refetchOnWindowFocus: false,
  })

// @find: workflow palette; route: GET /api/workflows/palette; used by: workflow editor
/** The agents and connectors the canvas can place (task:read). */
export const useWorkflowPalette = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['workflows', 'palette'],
    queryFn: ({ signal }) => api<WorkflowPalette>('/api/workflows/palette', { signal }),
    enabled: options.enabled ?? true,
    staleTime: 30_000,
  })

// @find: workflow run history; route: GET /api/workflows/{id}/runs; used by: workflow editor
/** A workflow's runs, newest first, refreshed every 10 seconds while one is still going (task:read). */
export const useWorkflowRuns = (id: string | null, options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['workflows', id ?? '', 'runs'],
    queryFn: ({ signal }) => api<WorkflowRunView[]>(`/api/workflows/${id ?? ''}/runs`, { signal }),
    enabled: Boolean(id) && (options.enabled ?? true),
    refetchInterval: (query) => (query.state.data?.some((run) => ACTIVE_GOAL.has(run.status ?? '')) ? 10_000 : false),
  })

// @find: create workflow; route: POST /api/workflows; used by: workflow editor
/** Saves a new workflow (task:create). */
export function useCreateWorkflow() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: WorkflowInput) =>
      workflowRow(await api<Workflow>('/api/workflows', { method: 'POST', body: input })),
    onSuccess: (saved) => {
      client.setQueryData(['workflows', saved.id], saved)
      void client.invalidateQueries({ queryKey: ['workflows'], exact: true })
    },
  })
}

// @find: update workflow, save workflow; route: PUT /api/workflows/{id}; used by: workflow editor
/** Saves a workflow over the version it was loaded at (task:create). A stale version is refused with 409. */
export function useUpdateWorkflow(id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: WorkflowInput) =>
      workflowRow(await api<Workflow>(`/api/workflows/${id}`, { method: 'PUT', body: input })),
    onSuccess: (saved) => {
      client.setQueryData(['workflows', saved.id], saved)
      void client.invalidateQueries({ queryKey: ['workflows'], exact: true })
    },
  })
}

// @find: delete workflow; route: DELETE /api/workflows/{id}; used by: Workflows page, workflow editor
/**
 * Deletes a workflow; its runs and the goals they started stay, so they can still be replayed
 * (task:create, maker or task:cancel). Once it is gone, this person's unsaved draft of it is
 * forgotten too: nothing could ever offer it back, so it would only sit in this browser.
 */
export function useDeleteWorkflow() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<void>(`/api/workflows/${id}`, { method: 'DELETE' }),
    onSuccess: (_, id) => {
      const who = profile()
      clearDraft(draftKey(id, who?.userId ?? null, who?.workspaceId ?? null))
      client.removeQueries({ queryKey: ['workflows', id] })
      void client.invalidateQueries({ queryKey: ['workflows'], exact: true })
    },
  })
}

/** What a run takes: the placeholder values, and the version on screen so a newer one is never run unseen. */
export type RunInput = { inputs: Record<string, string>; version?: number }

// @find: run workflow; route: POST /api/workflows/{id}/run; used by: workflow editor
/**
 * Runs a workflow as one goal with the given values (task:create). With a version, a workflow
 * someone else saved since is refused with 409 instead of running what the person has not seen.
 */
export function useRunWorkflow(id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ inputs, version }: RunInput) =>
      api<WorkflowRunView>(`/api/workflows/${id}/run`, { method: 'POST', body: { inputs, version } }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['workflows', id, 'runs'] })
      void client.invalidateQueries({ queryKey: ['workflows'], exact: true })
      void client.invalidateQueries({ queryKey: ['goals'] })
    },
  })
}

// @find: goal timeline, live view polling; route: GET /api/goals/{id}/timeline; used by: workflow run view, replay
/**
 * One goal's timeline (task:read). Read again every LIVE_POLL_MS while the goal is under way;
 * TanStack Query pauses that while the tab is hidden, so a background tab costs nothing.
 */
export const useGoalTimeline = (goalId: string | null, options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['goals', goalId ?? '', 'timeline'],
    queryFn: async ({ signal }) => timelineRow(await api<GoalTimeline>(`/api/goals/${goalId ?? ''}/timeline`, { signal })),
    enabled: Boolean(goalId) && (options.enabled ?? true),
    refetchInterval: (query) => (query.state.data && ACTIVE_GOAL.has(query.state.data.status) ? LIVE_POLL_MS : false),
  })
