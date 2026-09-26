import { Card, DataTable, EmptyState, Eyebrow, Notice, PageHeader, Tag } from '../components/ui'
import type { Column } from '../components/ui'
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
import { timeAgo } from '../lib/api'
import { useAudit } from '../lib/queries'
import type { AuditEvent } from '../lib/queries'

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

const OUTCOME_TONE = { succeeded: 'success', failed: 'danger', denied: 'warning' } as const
const OUTCOME_LABEL = { succeeded: 'Succeeded', failed: 'Failed', denied: 'Denied' } as const

const COLUMNS: Column<AuditEvent>[] = [
  { key: 'sequence', header: 'Entry', numeric: true, render: (row) => row.sequence },
  { key: 'occurredAt', header: 'Time', render: (row) => <span className="mono">{timeAgo(row.occurredAt)}</span> },
  {
    key: 'actorId',
    header: 'Who',
    // An agent's action always names the person accountable for it. A trail that stops at "the
    // agent did it" cannot answer the only question ever asked of one.
    render: (row) => (
      <div>
        <span>{row.actorId}</span>
        {row.onBehalfOf && <p className="caption">for {row.onBehalfOf}</p>}
      </div>
    ),
  },
  { key: 'action', header: 'Action', render: (row) => <span className="mono">{row.action}</span> },
  {
    key: 'resourceType',
    header: 'Resource',
    render: (row) => (
      <span className="muted">
        {row.resourceType}
        {row.resourceId ? `:${row.resourceId}` : ''}
      </span>
    ),
  },
  {
    key: 'outcome',
    header: 'Outcome',
    render: (row) => <Tag tone={OUTCOME_TONE[row.outcome]}>{OUTCOME_LABEL[row.outcome]}</Tag>,
  },
]

export function AuditLog() {
  const auditQuery = useAudit()

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
          <Eyebrow>Recent entries</Eyebrow>
          <QueryState
            query={auditQuery}
            permission="audit:read"
            what="the audit log"
            rows={6}
            isEmpty={(data) => data.length === 0}
            empty={
              <EmptyState
                icon={<EmptyIcon kind="document" />}
                title="No audit entries yet"
                body="Actions taken in this workspace will appear here as they happen."
              />
            }
          >
            {(entries) => (
              <DataTable
                columns={COLUMNS}
                rows={entries}
                getKey={(row) => row.id}
                caption="Audit entries for this workspace, newest first, from the append-only audit projection."
              />
            )}
          </QueryState>
        </Card>
      </section>
    </div>
  )
}
