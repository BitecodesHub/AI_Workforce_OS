import { Card, DataTable, EmptyState, Eyebrow, Notice, PageHeader, StatRow, StatTile, Tag } from '../components/ui'
import type { Column } from '../components/ui'
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
import { useAnalytics } from '../lib/queries'
import type { ActionCount, AnalyticsSummary, OutcomeCount } from '../lib/queries'

/*
 * Analytics.
 *
 * Derived directly from the audit log rather than from a daily rollup table: nothing populates
 * that rollup yet, so reading it would show zeros forever, indistinguishable from a workspace
 * with no activity. Counting the audit projection instead means every figure here reflects rows
 * that actually exist, even before the rollup job is built - see AnalyticsController on the
 * analytics service for how the count is produced.
 */

const OUTCOME_TONE = { succeeded: 'success', failed: 'danger', denied: 'warning' } as const

const ACTION_COLUMNS: Column<ActionCount>[] = [
  { key: 'action', header: 'Action', render: (row) => <span className="mono">{row.action}</span> },
  { key: 'count', header: 'Count', numeric: true, render: (row) => row.count },
]

function outcomeTone(outcome: string) {
  return OUTCOME_TONE[outcome as keyof typeof OUTCOME_TONE] ?? 'neutral'
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}

function Summary({ data }: { data: AnalyticsSummary }) {
  const denied = data.byOutcome.find((row) => row.outcome === 'denied')?.count ?? 0
  const failed = data.byOutcome.find((row) => row.outcome === 'failed')?.count ?? 0

  return (
    <>
      <div style={{ marginTop: 'var(--space-6)' }}>
        <StatRow>
          <StatTile label="Audit entries" value={String(data.totalEvents)} unit="last 30 days" />
          <StatTile label="Distinct actions" value={String(data.byAction.length)} unit="kinds of event" />
          <StatTile label="Denied" value={String(denied)} unit="blocked by policy" />
          <StatTile label="Failed" value={String(failed)} unit="did not succeed" />
        </StatRow>
        <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
          {formatDate(data.windowStart)} to {formatDate(data.windowEnd)}, counted from the
          append-only audit projection.
        </p>
      </div>

      <section style={{ marginTop: 'var(--space-7)' }}>
        <Card as="section">
          <Eyebrow>Outcomes</Eyebrow>
          {data.byOutcome.length === 0 ? (
            <p className="muted">No audit entries in this window.</p>
          ) : (
            <div className="row" style={{ gap: 'var(--space-4)', flexWrap: 'wrap' }}>
              {data.byOutcome.map((row: OutcomeCount) => (
                <Tag key={row.outcome} tone={outcomeTone(row.outcome)} withDot>
                  {row.outcome} · {row.count}
                </Tag>
              ))}
            </div>
          )}
        </Card>
      </section>

      <section style={{ marginTop: 'var(--space-6)' }}>
        <Card as="section">
          <Eyebrow>By action</Eyebrow>
          <DataTable
            columns={ACTION_COLUMNS}
            rows={data.byAction}
            getKey={(row) => row.action}
            caption="Audit entries grouped by action, for the window above."
          />
        </Card>
      </section>
    </>
  )
}

export function Analytics() {
  const analyticsQuery = useAnalytics()

  return (
    <div className="page">
      <PageHeader
        eyebrow="How the workforce is doing"
        title="Analytics"
        description="Activity for this workspace, derived from the audit log."
      />

      <Notice tone="info">
        These figures are counted directly from the audit log, not from a separate rollup. A
        number here is always as many entries as actually exist, never a placeholder.
      </Notice>

      <QueryState
        query={analyticsQuery}
        permission="analytics:read"
        what="the analytics dashboard"
        rows={4}
        isEmpty={(data) => data.totalEvents === 0}
        empty={
          <div style={{ marginTop: 'var(--space-6)' }}>
            <Card>
              <EmptyState
                icon={<EmptyIcon kind="task" />}
                title="No activity yet"
                body="Once agents start running and approvals are decided, activity will appear here."
              />
            </Card>
          </div>
        }
      >
        {(data) => <Summary data={data} />}
      </QueryState>
    </div>
  )
}
