import { useMemo } from 'react'
import { Card, DataTable, EmptyState, Eyebrow, Notice, PageHeader, StatRow, StatTile, Tag } from '../components/ui'
import type { Column, TagTone } from '../components/ui'
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
import { formatCount, formatDate, sentenceCase } from '../lib/format'
import { OUTCOME_LABEL, OUTCOME_TONE, auditActionLabel, type AuditOutcome } from '../lib/labels'
import { useAnalytics } from '../lib/queries'
import type { ActionCount, AnalyticsSummary, OutcomeCount } from '../lib/queries'
import { can } from '../lib/session'

/*
 * Analytics.
 *
 * Derived directly from the audit log rather than from a daily rollup table: nothing populates
 * that rollup yet, so reading it would show zeros forever, indistinguishable from a workspace
 * with no activity. Counting the audit projection instead means every figure here reflects rows
 * that actually exist, even before the rollup job is built - see AnalyticsController on the
 * analytics service for how the count is produced.
 *
 * Where the viewer can read the audit log, an action or outcome links to the matching entries
 * there. The audit log filters only the entries it has loaded, and says so, so a 30-day count
 * here can be larger than the matches shown there.
 */

const isAuditOutcome = (value: string): value is AuditOutcome => Object.hasOwn(OUTCOME_LABEL, value)

function outcomeWords(outcome: string): { tone: TagTone; label: string } {
  return isAuditOutcome(outcome)
    ? { tone: OUTCOME_TONE[outcome], label: OUTCOME_LABEL[outcome] }
    : { tone: 'neutral', label: sentenceCase(outcome) || 'Unknown' }
}

/** The audit log filtered to one action, found by the same words this table shows. */
const auditSearchHref = (label: string) => `/audit?q=${encodeURIComponent(label)}`

function Summary({ data }: { data: AnalyticsSummary }) {
  const canReadAudit = can('audit:read')
  const denied = data.byOutcome.find((row) => row.outcome === 'denied')?.count ?? 0
  const failed = data.byOutcome.find((row) => row.outcome === 'failed')?.count ?? 0

  const actionColumns = useMemo<Column<ActionCount>[]>(
    () => [
      {
        key: 'action',
        header: 'Action',
        sortValue: (row) => auditActionLabel(row.action),
        render: (row) => {
          const label = auditActionLabel(row.action)
          return canReadAudit ? (
            <a className="link" href={auditSearchHref(label)} title={row.action}>
              {label}
            </a>
          ) : (
            <span title={row.action}>{label}</span>
          )
        },
      },
      {
        key: 'count',
        header: 'Entries',
        numeric: true,
        sortValue: (row) => row.count,
        render: (row) => formatCount(row.count),
      },
    ],
    [canReadAudit],
  )

  return (
    <>
      <div style={{ marginTop: 'var(--space-6)' }}>
        <StatRow>
          <StatTile label="Audit entries" value={formatCount(data.totalEvents)} unit="last 30 days" />
          <StatTile label="Distinct actions" value={formatCount(data.byAction.length)} unit="kinds of event" />
          <StatTile
            label="Denied"
            value={formatCount(denied)}
            unit="blocked by policy"
            href={canReadAudit && denied > 0 ? '/audit?outcome=denied' : undefined}
          />
          <StatTile
            label="Failed"
            value={formatCount(failed)}
            unit="did not succeed"
            href={canReadAudit && failed > 0 ? '/audit?outcome=failed' : undefined}
          />
        </StatRow>
        <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
          {formatDate(data.windowStart)} to {formatDate(data.windowEnd)}, counted from the
          append-only audit projection.
        </p>
      </div>

      <section style={{ marginTop: 'var(--space-7)' }}>
        <Card as="section">
          <Eyebrow as="h2">Outcomes</Eyebrow>
          {data.byOutcome.length === 0 ? (
            <p className="muted">No audit entries in this window.</p>
          ) : (
            <div className="row" style={{ gap: 'var(--space-4)', flexWrap: 'wrap' }}>
              {data.byOutcome.map((row: OutcomeCount) => {
                const words = outcomeWords(row.outcome)
                return (
                  <Tag key={row.outcome} tone={words.tone} withDot>
                    {words.label} · {formatCount(row.count)}
                  </Tag>
                )
              })}
            </div>
          )}
        </Card>
      </section>

      <section style={{ marginTop: 'var(--space-6)' }}>
        <Card as="section">
          <Eyebrow as="h2">By action</Eyebrow>
          <DataTable
            columns={actionColumns}
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
    <div className="page admin-analytics">
      <PageHeader
        eyebrow="How the workforce is doing"
        title="Analytics"
        description="Activity for this workspace over the last 30 days, derived from the audit log."
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
