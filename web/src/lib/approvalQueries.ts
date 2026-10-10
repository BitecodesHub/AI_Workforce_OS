// @find: approvals, approval queue, pending approvals, decided approvals, approval history, approve, reject, decide approvals, bulk decision, approval count, run approval, four-eyes, POST /api/approvals/decisions, Approvals page
// @what: Pages the approvals queue and history, counts what waits, finds the request a run is parked on, and decides several approvals at once.
// @flow: Called by Approvals route, TraceStep, InlineApproval and WaitingForApproval; calls api() against /api/approvals; the single decision stays in queries.ts.
import { useCallback } from 'react'
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import { useMemberNames } from './queries'
import type { Approval, Member, QueryOptions } from './queries'
import { can, profile } from './session'

/*
 * The approvals queue and its history, a page at a time, and deciding several at once. Kept beside
 * lib/approvals.ts, whose rules say how a request is read; the single decision and the plain
 * pending list stay in lib/queries.ts, where the navigation badge and the Command Map read them.
 *
 * Every key starts with 'approvals', so a decision made anywhere (useDecideApproval included)
 * refreshes all of these together, and every write here refreshes the board as well.
 */

/**
 * One approval as the server describes it now: the request itself, who decided it and why, and the
 * work and the person behind it. `canDecide` already counts the workspace's four-eyes rule.
 */
export type ApprovalItem = Approval & {
  decidedBy: string | null
  decidedAt: string | null
  decisionNote: string | null
  goalId: string | null
  goalTitle: string | null
  /** Who asked for the work: the goal's requester, or whoever started a run directly. */
  requestedBy: string | null
  /** What the task was told to do, cut to 500 characters. */
  taskInstruction: string | null
  canDecide: boolean
  /** Rejected as written, with feedback, while the run carried on. */
  sentBack?: boolean
}

/** How many requests are waiting, and how many of them the signed-in person could decide. */
export type ApprovalCount = { pending: number; canDecide: number }

/** What the server says about one approval in a bulk decision (see lib/approvals.ts bulkSummary). */
export type BulkItem = {
  id: string
  result: 'decided' | 'already_decided' | 'expired' | 'forbidden' | 'not_found'
  status: string | null
  message: string | null
}

/** What deciding several at once returns: one answer per request, in the order asked. */
export type BulkDecision = { results: BulkItem[] }

/** Waiting requests are fetched this many at a time; the queue's own order (soonest to expire) is kept. */
export const PENDING_PAGE_SIZE = 50
/** Decisions in the history, a page at a time, newest first. */
export const DECIDED_PAGE_SIZE = 25

/**
 * A row from an older server has none of the newer fields. Whether the person may decide it then
 * falls back to their role, as the queue did before the server said so.
 */
export function approvalItemRow(row: Approval & Partial<ApprovalItem>): ApprovalItem {
  return {
    ...row,
    tool: row.tool ?? null,
    decidedBy: row.decidedBy ?? null,
    decidedAt: row.decidedAt ?? null,
    decisionNote: row.decisionNote ?? null,
    goalId: row.goalId ?? null,
    goalTitle: row.goalTitle ?? null,
    requestedBy: row.requestedBy ?? null,
    taskInstruction: row.taskInstruction ?? null,
    canDecide: row.canDecide ?? can('approval:decide'),
    sentBack: row.sentBack ?? false,
  }
}

/** The next page's number, or nothing once a page comes back short and there is no more. */
export function nextApprovalPage(lastPage: readonly unknown[], pageParam: number, size: number): number | undefined {
  return lastPage.length >= size ? pageParam + 1 : undefined
}

/** Every approval across the pages loaded so far, each once even if the queue shifted between two pages. */
export function flattenApprovalPages(pages: ReadonlyArray<readonly ApprovalItem[]>): ApprovalItem[] {
  const seen = new Set<string>()
  const items: ApprovalItem[] = []
  for (const page of pages) {
    for (const item of page) {
      if (seen.has(item.id)) continue
      seen.add(item.id)
      items.push(item)
    }
  }
  return items
}

function query(params: Record<string, string | number | null | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== '') search.set(key, String(value))
  }
  return search.toString()
}

async function fetchPage(params: Record<string, string | number | null | undefined>): Promise<ApprovalItem[]> {
  return (await api<Array<Approval & Partial<ApprovalItem>>>(`/api/approvals?${query(params)}`)).map(approvalItemRow)
}

// @find: pending approvals queue, load more; route: GET /api/approvals?status=pending; used by: Approvals page
/**
 * The queue, soonest to expire first, a page at a time (approval:read). Refreshed every 30 seconds
 * while it is on screen, like the plain list.
 */
