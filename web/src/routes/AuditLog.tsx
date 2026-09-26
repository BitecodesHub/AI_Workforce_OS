import { Card, DataTable, Eyebrow, Notice, PageHeader, Tag } from '../components/ui'
import type { Column } from '../components/ui'

/*
 * The audit log.
 *
 * Not in the primary navigation, per the brief. It is reached from Settings and from the records
 * that refer to it.
 *
 * Two things make it worth reading. Every entry names the person behind an action even when an
 * agent performed it, and the log is a hash chain, so an entry that was altered after it was
 * written can be detected rather than merely being trusted.
 */

type AuditRow = {
  id: string
  sequence: number
  actor: string
  onBehalfOf?: string
  action: string
  resource: string
  outcome: 'succeeded' | 'failed' | 'denied'
  at: string
}

const ENTRIES: AuditRow[] = [
  {
    id: 'a1',
    sequence: 4821,
    actor: 'Customer Support agent',
    onBehalfOf: 'Priya Shah',
    action: 'tool.invoke',
    resource: 'gmail.send_message',
    outcome: 'denied',
    at: '09:41:22',
  },
  {
    id: 'a2',
    sequence: 4820,
    actor: 'Priya Shah',
    action: 'approval.decide',
    resource: 'apr_01J9A',
    outcome: 'succeeded',
    at: '09:41:20',
  },
  {
    id: 'a3',
    sequence: 4819,
    actor: 'HR agent',
    onBehalfOf: 'Yash Doshi',
    action: 'tool.invoke',
    resource: 'calendar.create_event',
    outcome: 'succeeded',
    at: '09:38:04',
  },
  {
    id: 'a4',
    sequence: 4818,
    actor: 'Aum Parmar',
    action: 'role.update',
    resource: 'manager',
    outcome: 'succeeded',
    at: '09:12:55',
  },
  {
    id: 'a5',
    sequence: 4817,
    actor: 'Fahim Saiyad',
    action: 'credential.store',
    resource: 'provider:openrouter',
    outcome: 'succeeded',
    at: '08:59:31',
  },
]

const OUTCOME_TONE = { succeeded: 'success', failed: 'danger', denied: 'warning' } as const
const OUTCOME_LABEL = { succeeded: 'Succeeded', failed: 'Failed', denied: 'Denied' } as const

const COLUMNS: Column<AuditRow>[] = [
  { key: 'sequence', header: 'Entry', numeric: true, render: (row) => row.sequence },
  { key: 'at', header: 'Time', render: (row) => <span className="mono">{row.at}</span> },
  {
    key: 'actor',
    header: 'Who',
    // An agent's action always names the person accountable for it. A trail that stops at "the
    // agent did it" cannot answer the only question ever asked of one.
    render: (row) => (
      <div>
        <span>{row.actor}</span>
        {row.onBehalfOf && <p className="caption">for {row.onBehalfOf}</p>}
      </div>
    ),
  },
  { key: 'action', header: 'Action', render: (row) => <span className="mono">{row.action}</span> },
  { key: 'resource', header: 'Resource', render: (row) => <span className="muted">{row.resource}</span> },
  {
    key: 'outcome',
    header: 'Outcome',
    render: (row) => <Tag tone={OUTCOME_TONE[row.outcome]}>{OUTCOME_LABEL[row.outcome]}</Tag>,
  },
]

export function AuditLog() {
  return (
    <div className="page">
      <PageHeader
        eyebrow="Everything that happened"
        title="Audit log"
        description="Every decision, tool call and configuration change, with the person accountable for it."
      />

      <Notice tone="info">
        Each entry carries the digest of the one before it, so an entry altered after it was
        written breaks the chain and can be detected.
      </Notice>

      <section style={{ marginTop: 'var(--space-6)' }}>
        <Card as="section">
          <Eyebrow>Today</Eyebrow>
          <DataTable
            columns={COLUMNS}
            rows={ENTRIES}
            getKey={(row) => row.id}
            caption="Audit entries for 24 September 2026, newest first, from the append-only audit projection."
          />
        </Card>
      </section>
    </div>
  )
}
