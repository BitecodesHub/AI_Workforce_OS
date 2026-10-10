// @find: spend breakdown, usage report, cost by model, cost by agent, download usage, export csv, spend report, SpendBreakdown, tokens
// @what: Analytics spend table grouped by model, agent or other grouping, with a download.
// @flow: Rendered on Analytics; uses useUsageReport
import { useMemo, useState } from 'react'
import { Button, Card, DataTable, Eyebrow, Notice } from '../ui'
import type { Column } from '../ui'
import { QueryState } from '../ui/QueryState'
import { describeApiError } from '../../lib/api'
import { formatCount, formatMoney } from '../../lib/format'
import type { InsightsWindow, UsageLine } from '../../lib/insightsQueries'
import { WINDOW_LABEL, downloadUsageCsv, useUsageReport, windowRange } from '../../lib/insightsQueries'
import { can } from '../../lib/session'
import { useToast } from '../../lib/toast'

/*
 * Where the money went over the window: by model and by agent, with the cost of the attempts that
 * failed (a provider bills for those too) and a file for finance.
 *
 * Needs budget:read, the permission the usage report itself needs, so a role that cannot read the
 * budget sees a sentence rather than a request that fails. Every amount is an estimate at the
 * catalogue's prices; the file says so too.
 */

function columnsFor(first: string): Column<UsageLine>[] {
  return [
    { key: 'name', header: first, sortValue: (line) => line.label, render: (line) => line.label },
    {
      key: 'cost',
      header: 'Cost',
      numeric: true,
      sortValue: (line) => line.cost,
      render: (line) => formatMoney(line.cost),
    },
    {
      key: 'failedCost',
      header: 'Of it, failed attempts',
      numeric: true,
      sortValue: (line) => line.failedAttemptCost,
      render: (line) => formatMoney(line.failedAttemptCost),
    },
    {
      key: 'attempts',
      header: 'Attempts',
      numeric: true,
      sortValue: (line) => line.attempts,
      render: (line) => formatCount(line.attempts),
    },
    {
      key: 'tokens',
      header: 'Tokens',
      numeric: true,
      sortValue: (line) => line.totalTokens,
      render: (line) => formatCount(line.totalTokens),
    },
  ]
}

function Breakdown({ title, first, grouping, range }: { title: string; first: string; grouping: 'model' | 'agent'; range: { from: string; to: string } }) {
  const report = useUsageReport(grouping, range)
  const columns = useMemo(() => columnsFor(first), [first])
  return (
    <div>
      <h3 className="section-heading" style={{ marginBottom: 'var(--space-3)' }}>
        {title}
      </h3>
      <QueryState query={report} permission="budget:read" what={`spend ${title.toLowerCase()}`} rows={3}>
        {(loaded) =>
          !loaded || loaded.groups.length === 0 ? (
            <p className="muted">No spend was recorded in this window.</p>
          ) : (
            <DataTable
              columns={columns}
              rows={loaded.groups}
              getKey={(line) => line.key ?? line.label}
              caption={`Estimated spend ${title.toLowerCase()}, from the usage record, failed attempts included.`}
            />
          )
        }
      </QueryState>
    </div>
  )
}

// @find: SpendBreakdown, usage report, spend by model, download usage csv
export function SpendBreakdown({ window }: { window: InsightsWindow }) {
  const canRead = can('budget:read')
  const toast = useToast()
  const range = useMemo(() => windowRange(window), [window])
  // The same request as the by-model table's, so it is made once and the totals come with it.
  const totals = useUsageReport('model', range, { enabled: canRead })
  const [downloading, setDownloading] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)

  const download = async () => {
    setDownloading(true)
    setFailure(null)
    try {
      const name = await downloadUsageCsv(range)
      toast.success(`Downloaded ${name}.`)
    } catch (error) {
      setFailure(describeApiError(error))
    } finally {
      setDownloading(false)
    }
  }

  return (
    <Card as="section">
      <Eyebrow as="h2">Where the money went</Eyebrow>
      {!canRead ? (
        <p className="muted">The spend breakdown is shown to people who can view the budget. Ask an administrator.</p>
      ) : (
        <div className="stack" style={{ gap: 'var(--space-5)' }}>
          <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-4)', flexWrap: 'wrap' }}>
            <p style={{ margin: 0 }}>
              {totals.data
                ? `${formatMoney(totals.data.totals.cost)} over ${WINDOW_LABEL[window].toLowerCase()}, ${formatMoney(totals.data.totals.failedAttemptCost)} of it on attempts that failed.`
                : `Spend over the ${WINDOW_LABEL[window].toLowerCase()}.`}
            </p>
            <Button variant="outline" loading={downloading} onClick={() => void download()}>
              Download CSV
            </Button>
          </div>
          {failure && <Notice tone="warning" live>{failure}</Notice>}
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 340px), 1fr))',
              gap: 'var(--space-6)',
            }}
          >
            <Breakdown title="By model" first="Model" grouping="model" range={range} />
            <Breakdown title="By agent" first="Agent" grouping="agent" range={range} />
          </div>
          <p className="caption" style={{ margin: 0 }}>
            Estimated in US dollars at catalogue prices, not the provider&apos;s invoice. The file lists every
            attempt in the window, one row each, failed and skipped ones included.
          </p>
        </div>
      )}
    </Card>
  )
}
