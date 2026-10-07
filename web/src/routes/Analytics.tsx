import { Button, Card, EmptyState, PageHeader } from '../components/ui'
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
import { ActivityLog } from '../components/analytics/ActivityLog'
import { AgentsTable } from '../components/analytics/AgentsTable'
import { BudgetCard } from '../components/analytics/BudgetCard'
import { DailyTrend } from '../components/analytics/DailyTrend'
import { OutcomesCard } from '../components/analytics/OutcomesCard'
import { SpendBreakdown } from '../components/analytics/SpendBreakdown'
import { ValueInputs } from '../components/analytics/ValueInputs'
import { ValueTiles } from '../components/analytics/ValueTiles'
import type { Insights, InsightsWindow } from '../lib/insightsQueries'
import {
  INSIGHTS_WINDOWS,
  WINDOW_LABEL,
  parseWindow,
  useAgentInsights,
  useInsights,
} from '../lib/insightsQueries'
import { useRouter } from '../lib/router'
import { can } from '../lib/session'

/*
 * Analytics: what the workforce got done, what it cost and what it was worth.
 *
 * The figures come from the orchestrator's own records of goals, tasks, runs, approvals, questions
 * and model spend, over a window of 7, 30 or 90 days, each with how it moved against the same
 * length of time before. They follow three rules so none can mislead: a rate counts finished work
 * only and needs a handful of runs, work with no price is called unpriced and never free, and
 * anything worked out from the inputs an administrator typed is labelled as an estimate.
 *
 * Under the figures: the days, each agent, the budget (the Orchestrator's "Spend today" tile lands
 * on it), where the money went with a file for finance, and the two inputs the estimate rests on.
 * The audit log's counts, which this page used to be, are folded away at the bottom for the people
 * who read them for the blocked and failed totals.
 */

function WindowPicker({ window, onChange }: { window: InsightsWindow; onChange: (next: InsightsWindow) => void }) {
  return (
    <div role="group" aria-label="Time window" className="row" style={{ gap: 'var(--space-2)' }}>
      {INSIGHTS_WINDOWS.map((option) => (
        <Button
          key={option}
          variant={option === window ? 'primary' : 'outline'}
          aria-pressed={option === window}
          onClick={() => onChange(option)}
        >
          {WINDOW_LABEL[option].replace('Last ', '')}
        </Button>
      ))}
    </div>
  )
}

function Figures({ insights, window }: { insights: Insights; window: InsightsWindow }) {
  const canReadRuns = can('run:read')
  const agents = useAgentInsights(window, { enabled: canReadRuns })
  const empty = insights.runs.total === 0 && insights.goals.completed + insights.goals.failed === 0

  return (
    <div className="page-sections">
      <div>
        <ValueTiles insights={insights} window={window} />
        <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
          {WINDOW_LABEL[window]}, compared with the {WINDOW_LABEL[window].replace('Last ', '')} before. Goals are
          counted on the day they finished, runs on the day they started. Spend is estimated at catalogue prices.
        </p>
      </div>

      {empty ? (
        <Card>
          <EmptyState
            icon={<EmptyIcon kind="task" />}
            title="No work in this window"
            body="Once agents start running goals, what they got done, what it cost and how it went will appear here. Try a longer window if work was quieter lately."
          />
        </Card>
      ) : (
        <>
          <DailyTrend insights={insights} />
          <OutcomesCard insights={insights} />
        </>
      )}

      {canReadRuns ? (
        <QueryState query={agents} permission="run:read" what="how each agent is doing" rows={4}>
          {(loaded) =>
            loaded ? (
              <AgentsTable
                agents={loaded.agents}
                window={window}
                hourlyRateSet={(insights.value?.hourlyRate ?? loaded.hourlyRate) !== null}
              />
            ) : (
              <Card>
                <p className="muted">How each agent is doing could not be read.</p>
              </Card>
            )
          }
        </QueryState>
      ) : null}
    </div>
  )
}

export function Analytics() {
  const { search, hash, navigate } = useRouter()
  const window = parseWindow(search.get('window'))
  const insights = useInsights(window)

  const choose = (next: InsightsWindow) => navigate(`/analytics?window=${next}${hash}`, { replace: true })

  return (
    <div className="page admin-analytics">
      <PageHeader
        eyebrow="How the workforce is doing"
        title="Analytics"
        description="What your agents got done, what it cost, and what it was worth, over the window you choose."
        action={<WindowPicker window={window} onChange={choose} />}
      />

      <div className="page-sections">
        <QueryState query={insights} permission="analytics:read" what="the analytics dashboard" rows={4}>
          {(loaded) =>
            loaded ? (
              <Figures insights={loaded} window={window} />
            ) : (
              <Card>
                <p className="muted">The analytics could not be read. Try again in a moment.</p>
              </Card>
            )
          }
        </QueryState>

        <BudgetCard />
        <SpendBreakdown window={window} />
        <ValueInputs value={insights.data?.value ?? null} />
        <ActivityLog />
      </div>
    </div>
  )
}
