import { useCallback, useMemo } from 'react'
import { Button, Card, DataTable, EmptyState, Eyebrow, Notice, PageHeader, Tag, Time } from '../components/ui'
import type { Column } from '../components/ui'
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
// Imported from its own module, not the ../components/ui barrel: this screen is lazy-loaded, and
// the barrel is also part of the main bundle, so going through it created a circular chunk
// dependency (Rollup warned of a "broken execution order").
import { FilterBar, FilterEmpty } from '../components/ui/FilterBar'
import { describeApiError } from '../lib/api'
import { formatCount, sentenceCase, shortId, truncateWords } from '../lib/format'
import { OUTCOME_LABEL, OUTCOME_TONE, auditActionLabel, toolLabel, type AuditOutcome } from '../lib/labels'
import { useAgentNames, useAgents, useAuditPages, useMemberNames, useMembers } from '../lib/queries'
import type { Agent, AuditEvent, Member } from '../lib/queries'
import { can } from '../lib/session'
import { useListFilter } from '../lib/useListFilter'

/*
 * The audit log.
 *
 * Not in the primary navigation, per the brief. It is reached from Settings and from the records
 * that refer to it.
 *
 * Two things make it worth reading. Every entry names the person behind an action even when an
 * agent performed it, and the log is a hash chain, so an entry that was altered after it was
 * written can be detected rather than merely being trusted.
 *
 * Entries arrive a page at a time, newest first. Search and the outcome filter run over the pages
 * loaded so far, and the count under the filters says so while older entries remain.
 */

const OUTCOMES: AuditOutcome[] = ['succeeded', 'failed', 'denied']

const FACETS = { outcome: (row: AuditEvent) => row.outcome }

type Name = { label: string; title?: string }

type Directory = {
  members: Record<string, Member>
  /** True once the member list has loaded, so a miss means a former member rather than "not known yet". */
  membersLoaded: boolean
  agents: Record<string, Agent>
  agentsLoaded: boolean
}

const detailText = (value: unknown): string | undefined =>
  typeof value === 'string' && value.trim() ? value : undefined

function personName(id: string, directory: Directory): Name {
  const member = directory.members[id]
  if (member) return { label: member.displayName, title: member.email }
  // Loaded and still unknown: somebody who has since left the workspace.
  if (directory.membersLoaded) return { label: 'Former member', title: id }
  return { label: shortId(id), title: id }
}

function agentName(id: string, directory: Directory): Name {
  const agent = directory.agents[id]
  if (agent) return { label: agent.name }
  if (directory.agentsLoaded) return { label: 'Former agent', title: id }
  return { label: `Agent ${shortId(id)}`, title: id }
}

function actorKind(row: AuditEvent): string {
  return row.actorKind.toUpperCase().replace(/_/g, '')
}

function actorName(row: AuditEvent, directory: Directory): Name {
  switch (actorKind(row)) {
    case 'SYSTEM':
      return { label: 'The platform' }
    case 'AGENT':
      return agentName(row.actorId, directory)
    case 'APIKEY':
      return { label: `API key ${shortId(row.actorId)}`, title: row.actorId }
    default:
      return personName(row.actorId, directory)
  }
}

/** The run an entry is about: the run itself, or the run an approval decision released or stopped. */
function runOf(row: AuditEvent): string | undefined {
  if (row.resourceType === 'run') return row.resourceId ?? undefined
  if (row.resourceType === 'approval') return detailText(row.detail.runId)
  return undefined
}

function resourceWords(row: AuditEvent): string {
  return sentenceCase(row.resourceType) || 'Unknown'
}

function WhoCell({ row, directory }: { row: AuditEvent; directory: Directory }) {
  const actor = actorName(row, directory)
  const behalf = row.onBehalfOf ? personName(row.onBehalfOf, directory) : null
  const isAgent = actorKind(row) === 'AGENT'
  return (
    <div>
      <span title={actor.title}>{actor.label}</span>
      {/* An agent's action always names the person accountable for it. A trail that stops at
          "the agent did it" cannot answer the only question ever asked of one. */}
      {behalf && (
        <p className="caption">
          {isAgent ? 'Agent acting for ' : 'For '}
          <span title={behalf.title}>{behalf.label}</span>
        </p>
      )}
    </div>
  )
}

function ActionCell({ row }: { row: AuditEvent }) {
  const tool = detailText(row.detail.tool)
  const failure = detailText(row.detail.failureReason)
  return (
    <div>
      <span title={row.action}>{auditActionLabel(row.action, row.detail)}</span>
      {tool && <p className="caption">{toolLabel(tool)}</p>}
      {failure && (
        <p className="caption" title={failure}>
          {truncateWords(failure, 140)}
        </p>
      )}
    </div>
  )
}

function ResourceCell({ row, canReadRuns }: { row: AuditEvent; canReadRuns: boolean }) {
  const runId = runOf(row)
  if (runId && canReadRuns) {
    return (
      <a className="link" href={`/runs/${encodeURIComponent(runId)}`} style={{ whiteSpace: 'nowrap' }}>
        Run trace
      </a>
    )
  }
  return (
    <span className="muted" title={row.resourceId ?? undefined}>
      {resourceWords(row)}
      {row.resourceId ? <span className="mono"> {shortId(row.resourceId)}</span> : null}
    </span>
  )
}

