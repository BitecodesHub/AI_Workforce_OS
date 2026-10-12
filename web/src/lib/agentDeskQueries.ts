// @find: takeover queries, take over browser, hand back, who has control, GET /api/runs/{id}/takeover, POST /api/runs/{id}/takeover, POST /api/runs/{id}/takeover/actions, POST /api/runs/{id}/takeover/release, working now tile polling, run glance
// @what: TanStack Query hooks for the Agent desk: the takeover state of a run, taking control, a person's action on the page, handing back, and the light, slower polls the Working now tiles use.
// @flow: Used by AgentDesk, TakeoverControls and WorkingNow; calls RunTakeoverController, RunGlanceController and the live view endpoint
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { BrowserLive } from './queries'

/** Where a run's takeover stands for the signed-in person (RunTakeoverController.view). */
export type TakeoverView = {
  status: 'none' | 'starting' | 'active' | 'released' | 'expired' | 'ended'
  /** A person holds control now and may act on the page. */
  active: boolean
  /** A person asked for control and the agent is finishing the step it is on. */
  starting: boolean
  /** Who holds or is getting control (a user id), only while starting or active. */
  takenBy: string | null
  takenAt: string | null
  /** When control returns to the agent by itself, only while active. */
  endsAt: string | null
  /** The signed-in person holds or is getting it. */
  mine: boolean
  /** The signed-in person may take control now (or already has it). */
  canTakeOver: boolean
  /** The signed-in person may hand control back. */
  canRelease: boolean
  /** How many actions the current or last holder made. */
  actions: number
  /** Who handed control back last, when it was handed back and nobody holds it now. */
  releasedBy: string | null
}

/** A run at a glance (RunGlanceController): its start, its latest step's kind and caption facts, and whether it browsed. */
export type RunGlance = {
  startedAt: string | null
  kind: string | null
  detail: Record<string, unknown>
  occurredAt: string | null
  browsing: boolean
}

/** One action on a page you have control of, in the shape the server checks. */
export type TakeoverAction =
  | { type: 'click'; x: number; y: number; width: number; height: number }
  | { type: 'type'; text: string }
  | { type: 'key'; key: string }
  | { type: 'scroll'; dy: number }
  | { type: 'navigate'; url: string }

/** What an action left on screen: the page's address and title and a fresh frame (base64 JPEG). */
export type TakeoverActionResult = { url: string; title: string; frame: string }

/** The cache key of a run's takeover state. */
const takeoverKey = (runId: string) => ['runs', runId, 'takeover'] as const

// @find: useTakeover, who has control of the browser, takeover polling
/**
 * A run's takeover state, read every 2 seconds while `active` (the run is working and the desk is
 * open) and the tab is in front; once otherwise. Key ['runs', id, 'takeover']. Not retried: a
 * refusal (the run hidden, or no run:read) is shown as it is.
 */
export function useTakeover(runId: string, active: boolean) {
  return useQuery({
    queryKey: takeoverKey(runId),
    queryFn: ({ signal }) => api<TakeoverView>(`/api/runs/${runId}/takeover`, { signal }),
    enabled: Boolean(runId),
    refetchInterval: active ? 2_000 : false,
    refetchIntervalInBackground: false,
    staleTime: 0,
    retry: false,
  })
}

/** Puts a fresh frame from an action into the live view's cache, so the picture changes at once. */
function showFrame(client: ReturnType<typeof useQueryClient>, runId: string, result: TakeoverActionResult) {
  if (!result.frame) return
  client.setQueryData<BrowserLive | null>(['runs', runId, 'browser-live'], (current) => ({
    busy: false,
    action: '',
    since: null,
    ...(current ?? {}),
    url: result.url,
    title: result.title,
    frame: result.frame,
  }))
}

// @find: useStartTakeover, take over browser, POST /api/runs/{id}/takeover
/** Takes control of the run's web browser; the agent waits. Stores the new state on success. */
export function useStartTakeover(runId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api<TakeoverView>(`/api/runs/${runId}/takeover`, { method: 'POST' }),
    onSuccess: (view) => client.setQueryData(takeoverKey(runId), view),
    onError: () => client.invalidateQueries({ queryKey: takeoverKey(runId) }),
  })
}

// @find: useTakeoverAction, click type key scroll navigate on taken over page, POST /api/runs/{id}/takeover/actions
/**
 * One action on the page you have control of. The answer's frame replaces the live picture at once.
 * Not kept in the mutation cache after it settles (gcTime 0), so what a person typed is not held
 * in memory longer than the request.
 */
export function useTakeoverAction(runId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (action: TakeoverAction) =>
      api<TakeoverActionResult>(`/api/runs/${runId}/takeover/actions`, { method: 'POST', body: action }),
    gcTime: 0,
    onSuccess: (result) => showFrame(client, runId, result),
    onError: () => client.invalidateQueries({ queryKey: takeoverKey(runId) }),
  })
}

// @find: useReleaseTakeover, hand back to agent, POST /api/runs/{id}/takeover/release
/** Hands control back to the agent with an optional note; refreshes the state and the run's steps. */
export function useReleaseTakeover(runId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { note?: string }) =>
      api<TakeoverView>(`/api/runs/${runId}/takeover/release`, {
        method: 'POST',
        body: input.note && input.note.trim() ? { note: input.note.trim() } : {},
      }),
    onSuccess: (view) => {
      client.setQueryData(takeoverKey(runId), view)
      void client.invalidateQueries({ queryKey: ['runs', runId, 'steps'] })
    },
    onError: () => client.invalidateQueries({ queryKey: takeoverKey(runId) }),
  })
}

/** How often a Working now tile refreshes: every 5 seconds, or every 8 when more than six tiles show. */
export function tileInterval(tiles: number): number {
  return tiles > 6 ? 8_000 : 5_000
}

// @find: useRunGlance, working now tile caption, run at a glance polling
/**
 * A run at a glance for a Working now tile (GET /api/runs/{id}/glance): a few facts rather than the
 * whole trace. Read every `interval` milliseconds while `enabled` (the run works, no desk is open)
 * and the tab is in front; once otherwise. Key ['runs', id, 'glance'].
 */
export function useRunGlance(runId: string, enabled: boolean, interval: number) {
  return useQuery({
    queryKey: ['runs', runId, 'glance'],
    queryFn: ({ signal }) => api<RunGlance>(`/api/runs/${runId}/glance`, { signal }),
    enabled: Boolean(runId),
    refetchInterval: enabled ? interval : false,
    refetchIntervalInBackground: false,
  })
}

// @find: working now tile live frame, small live browser frame
/**
 * The live view for a tile's small frame: the same cache as the run's preview
 * (['runs', id, 'browser-live']), read every `interval` milliseconds while the agent is browsing,
 * no desk is open and the tab is in front. Null when the run has no page open.
 */
export function useTileLive(runId: string, browsing: boolean, interval: number) {
  return useQuery({
    queryKey: ['runs', runId, 'browser-live'],
    queryFn: async ({ signal }) => (await api<BrowserLive | undefined>(`/api/runs/${runId}/browser/live`, { signal })) ?? null,
    enabled: Boolean(runId) && browsing,
    refetchInterval: browsing ? interval : false,
    refetchIntervalInBackground: false,
    retry: false,
    staleTime: 0,
  })
}
