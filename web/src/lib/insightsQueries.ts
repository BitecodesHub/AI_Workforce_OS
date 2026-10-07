import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, api, refreshAccessToken } from './api'
import type { QueryOptions } from './queries'
import { accessToken, clearSession } from './session'

/*
 * What Analytics, the Command Map, the run trace and the chat read about how the workforce is
 * doing: the figures over a window, each agent's outcomes, the inputs the estimated value is
 * worked out from, the spending caps and the spend behind them, and the ratings people give
 * answers.
 *
 * Every number is read through figure(), because the service writes a decimal as a JSON number
 * but a proxy or a test double may write it as text; a value that is neither is absent, never zero.
 * Absent is a state the screens say out loud ("Not enough runs yet", "Not estimated"): a figure
 * that is not known must never turn into a 0 somewhere between the wire and the tile.
 */

/* ---- The window ------------------------------------------------------------------------------------ */

export type InsightsWindow = '7d' | '30d' | '90d'

export const INSIGHTS_WINDOWS: readonly InsightsWindow[] = ['7d', '30d', '90d']

/** What a window opens on when none is asked for. */
export const DEFAULT_WINDOW: InsightsWindow = '30d'

export const WINDOW_DAYS: Record<InsightsWindow, number> = { '7d': 7, '30d': 30, '90d': 90 }

export const WINDOW_LABEL: Record<InsightsWindow, string> = {
  '7d': 'Last 7 days',
  '30d': 'Last 30 days',
  '90d': 'Last 90 days',
}

/** A window from a URL or a stored value; anything else is the default. */
export function parseWindow(value: string | null | undefined): InsightsWindow {
  return INSIGHTS_WINDOWS.find((window) => window === value) ?? DEFAULT_WINDOW
}

/**
 * The calendar days a window covers, as the dates the usage report takes: from the start of the
 * UTC day (days - 1) days ago, to today. The same cut the insights service makes, so the spend
 * breakdown and the tiles above it add up to the same money.
 */
export function windowRange(window: InsightsWindow, now: Date = new Date()): { from: string; to: string } {
  const today = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate())
  const first = today - (WINDOW_DAYS[window] - 1) * 86_400_000
  return { from: new Date(first).toISOString().slice(0, 10), to: new Date(today).toISOString().slice(0, 10) }
}

/* ---- Reading numbers --------------------------------------------------------------------------------- */