export function usePendingApprovalPages(options: QueryOptions = {}) {
  return useInfiniteQuery({
    queryKey: ['approvals', 'pending', 'pages'],
    queryFn: ({ pageParam }) => fetchPage({ status: 'pending', page: pageParam, size: PENDING_PAGE_SIZE }),
    initialPageParam: 0,
    getNextPageParam: (lastPage, _all, lastParam) => nextApprovalPage(lastPage, lastParam, PENDING_PAGE_SIZE),
    refetchInterval: 30_000,
    enabled: options.enabled ?? true,
  })
}

// @find: approval history, decided approvals; route: GET /api/approvals (decided); used by: Approvals page
/**
 * What was decided, newest first, a page at a time (approval:read). `status` narrows to one
 * outcome - approved, rejected, expired or cancelled - and `agentId` to one agent; both are asked
 * of the server, so the history is complete however many decisions there are.
 */
export function useDecidedApprovalPages(
  filters: { agentId?: string | null; status?: string | null },
  options: QueryOptions = {},
) {
  const status = filters.status || 'decided'
  const agentId = filters.agentId || null
  return useInfiniteQuery({
    queryKey: ['approvals', 'decided', { status, agentId }],
    queryFn: ({ pageParam }) => fetchPage({ status, agentId, page: pageParam, size: DECIDED_PAGE_SIZE }),
    initialPageParam: 0,
    getNextPageParam: (lastPage, _all, lastParam) => nextApprovalPage(lastPage, lastParam, DECIDED_PAGE_SIZE),
    enabled: options.enabled ?? true,
  })
}

// @find: approval count, badge number; route: GET /api/approvals/count; used by: Approvals page
/** How many are waiting, counted by the server without loading a payload (approval:read). */
export function useApprovalCount(options: QueryOptions & { agentId?: string | null } = {}) {
  const agentId = options.agentId || null
  return useQuery({
    queryKey: ['approvals', 'count', { agentId }],
    queryFn: () => api<ApprovalCount>(`/api/approvals/count?${query({ agentId })}`),
    refetchInterval: 30_000,
    enabled: options.enabled ?? true,
  })
}

// @find: approval a run is waiting on; route: GET /api/approvals?runId=; used by: run trace (InlineApproval, WaitingForApproval)
/**
 * The request one run is parked on, if any (approval:read): what a run's own page and the chat
 * show for a run that is waiting. Asked of the server by run, so it is found however long the
 * queue is.
 */
export function useRunApproval(runId: string, options: QueryOptions = {}) {
  return useQuery({
    queryKey: ['approvals', 'run', runId],
    queryFn: async () => (await fetchPage({ status: 'pending', runId, size: 1 }))[0] ?? null,
    enabled: Boolean(runId) && (options.enabled ?? true),
  })
}

// @find: one approval detail; route: GET /api/approvals/{id}; used by: Approvals page, run trace (TraceStep)
/** One approval, waiting or decided (approval:read): what a run's trace links to. */
export function useApproval(id: string | null | undefined, options: QueryOptions = {}) {
  return useQuery({
    queryKey: ['approvals', 'one', id],
    queryFn: async () => approvalItemRow(await api<Approval & Partial<ApprovalItem>>(`/api/approvals/${id}`)),
    enabled: Boolean(id) && (options.enabled ?? true),
  })
}

// @find: approve or reject several approvals; route: POST /api/approvals/decisions; used by: Approvals page
/**
 * Decides several requests the same way, each on its own (approval:decide). The answer says what
 * happened to every one, so a request someone else decided first is reported, not an error.
 */
export function useDecideApprovals() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { ids: string[]; approved: boolean; note?: string }) =>
      api<BulkDecision>('/api/approvals/decisions', {
        method: 'POST',
        body: { ids: input.ids, approved: input.approved, note: input.note ?? null },
      }),
    // Settled rather than success, like a single decision: a bulk call that failed part way must
    // still clear what was decided.
    onSettled: () => {
      client.invalidateQueries({ queryKey: ['approvals'] })
      client.invalidateQueries({ queryKey: ['board'] })
      client.invalidateQueries({ queryKey: ['runs'] })
      client.invalidateQueries({ queryKey: ['goals'] })
      client.invalidateQueries({ queryKey: ['conversations'] })
    },
  })
}

// @find: who decided, member names for approvals; route: none (reads cached members); used by: Approvals page, run trace (InlineApproval, TraceStep)
/**
 * Names a person from the workspace's member list, and says nothing false when the list cannot:
 * only a list that loaded can say somebody has left. A role that may not read members is never
 * shown a name, and so reads 'Someone'. `me` is the signed-in person, who reads as 'You' wherever
 * the caller says so.
 */
export function useMemberNamer(): { me: string | null; nameOf: (userId: string) => string } {
  const members = useMemberNames({ enabled: can('member:read') })
  const me = profile()?.userId ?? null
  const loaded = Object.keys(members).length > 0
  const nameOf = useCallback(
    (userId: string) => {
      const member: Member | undefined = members[userId]
      if (member) return member.displayName
      return loaded ? 'Former member' : 'Someone'
    },
    [members, loaded],
  )
  return { me, nameOf }
}
