import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { QueryOptions, Schedule } from './queries'

/*
 * Schedule queries that go beyond the basic list: a schedule's run history a page at a time, and
 * handing a schedule to somebody else. Kept beside lib/schedules.ts, whose rules decide when the
 * console offers either.
 */

/** One goal a schedule started, as its history lists it: title, outcome and when, no tasks. */
export type ScheduleRun = {
  id: string
  title: string
  status: string
  createdAt: string | null
  completedAt: string | null
}

/** One page of GET /api/schedules/{id}/runs, newest first. */
export type ScheduleRunsPage = {
  runs: ScheduleRun[]
  page: number
  size: number
  /** How many runs the schedule has started in all. */
  total: number
  hasMore: boolean
}

/** Runs shown per page; the server allows up to 100. */
export const SCHEDULE_RUNS_PAGE_SIZE = 20

const runRow = (run: ScheduleRun): ScheduleRun => ({
  ...run,
  createdAt: run.createdAt ?? null,
  completedAt: run.completedAt ?? null,
})

/** The page after `last`, or nothing once the server says there is no older one. */
export function nextRunsPage(last: Pick<ScheduleRunsPage, 'page' | 'hasMore'>): number | undefined {
  return last.hasMore ? last.page + 1 : undefined
}

/** Every run across the pages loaded so far, newest first, each once even if a page shifted underneath. */
export function flattenRunPages(pages: readonly Pick<ScheduleRunsPage, 'runs'>[]): ScheduleRun[] {
  const seen = new Set<string>()
  const runs: ScheduleRun[] = []
  for (const page of pages) {
    for (const run of page.runs) {
      if (seen.has(run.id)) continue
      seen.add(run.id)
      runs.push(run)
    }
  }
  return runs
}

/**
 * A schedule's own run history, newest first, a page at a time (task:read). A schedule that fires
 * every few minutes starts thousands of goals a month, so older pages load only when asked for.
 */
export function useScheduleRunPages(id: string, options: QueryOptions = {}) {
  return useInfiniteQuery({
    queryKey: ['schedules', id, 'runs', 'pages'],
    queryFn: async ({ pageParam }) => {
      const page = await api<ScheduleRunsPage>(
        `/api/schedules/${id}/runs?page=${pageParam}&size=${SCHEDULE_RUNS_PAGE_SIZE}`,
      )
      return { ...page, runs: page.runs.map(runRow) }
    },
    initialPageParam: 0,
    getNextPageParam: (lastPage) => nextRunsPage(lastPage),
    enabled: Boolean(id) && (options.enabled ?? true),
  })
}

/** Hands a schedule to another member, who it then runs as (task:cancel). */
export function useTransferScheduleOwner(id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { userId: string }) =>
      api<Schedule>(`/api/schedules/${id}/owner`, { method: 'PUT', body: input }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['schedules'] }),
  })
}