/** A number as the service wrote it, or null for anything that is not one. */
export function figure(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

const count = (value: unknown): number => figure(value) ?? 0

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

const text = (value: unknown): string | null => (typeof value === 'string' && value !== '' ? value : null)

const list = (value: unknown): unknown[] => (Array.isArray(value) ? value : [])

/* ---- The workspace's figures -------------------------------------------------------------------------- */

/** A figure now and over the window before, and the change between them; absent where either is. */
export type Delta = {
  current: number | null
  previous: number | null
  change: number | null
  changePercent: number | null
}

export type Insights = {
  window: InsightsWindow
  from: string | null
  to: string | null
  goals: {
    completed: number
    failed: number
    cancelled: number
    bySource: { source: string; completed: number; failed: number }[]
    byDay: { day: string; completed: number; failed: number }[]
  }
  tasks: {
    completed: number
    failed: number
    /** Completed over completed and failed, 0 to 1; null when nothing has finished. */
    successRate: number | null
    completedWithoutRetry: number
    withoutRetryRate: number | null
  }
  runs: {
    total: number
    finished: number
    active: number
    byStatus: Record<string, number>
    /** Finished runs on a real model the catalogue has no price for. */
    unpriced: number
    sandboxOnly: number
  }
  spend: {
    total: number
    byDay: { day: string; cost: number }[]
    costPerCompletedGoal: number | null
    costedGoals: number
    unpricedGoals: number
    sandboxGoals: number
    failedOrCancelledSpend: number
    unpricedRuns: number
  }
  approvals: {
    raised: number
    approved: number
    rejected: number
    expired: number
    cancelled: number
    pending: number
    medianDecisionSeconds: number | null
    p90DecisionSeconds: number | null
  }
  questions: { asked: number; answered: number; per100Runs: number | null; medianAnswerSeconds: number | null }
  failureReasons: { reason: string; count: number }[]
  /** Absent until at least one agent has minutes per task. Every amount in it is an estimate. */
  value: {
    hourlyRate: number | null
    agentsEstimated: number
    agentsNotEstimated: number
    completedTasks: number
    hoursReturned: number | null
    humanEquivalentCost: number | null
    netValue: number | null
  } | null
  deltas: Record<string, Delta>
}

function deltaRow(raw: unknown): Delta {
  const row = isRecord(raw) ? raw : {}
  return {
    current: figure(row.current),
    previous: figure(row.previous),
    change: figure(row.change),
    changePercent: figure(row.changePercent),
  }
}

/**
 * The insights as the service sent them, with every number a number, or null when the answer is
 * not insights at all (an empty array from a stub, a proxy's error page). A missing section is
 * zeros and empty lists, which are true of a workspace that has done nothing.
 */
export function normaliseInsights(raw: unknown): Insights | null {
  if (!isRecord(raw) || !isRecord(raw.goals)) return null
  const goals = raw.goals
  const tasks = isRecord(raw.tasks) ? raw.tasks : {}
  const runs = isRecord(raw.runs) ? raw.runs : {}
  const spend = isRecord(raw.spend) ? raw.spend : {}
  const approvals = isRecord(raw.approvals) ? raw.approvals : {}
  const questions = isRecord(raw.questions) ? raw.questions : {}
  const value = isRecord(raw.value) ? raw.value : null
  const deltas = isRecord(raw.deltas) ? raw.deltas : {}

  return {
    window: parseWindow(text(raw.window)),
    from: text(raw.from),
    to: text(raw.to),
    goals: {
      completed: count(goals.completed),
      failed: count(goals.failed),
      cancelled: count(goals.cancelled),
      bySource: list(goals.bySource)
        .filter(isRecord)
        .map((row) => ({
          source: text(row.source) ?? 'unknown',
          completed: count(row.completed),
          failed: count(row.failed),
        })),
      byDay: list(goals.byDay)
        .filter(isRecord)
        .map((row) => ({ day: text(row.day) ?? '', completed: count(row.completed), failed: count(row.failed) })),
    },
    tasks: {
      completed: count(tasks.completed),
      failed: count(tasks.failed),
      successRate: figure(tasks.successRate),
      completedWithoutRetry: count(tasks.completedWithoutRetry),
      withoutRetryRate: figure(tasks.withoutRetryRate),
    },
    runs: {
      total: count(runs.total),
      finished: count(runs.finished),
      active: count(runs.active),
      byStatus: Object.fromEntries(
        Object.entries(isRecord(runs.byStatus) ? runs.byStatus : {}).map(([status, n]) => [status, count(n)]),
      ),
      unpriced: count(runs.unpriced),
      sandboxOnly: count(runs.sandboxOnly),
    },
    spend: {
      total: count(spend.total),
      byDay: list(spend.byDay)
        .filter(isRecord)
        .map((row) => ({ day: text(row.day) ?? '', cost: count(row.cost) })),
      costPerCompletedGoal: figure(spend.costPerCompletedGoal),
      costedGoals: count(spend.costedGoals),
      unpricedGoals: count(spend.unpricedGoals),
      sandboxGoals: count(spend.sandboxGoals),
      failedOrCancelledSpend: count(spend.failedOrCancelledSpend),
      unpricedRuns: count(spend.unpricedRuns),
    },
    approvals: {
      raised: count(approvals.raised),
      approved: count(approvals.approved),
      rejected: count(approvals.rejected),
      expired: count(approvals.expired),
      cancelled: count(approvals.cancelled),
      pending: count(approvals.pending),
      medianDecisionSeconds: figure(approvals.medianDecisionSeconds),
      p90DecisionSeconds: figure(approvals.p90DecisionSeconds),
    },
    questions: {
      asked: count(questions.asked),
      answered: count(questions.answered),
      per100Runs: figure(questions.per100Runs),
      medianAnswerSeconds: figure(questions.medianAnswerSeconds),
    },
    failureReasons: list(raw.failureReasons)
      .filter(isRecord)
      .map((row) => ({ reason: text(row.reason) ?? 'No reason recorded', count: count(row.count) })),
    value: value
      ? {
          hourlyRate: figure(value.hourlyRate),
          agentsEstimated: count(value.agentsEstimated),
          agentsNotEstimated: count(value.agentsNotEstimated),
          completedTasks: count(value.completedTasks),
          hoursReturned: figure(value.hoursReturned),
          humanEquivalentCost: figure(value.humanEquivalentCost),
          netValue: figure(value.netValue),
        }
      : null,
    deltas: Object.fromEntries(Object.entries(deltas).map(([key, row]) => [key, deltaRow(row)])),
  }
}

/** The workspace's figures over a window (analytics:read). Pass `enabled: can('analytics:read')`. */
export function useInsights(window: InsightsWindow, options: QueryOptions = {}) {
  return useQuery({
    queryKey: ['insights', window],
    queryFn: ({ signal }) => api<unknown>(`/api/orchestrator/insights?window=${window}`, { signal }),
    select: normaliseInsights,
    enabled: options.enabled ?? true,
    // A figure over days moves slowly; a minute is fresh enough for a page somebody keeps open.
    staleTime: 60_000,
  })
}

/* ---- Each agent ---------------------------------------------------------------------------------------- */

export type AgentInsight = {
  agentId: string
  name: string
  category: string | null
  status: string | null
  runs: number
  finishedRuns: number
  completed: number
  failed: number
  cancelled: number
  /** Whether there are enough finished runs for a rate to mean anything. */
  enoughRuns: boolean
  successRate: number | null
  /** Null when every run was unpriced: the cost is not zero, it is not known. */
  totalCost: number | null
  avgCostPerCompleted: number | null
  unpricedRuns: number
  sandboxRuns: number
  rejectedApprovals: number
  lastActive: string | null
  ratings: number
  thumbsDown: number
  satisfactionRate: number | null
  minutesPerTask: number | null
  completedTasks: number
  hoursReturned: number | null
}

export type AgentInsights = {
  window: InsightsWindow
  hourlyRate: number | null
  agents: AgentInsight[]
}

function agentRow(row: Record<string, unknown>): AgentInsight {
  return {
    agentId: text(row.agentId) ?? '',
    name: text(row.name) ?? 'Unnamed agent',
    category: text(row.category),
    status: text(row.status),
    runs: count(row.runs),
    finishedRuns: count(row.finishedRuns),
    completed: count(row.completed),
    failed: count(row.failed),
    cancelled: count(row.cancelled),
    enoughRuns: row.enoughRuns === true,
    successRate: figure(row.successRate),
    totalCost: figure(row.totalCost),
    avgCostPerCompleted: figure(row.avgCostPerCompleted),
    unpricedRuns: count(row.unpricedRuns),
    sandboxRuns: count(row.sandboxRuns),
    rejectedApprovals: count(row.rejectedApprovals),
    lastActive: text(row.lastActive),
    ratings: count(row.ratings),
    thumbsDown: count(row.thumbsDown),
    satisfactionRate: figure(row.satisfactionRate),
    minutesPerTask: figure(row.minutesPerTask),
    completedTasks: count(row.completedTasks),
    hoursReturned: figure(row.hoursReturned),
  }
}

/** Each agent's outcomes, or null when the answer is not that (see normaliseInsights). */
export function normaliseAgentInsights(raw: unknown): AgentInsights | null {
  if (!isRecord(raw) || !Array.isArray(raw.agents)) return null
  return {
    window: parseWindow(text(raw.window)),
    hourlyRate: figure(raw.hourlyRate),
    agents: raw.agents.filter(isRecord).map(agentRow),
  }
}

/** How each agent has done over a window (run:read). Pass `enabled: can('run:read')`. */
export function useAgentInsights(window: InsightsWindow, options: QueryOptions = {}) {
  return useQuery({
    queryKey: ['insights', 'agents', window],
    queryFn: ({ signal }) => api<unknown>(`/api/orchestrator/insights/agents?window=${window}`, { signal }),
    select: normaliseAgentInsights,
    enabled: options.enabled ?? true,
    staleTime: 60_000,
  })
}

/* ---- The inputs behind the estimated value --------------------------------------------------------------- */

/** What every figure worked out from the inputs below is called, wherever it is shown. */
export const VALUE_LABEL = 'Estimate from your inputs (current values)'

export type ValueSettings = {
  label: string
  /** The loaded hourly staff cost in US dollars; null until one is entered. */
  hourlyRate: number | null
  agents: { agentId: string; name: string; minutesPerTask: number | null }[]
}

export type ValueSettingsInput = {
  hourlyRate: number | null
  /** Minutes per agent id; an agent left out has no estimate. */
  minutesPerTask: Record<string, number>
}

export function normaliseValueSettings(raw: unknown): ValueSettings | null {
  if (!isRecord(raw) || !Array.isArray(raw.agents)) return null
  return {
    label: text(raw.label) ?? VALUE_LABEL,
    hourlyRate: figure(raw.hourlyRate),
    agents: raw.agents.filter(isRecord).map((row) => ({
      agentId: text(row.agentId) ?? '',
      name: text(row.name) ?? 'Unnamed agent',
      minutesPerTask: figure(row.minutesPerTask),
    })),
  }
}

/** The hourly staff cost and the minutes per task (analytics:read). */
export function useValueSettings(options: QueryOptions = {}) {
  return useQuery({
    queryKey: ['value-settings'],
    queryFn: ({ signal }) => api<unknown>('/api/orchestrator/value-settings', { signal }),
    select: normaliseValueSettings,
    enabled: options.enabled ?? true,
  })
}

/** Replaces the inputs (budget:manage), then refreshes every figure that was worked out from them. */
export function useSaveValueSettings() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: ValueSettingsInput) =>
      normaliseValueSettings(
        await api<unknown>('/api/orchestrator/value-settings', {
          method: 'PUT',
          body: { hourlyRate: input.hourlyRate, minutesPerTask: input.minutesPerTask },
        }),
      ),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['value-settings'] })
      void client.invalidateQueries({ queryKey: ['insights'] })
    },
  })
}

