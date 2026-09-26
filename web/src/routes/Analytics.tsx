import { Card, DataTable, Eyebrow, Notice, PageHeader, StatRow, StatTile, Tag } from '../components/ui'
import type { Column } from '../components/ui'

/*
 * Analytics.
 *
 * One accent colour, per the brief, which also suits the content: a chart in six colours implies
 * six things worth comparing, and there are two. The bars are drawn inline rather than pulled
 * from a chart library, because a simple comparison does not need eighty kilobytes of JavaScript.
 *
 * "Hours returned" is an estimate and is labelled as one everywhere it appears. Presenting an
 * estimate as a measurement is how a dashboard becomes a claim nobody can defend in a meeting.
 */

type AgentRow = {
  agent: string
  category: 'operations' | 'engineering' | 'growth' | 'support'
  runs: number
  completed: number
  approvals: number
  tokens: string
  cost: string
}

const BY_AGENT: AgentRow[] = [
  { agent: 'Customer Support', category: 'support', runs: 96, completed: 91, approvals: 14, tokens: '412k', cost: '0.00' },
  { agent: 'HR', category: 'operations', runs: 41, completed: 40, approvals: 9, tokens: '186k', cost: '0.00' },
  { agent: 'Engineering Manager', category: 'engineering', runs: 33, completed: 31, approvals: 0, tokens: '158k', cost: '0.00' },
  { agent: 'Research', category: 'growth', runs: 22, completed: 22, approvals: 2, tokens: '204k', cost: '0.00' },
]

const WEEK = [
  { day: 'Mon', completed: 34, failed: 2 },
  { day: 'Tue', completed: 41, failed: 1 },
  { day: 'Wed', completed: 28, failed: 4 },
  { day: 'Thu', completed: 37, failed: 0 },
  { day: 'Fri', completed: 44, failed: 3 },
]

const PEAK = Math.max(...WEEK.map((entry) => entry.completed + entry.failed))

const COLUMNS: Column<AgentRow>[] = [
  {
    key: 'agent',
    header: 'Agent',
    render: (row) => (
      <Tag tone={row.category} withDot>
        {row.agent}
      </Tag>
    ),
  },
  { key: 'runs', header: 'Runs', numeric: true, render: (row) => row.runs },
  { key: 'completed', header: 'Completed', numeric: true, render: (row) => row.completed },
  { key: 'approvals', header: 'Approvals raised', numeric: true, render: (row) => row.approvals },
  { key: 'tokens', header: 'Tokens', numeric: true, render: (row) => row.tokens },
  {
    key: 'cost',
    header: 'Cost',
    numeric: true,
    render: (row) => (
      <>
        {row.cost} <span className="stat-unit">AUD</span>
      </>
    ),
  },
]

export function Analytics() {
  return (
    <div className="page">
      <PageHeader
        eyebrow="How the workforce is doing"
        title="Analytics"
        description="Activity, spend and outcomes for the seven days to 24 September 2026."
      />

      <Notice tone="info">
        Hours returned is an estimate derived from task duration against a configured baseline. It
        is not measured, and it should not be quoted as though it were.
      </Notice>

      <div style={{ marginTop: 'var(--space-6)' }}>
        <StatRow>
          <StatTile label="Tasks completed" value="184" unit="this week" note="Up from 161 last week" />
          <StatTile label="Completion rate" value="95" unit="per cent" note="9 runs failed or were cancelled" />
          <StatTile label="Hours returned" value="47.5" unit="hours" note="Estimated, not measured" />
          <StatTile label="Model spend" value="0.00" unit="AUD" note="Against a monthly cap of 250.00" />
        </StatRow>
        <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
          Source: daily activity rollups built from run and approval records.
        </p>
      </div>

      <section style={{ marginTop: 'var(--space-7)' }}>
        <Card as="section">
          <Eyebrow>Runs by day</Eyebrow>
          <p className="muted" style={{ marginBottom: 'var(--space-6)' }}>
            Completed in the accent colour, failed in the supporting tone.
          </p>

          <div
            className="row"
            style={{ gap: 'var(--space-5)', alignItems: 'flex-end', height: '160px' }}
            role="img"
            aria-label="Runs completed and failed for each weekday, peaking at 47 on Friday."
          >
            {WEEK.map((entry) => (
              <div key={entry.day} className="stack" style={{ flex: 1, gap: 'var(--space-3)', alignItems: 'center' }}>
                <div
                  className="stack"
                  style={{ width: '100%', justifyContent: 'flex-end', height: '120px', gap: '2px' }}
                >
                  <div
                    style={{
                      height: `${(entry.failed / PEAK) * 120}px`,
                      background: 'var(--chart-secondary)',
                      borderRadius: 'var(--radius-tag) var(--radius-tag) 0 0',
                    }}
                  />
                  <div
                    style={{
                      height: `${(entry.completed / PEAK) * 120}px`,
                      background: 'var(--chart-primary)',
                      borderRadius: entry.failed > 0 ? 0 : 'var(--radius-tag) var(--radius-tag) 0 0',
                    }}
                  />
                </div>
                <span className="caption">{entry.day}</span>
                <span className="caption tabular">{entry.completed + entry.failed}</span>
              </div>
            ))}
          </div>
        </Card>
      </section>

      <section style={{ marginTop: 'var(--space-6)' }}>
        <Card as="section">
          <Eyebrow>By agent</Eyebrow>
          <DataTable
            columns={COLUMNS}
            rows={BY_AGENT}
            getKey={(row) => row.agent}
            caption="Activity per agent for the week, from the daily activity rollups."
          />
        </Card>
      </section>
    </div>
  )
}