export function AuditLog() {
  const auditQuery = useAuditPages()
  const canReadRuns = can('run:read')

  // Names for the people and agents behind each entry. Both lists come from queries other screens
  // share, so they are usually cached already.
  const canReadMembers = can('member:read')
  const membersQuery = useMembers({ enabled: canReadMembers })
  const members = useMemberNames({ enabled: canReadMembers })
  const canReadAgents = can('agent:read')
  const agentsQuery = useAgents({ enabled: canReadAgents })
  const agents = useAgentNames({ enabled: canReadAgents })

  const directory = useMemo<Directory>(
    () => ({
      members,
      membersLoaded: membersQuery.data !== undefined,
      agents,
      agentsLoaded: agentsQuery.data !== undefined,
    }),
    [members, membersQuery.data, agents, agentsQuery.data],
  )

  const entries = useMemo(() => auditQuery.data?.pages.flat(), [auditQuery.data])

  // Everything a person might type to find an entry: what happened in words (both the specific
  // and the general wording, so a search for "Decided an approval" from Analytics finds approvals
  // and rejections alike), who did it, and what it was about, including a pasted id.
  const searchText = useCallback(
    (row: AuditEvent) => {
      const tool = detailText(row.detail.tool)
      return [
        auditActionLabel(row.action, row.detail),
        auditActionLabel(row.action),
        row.action,
        actorName(row, directory).label,
        row.onBehalfOf ? personName(row.onBehalfOf, directory).label : '',
        runOf(row) && canReadRuns ? 'Run trace' : resourceWords(row),
        row.resourceId ?? '',
        detailText(row.detail.runId) ?? '',
        tool ? toolLabel(tool) : '',
        detailText(row.detail.failureReason) ?? '',
      ].join(' ')
    },
    [directory, canReadRuns],
  )

  const filter = useListFilter({ rows: entries, text: searchText, facets: FACETS })

  const columns = useMemo<Column<AuditEvent>[]>(
    () => [
      { key: 'sequence', header: 'Entry', numeric: true, render: (row) => row.sequence },
      {
        key: 'occurredAt',
        header: 'Time',
        render: (row) => (
          <span style={{ whiteSpace: 'nowrap' }}>
            <Time iso={row.occurredAt} className="muted" />
          </span>
        ),
      },
      { key: 'actorId', header: 'Who', render: (row) => <WhoCell row={row} directory={directory} /> },
      { key: 'action', header: 'Action', render: (row) => <ActionCell row={row} /> },
      { key: 'resourceType', header: 'Resource', render: (row) => <ResourceCell row={row} canReadRuns={canReadRuns} /> },
      {
        key: 'outcome',
        header: 'Outcome',
        render: (row) => <Tag tone={OUTCOME_TONE[row.outcome]}>{OUTCOME_LABEL[row.outcome]}</Tag>,
      },
    ],
    [directory, canReadRuns],
  )

  const moreToLoad = Boolean(auditQuery.hasNextPage)

  return (
    <div className="page admin-audit">
      <PageHeader
        eyebrow="Everything that happened"
        title="Audit log"
        description="Approval decisions and agent runs that completed or failed, with the person accountable for each."
      />

      <Notice tone="info">
        Each entry carries the digest of the one before it, so an entry altered after it was
        written breaks the chain and can be detected.
      </Notice>

      <section style={{ marginTop: 'var(--space-6)' }}>
        <Card as="section">
          <Eyebrow as="h2">Entries, newest first</Eyebrow>
          <QueryState
            // A failure to load an older page keeps the entries already on screen; it is reported
            // beside the button that asked for them instead.
            query={{
              data: entries,
              isLoading: auditQuery.isLoading,
              error: entries ? null : auditQuery.error,
              refetch: auditQuery.refetch,
            }}
            permission="audit:read"
            what="the audit log"
            rows={6}
            isEmpty={(data) => data.length === 0}
            empty={
              <EmptyState
                icon={<EmptyIcon kind="document" />}
                title="No audit entries yet"
                body="Approval decisions and finished runs will appear here as they happen."
              />
            }
          >
            {() => (
              <>
                <FilterBar
                  searchLabel="Search the audit log"
                  placeholder="Action, person, agent or run id"
                  query={filter.query}
                  onQueryChange={filter.setQuery}
                  facets={[
                    {
                      param: 'outcome',
                      label: 'Outcome',
                      options: OUTCOMES.map((outcome) => ({
                        value: outcome,
                        label: OUTCOME_LABEL[outcome],
                        count: filter.counts.outcome?.[outcome] ?? 0,
                      })),
                      selected: filter.selected.outcome ?? [],
                      onToggle: (value) => filter.toggle('outcome', value),
                    },
                  ]}
                  shown={filter.filtered.length}
                  total={filter.total}
                  scopeNote={
                    moreToLoad
                      ? `Filters cover the ${formatCount(filter.total)} most recent entries loaded so far.`
                      : undefined
                  }
                  active={filter.active}
                  onClear={filter.clear}
                />

                {filter.filtered.length === 0 ? (
                  <FilterEmpty onClear={filter.clear} what="audit entries" />
                ) : (
                  <DataTable
                    columns={columns}
                    rows={filter.filtered}
                    getKey={(row) => row.id}
                    caption="Audit entries for this workspace, newest first, from the append-only audit projection."
                  />
                )}

                {auditQuery.isFetchNextPageError && (
                  <div style={{ marginTop: 'var(--space-4)' }}>
                    <Notice tone="warning" live>
                      Older entries could not be loaded. {describeApiError(auditQuery.error)}
                    </Notice>
                  </div>
                )}

                {moreToLoad && (
                  <div className="row" style={{ marginTop: 'var(--space-5)', justifyContent: 'center' }}>
                    <Button
                      variant="outline"
                      onClick={() => void auditQuery.fetchNextPage()}
                      loading={auditQuery.isFetchingNextPage}
                    >
                      Show older entries
                    </Button>
                  </div>
                )}
              </>
            )}
          </QueryState>
        </Card>
      </section>
    </div>
  )
}