/* ---- The spending caps ------------------------------------------------------------------------------------- */

export type Budget = {
  monthlyCap: number | null
  perRunCap: number | null
  perAgentDailyCap: number | null
  /** What happens at a cap: 'stop', or 'sandbox' to answer on the offline model instead. */
  onExhausted: 'stop' | 'sandbox'
  spentThisMonth: number
  remaining: number | null
  projectedMonthEnd: number | null
  periodStart: string | null
}

export type BudgetInput = {
  monthlyCap: number | null
  perRunCap: number | null
  perAgentDailyCap: number | null
  onExhausted: 'stop' | 'sandbox'
}

export function normaliseBudget(raw: unknown): Budget | null {
  if (!isRecord(raw)) return null
  return {
    monthlyCap: figure(raw.monthlyCap),
    perRunCap: figure(raw.perRunCap),
    perAgentDailyCap: figure(raw.perAgentDailyCap),
    onExhausted: raw.onExhausted === 'sandbox' ? 'sandbox' : 'stop',
    spentThisMonth: count(raw.spentThisMonth),
    remaining: figure(raw.remaining),
    projectedMonthEnd: figure(raw.projectedMonthEnd),
    periodStart: text(raw.periodStart),
  }
}

/** The caps and what has been spent against them this month (budget:read). */
export function useBudget(options: QueryOptions = {}) {
  return useQuery({
    queryKey: ['budget'],
    queryFn: ({ signal }) => api<unknown>('/api/orchestrator/budget', { signal }),
    select: normaliseBudget,
    enabled: options.enabled ?? true,
  })
}

