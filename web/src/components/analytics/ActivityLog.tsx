import { useMemo } from 'react'
import { Card, DataTable, EmptyState, Eyebrow, Notice, StatRow, StatTile, Tag } from '../ui'
import type { Column, TagTone } from '../ui'
import { Collapsible, useCollapsed } from '../ui/Collapsible'
import { EmptyIcon, QueryState } from '../ui/QueryState'
import { formatCount, formatDate, sentenceCase } from '../../lib/format'
import { OUTCOME_LABEL, OUTCOME_TONE, auditActionLabel, type AuditOutcome } from '../../lib/labels'
import { useAnalytics } from '../../lib/queries'
import type { ActionCount, AnalyticsSummary, OutcomeCount } from '../../lib/queries'
import { can } from '../../lib/session'

/*
 * The audit log, counted: how many entries of each kind and outcome in the last 30 days.
 *
 * It is the page's security view, kept for the people who read it for the blocked and failed
 * counts, and folded away because it says nothing about work done, which is what the rest of the
 * page is for. It is counted directly from the audit projection, so a figure here is always as many
 * entries as exist. Where the viewer can read the audit log, an action or an outcome links to the
 * matching entries there.
 *
 * "Blocked or rejected" is the audit's denied outcome: a policy refusing a call, and an approval
 * that expired without a decision, which the default rule turns down.
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
    <div className="stack" style={{ gap: 'var(--space-5)' }}>
      <div>
        <StatRow>
          <StatTile label="Audit entries" value={formatCount(data.totalEvents)} unit="last 30 days" />
          <StatTile label="Distinct actions" value={formatCount(data.byAction.length)} unit="kinds of event" />
          <StatTile
            label="Blocked or rejected"
            value={formatCount(denied)}
            unit="policy refusals and expired approvals"
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
          {formatDate(data.windowStart)} to {formatDate(data.windowEnd)}, counted from the append-only audit
          projection.
        </p>
      </div>

      <section>
        <Eyebrow as="h3">Outcomes</Eyebrow>
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
      </section>

      <section>
        <Eyebrow as="h3">By action</Eyebrow>
        <DataTable
          columns={actionColumns}
          rows={data.byAction}
          getKey={(row) => row.action}
          caption="Audit entries grouped by action, for the window above."
        />
      </section>
    </div>
  )
}

function ActivityLogBody() {
  const analytics = useAnalytics()
  return (
    <QueryState
      query={analytics}
      permission="analytics:read"
      what="the activity log"
      rows={3}
      isEmpty={(data) => data.totalEvents === 0}
      empty={
        <EmptyState
          icon={<EmptyIcon kind="task" />}
          title="No activity yet"
          body="Once agents start running and approvals are decided, activity will appear here."
          titleAs="h3"
        />
      }
    >
      {(data) => <Summary data={data} />}
    </QueryState>
  )
}

export function ActivityLog() {
  const [open, toggle] = useCollapsed('analytics.activity-log', false)
  return (
    <Card as="section">
      <Collapsible
        title="Activity log"
        summary="Audit entries by action and outcome, with the blocked and failed counts"
        open={open}
        onToggle={toggle}
        headingLevel="h2"
      >
        <div style={{ marginTop: 'var(--space-4)' }}>
          <Notice tone="info">
            These figures are counted directly from the audit log, not from a separate rollup. They count entries,
            not work done: the figures above are what the agents did.
          </Notice>
          <div style={{ marginTop: 'var(--space-5)' }}>{open && <ActivityLogBody />}</div>
        </div>
      </Collapsible>
    </Card>
  )
}