/** Replaces the caps (budget:manage). A cap left out is removed. */
export function useSaveBudget() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: BudgetInput) =>
      normaliseBudget(await api<unknown>('/api/orchestrator/budget', { method: 'PUT', body: input })),
    onSuccess: (saved) => {
      // The answer is the budget as it now stands, so there is nothing to wait for.
      client.setQueryData(['budget'], saved)
      void client.invalidateQueries({ queryKey: ['budget'] })
    },
  })
}

/** The share of the monthly cap that has been used, 0 to 1 and over; null with no monthly cap. */
export function capUsed(budget: Pick<Budget, 'monthlyCap' | 'spentThisMonth'>): number | null {
  if (budget.monthlyCap === null) return null
  if (budget.monthlyCap <= 0) return budget.spentThisMonth > 0 ? Number.POSITIVE_INFINITY : 1
  return budget.spentThisMonth / budget.monthlyCap
}

/** The share of the cap at which Analytics starts to warn. */
export const CAP_WARNING_AT = 0.8

/* ---- Spend ------------------------------------------------------------------------------------------------- */

export type UsageGrouping = 'model' | 'agent' | 'day' | 'provider' | 'outcome'

export type UsageLine = {
  key: string | null
  label: string
  totalTokens: number
  cost: number
  failedAttemptCost: number
  attempts: number
  failedAttempts: number
  skipped: number
}

export type UsageReport = { grouping: string; totals: UsageLine; groups: UsageLine[] }

function usageLine(raw: unknown): UsageLine {
  const row = isRecord(raw) ? raw : {}
  return {
    key: text(row.key),
    label: text(row.label) ?? 'Unknown',
    totalTokens: count(row.totalTokens),
    cost: count(row.cost),
    failedAttemptCost: count(row.failedAttemptCost),
    attempts: count(row.attempts),
    failedAttempts: count(row.failedAttempts),
    skipped: count(row.skipped),
  }
}

export function normaliseUsageReport(raw: unknown): UsageReport | null {
  if (!isRecord(raw) || !Array.isArray(raw.groups)) return null
  return { grouping: text(raw.groupBy) ?? '', totals: usageLine(raw.totals), groups: raw.groups.map(usageLine) }
}

/** What the workspace spent over a range of days, grouped (budget:read). */
export function useUsageReport(
  grouping: UsageGrouping,
  range: { from: string; to: string },
  options: QueryOptions = {},
) {
  return useQuery({
    queryKey: ['usage', grouping, range.from, range.to],
    queryFn: ({ signal }) =>
      api<unknown>(`/api/orchestrator/usage?from=${range.from}&to=${range.to}&groupBy=${grouping}`, { signal }),
    select: normaliseUsageReport,
    enabled: options.enabled ?? true,
    staleTime: 60_000,
  })
}

/** The file name in a Content-Disposition header, when it names one. */
export function fileNameFrom(disposition: string | null): string | null {
  if (!disposition) return null
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (encoded?.[1]) {
    try {
      return decodeURIComponent(encoded[1])
    } catch {
      /* fall through to the plain name */
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(disposition)
  return plain?.[1]?.trim() || null
}

/**
 * Fetches a file with the bearer token, which a plain link cannot send, and hands it to the browser
 * as a download. An expired token is renewed once, as api() does.
 */
export async function downloadAuthorised(
  path: string,
  fallbackName: string,
  failure: { forbidden: string; other: string },
): Promise<string> {
  const send = (token: string | null) =>
    fetch(path, {
      credentials: 'include',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })

  let response = await send(accessToken())
  if (response.status === 401 && accessToken()) {
    if (await refreshAccessToken()) {
      response = await send(accessToken())
    } else {
      clearSession()
      throw new ApiError(401, 'token_expired', 'Your session has ended. Sign in again.', false, {})
    }
  }
  if (!response.ok) {
    throw new ApiError(
      response.status,
      'download_failed',
      response.status === 403 ? failure.forbidden : failure.other,
      response.status >= 500,
      {},
    )
  }
  const blob = await response.blob()
  const name = fileNameFrom(response.headers.get('Content-Disposition')) ?? fallbackName
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = name
  anchor.hidden = true
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  // Revoked on the next turn, so the browser has begun the download before the address goes.
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
  return name
}

/** Downloads every attempt in a range as the CSV the usage report writes. */
export function downloadUsageCsv(range: { from: string; to: string }): Promise<string> {
  return downloadAuthorised(
    `/api/orchestrator/usage.csv?from=${range.from}&to=${range.to}`,
    `usage_${range.from}_to_${range.to}.csv`,
    {
      forbidden: 'You do not have permission to download the spend report.',
      other: 'The spend report could not be downloaded. Try again.',
    },
  )
}

/* ---- Ratings on answers ----------------------------------------------------------------------------------- */

/** One person's vote on one answer. */
export type AnswerRating = { messageId: string; rating: 1 | -1; reason: string | null }

export type ConversationRatings = Record<string, AnswerRating>

export function normaliseConversationRatings(raw: unknown): ConversationRatings {
  const ratings: ConversationRatings = {}
  if (!isRecord(raw)) return ratings
  for (const row of list(raw.ratings).filter(isRecord)) {
    const messageId = text(row.messageId)
    const rating = figure(row.rating)
    if (messageId && (rating === 1 || rating === -1)) ratings[messageId] = { messageId, rating, reason: text(row.reason) }
  }
  return ratings
}

const ratingsKey = (conversationId: string | null) => ['conversation-ratings', conversationId] as const

/** The ratings the signed-in person has given in one conversation, by message id (chat:use). */
export function useConversationRatings(conversationId: string | null, options: QueryOptions = {}) {
  return useQuery({
    queryKey: ratingsKey(conversationId),
    queryFn: ({ signal }) => api<unknown>(`/api/conversations/${conversationId}/feedback`, { signal }),
    select: normaliseConversationRatings,
    enabled: (options.enabled ?? true) && conversationId !== null,
    staleTime: 60_000,
  })
}

/** The longest reason the service keeps. */
export const REASON_MAX = 500

/**
 * Rates an answer, or changes the rating already given (a second vote replaces the first), or
 * withdraws it when `rating` is null. The thread shows the new state at once and goes back to the
 * old one if the service refuses, so a thumb never looks pressed when nothing was saved.
 */
export function useRateAnswer(conversationId: string | null) {
  const client = useQueryClient()
  return useMutation({
    // One at a time within a conversation, so a quick change of mind reaches the service in the
    // order it was made and the last click is the one that is kept.
    scope: { id: `answer-ratings-${conversationId}` },
    mutationFn: async (input: { messageId: string; rating: 1 | -1 | null; reason?: string | null }) => {
      const path = `/api/conversations/${conversationId}/messages/${input.messageId}/feedback`
      if (input.rating === null) {
        await api<void>(path, { method: 'DELETE' })
        return
      }
      await api<unknown>(path, { method: 'POST', body: { rating: input.rating, reason: input.reason ?? null } })
    },
    onMutate: async (input) => {
      const key = ratingsKey(conversationId)
      await client.cancelQueries({ queryKey: key })
      const previous = client.getQueryData<unknown>(key)
      const next = normaliseConversationRatings(previous)
      if (input.rating === null) delete next[input.messageId]
      else next[input.messageId] = { messageId: input.messageId, rating: input.rating, reason: input.reason ?? null }
      // Stored in the shape the service sends, since the query selects from it.
      client.setQueryData(key, { ratings: Object.values(next) })
      return { previous }
    },
    onError: (_error, _input, context) => {
      client.setQueryData(ratingsKey(conversationId), context?.previous)
    },
    onSettled: () => {
      void client.invalidateQueries({ queryKey: ratingsKey(conversationId) })
      void client.invalidateQueries({ queryKey: ['run-ratings'] })
      void client.invalidateQueries({ queryKey: ['insights', 'agents'] })
    },
  })
}

export type RunRating = { messageId: string; userId: string | null; rating: 1 | -1; reason: string | null; updatedAt: string | null }

export function normaliseRunRatings(raw: unknown): RunRating[] {
  if (!isRecord(raw)) return []
  const ratings: RunRating[] = []
  for (const row of list(raw.ratings).filter(isRecord)) {
    const messageId = text(row.messageId)
    const rating = figure(row.rating)
    if (messageId && (rating === 1 || rating === -1)) {
      ratings.push({ messageId, userId: text(row.userId), rating, reason: text(row.reason), updatedAt: text(row.updatedAt) })
    }
  }
  return ratings
}

/** Every rating the answers of one run have, newest change first, with the reasons (run:read). */
export function useRunRatings(runId: string, options: QueryOptions = {}) {
  return useQuery({
    queryKey: ['run-ratings', runId],
    queryFn: ({ signal }) => api<unknown>(`/api/runs/${runId}/feedback`, { signal }),
    select: normaliseRunRatings,
    enabled: options.enabled ?? true,
  })
}
