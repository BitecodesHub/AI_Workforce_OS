import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import {
  Button,
  Card,
  ConfirmDialog,
  EmptyState,
  Eyebrow,
  FilterBar,
  FilterEmpty,
  Notice,
  PageHeader,
  Tag,
  Textarea,
  Time,
} from '../components/ui'
import type { FilterFacet, TagTone } from '../components/ui'
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
import { ApiError, describeApiError } from '../lib/api'
import { actionClassLabel, categoryTone, statusLabel, toolLabel } from '../lib/labels'
import { useAgentNames, useAgents, useApprovals, useDecideApproval, type Agent, type Approval } from '../lib/queries'
import { useRouter } from '../lib/router'
import { useToast } from '../lib/toast'
import { can } from '../lib/session'
import { useListFilter } from '../lib/useListFilter'

const ACTION_TONE: Record<string, TagTone> = { OUTBOUND: 'warning', DESTRUCTIVE: 'danger', WRITE: 'blue' }

/** A search bar earns its place once the queue no longer fits on a screen. */
const FILTER_THRESHOLD = 5

const NOTE_MAX = 1_000

/** The anchor a run trace links to, so /approvals#approval-<id> lands on that card. */
const anchorFor = (approvalId: string) => `approval-${approvalId}`

/** What happened to the run once the decision was made, from DecisionResult.runStatus. */
function runOutcome(runStatus: string | null | undefined): string {
  switch (runStatus?.toLowerCase()) {
    case 'completed':
      return 'The run finished.'
    case 'waiting_approval':
      return 'The run is waiting for another approval.'
    case 'failed':
      return 'The run failed.'
    case 'cancelled':
      return 'The run was stopped.'
    case undefined:
    case '':
      return 'Open the run to see where it stands.'
    default:
      return `Run status: ${statusLabel('run', runStatus).label}.`
  }
}

/** Why a decision did not go through, in words. The queue refreshes after any failure. */
function decisionError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'approval_already_decided') return 'Someone already decided this request.'
    if (error.code === 'approval_expired') return 'This request expired, so the run was stopped.'
    if (error.isPermissionDenied) return 'Deciding this request needs a permission your role does not have.'
  }
  return describeApiError(error)
}

/**
 * The request's summary in words. The gateway writes it as "Send something outside the workspace
 * using gmail.send_message", so the raw tool name is swapped for its label.
 */
function readableSummary(approval: Approval): string {
  const tool = approval.tool?.trim()
  if (!tool || !approval.summary.includes(tool)) return approval.summary
  return approval.summary.split(tool).join(toolLabel(tool))
}

/** The backend guarantees valid JSON; the fallback only covers a payload from before this column was populated. */
function formatPayload(payload: string): string {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2)
  } catch {
    return payload
  }
}

type Decision = {
  id: string
  approved: boolean
  what: string
  agentName: string
  runId: string
  outcome: string
}

function ApprovalCard({
  approval,
  agent,
  agentsKnown,
  onApprove,
  onReject,
  canDecide,
  isApproving,
  isRejecting,
}: {
  approval: Approval
  agent: Agent | undefined
  /** Until the agent list has arrived, a missing name is not yet an unknown agent. */
  agentsKnown: boolean
  onApprove: () => void
  onReject: () => void
  canDecide: boolean
  isApproving: boolean
  isRejecting: boolean
}) {
  const actionClass = approval.actionClass.toUpperCase()

  return (
    // The id lets a run trace link straight to this card; tabIndex lets the page move focus here
    // when it arrives by that link.
    <div id={anchorFor(approval.id)} tabIndex={-1} style={{ scrollMarginTop: 'var(--space-7)' }}>
      <Card as="article">
        <Eyebrow>{toolLabel(approval.tool)}</Eyebrow>

        <div
          className="row"
          style={{
            justifyContent: 'space-between',
            alignItems: 'flex-start',
            flexWrap: 'wrap',
            gap: 'var(--space-3) var(--space-4)',
            marginBottom: 'var(--space-4)',
          }}
        >
          <h2 className="section-heading" style={{ fontSize: '15px' }}>
            {readableSummary(approval)}
          </h2>
          <Tag tone={ACTION_TONE[actionClass] ?? 'neutral'}>{actionClassLabel(approval.actionClass)}</Tag>
        </div>

        <div
          className="row"
          style={{ flexWrap: 'wrap', gap: 'var(--space-2) var(--space-4)', marginBottom: 'var(--space-5)' }}
        >
          {(agent || agentsKnown) && (
            <Tag tone={categoryTone(agent?.category)} withDot>
              {agent?.name ?? 'Unknown agent'}
            </Tag>
          )}
          <span className="caption">
            Requested <Time iso={approval.requestedAt} />
          </span>
          <span className="caption">
            Expires <Time iso={approval.expiresAt} />
          </span>
        </div>

        <div
          style={{
            background: 'var(--paper)',
            border: '1px solid var(--line)',
            borderRadius: 'var(--radius-control-lg)',
            padding: 'var(--space-5)',
            marginBottom: 'var(--space-5)',
            color: 'var(--ink)',
            lineHeight: 1.6,
          }}
        >
          <p className="caption muted" style={{ marginBottom: 'var(--space-2)' }}>
            Exactly what will be sent
          </p>
          <pre
            style={{
              margin: 0,
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
              fontFamily: 'var(--font-mono)',
              fontSize: '12px',
            }}
          >
            {formatPayload(approval.payload)}
          </pre>
        </div>

        <div className="row" style={{ flexWrap: 'wrap', gap: 'var(--space-3)' }}>
          {canDecide && (
            <>
              <Button onClick={onApprove} loading={isApproving} disabled={isRejecting}>
                Approve
              </Button>
              <Button variant="outline" onClick={onReject} loading={isRejecting} disabled={isApproving}>
                Reject
              </Button>
            </>
          )}
          <a className="button button-outline" href={`/runs/${approval.runId}`}>
            View the run
          </a>
        </div>
      </Card>
    </div>
  )
}

function DecidedList({ decisions }: { decisions: Decision[] }) {
  const headingId = useId()
  return (
    <section aria-labelledby={headingId} style={{ marginTop: 'var(--space-7)' }}>
      <Card>
        <Eyebrow as="h2" id={headingId}>
          Decided in this visit
        </Eyebrow>
        <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
          Only what was decided on this page since you opened it. The audit log keeps every decision.
        </p>
        <ul className="stack" style={{ gap: 'var(--space-4)', listStyle: 'none', margin: 0, padding: 0 }}>
          {decisions.map((decision) => (
            <li
              key={decision.id}
              className="row"
              style={{ flexWrap: 'wrap', gap: 'var(--space-2) var(--space-4)' }}
            >
              <Tag tone={decision.approved ? 'success' : 'danger'}>{decision.approved ? 'Approved' : 'Rejected'}</Tag>
              <span>{decision.what}</span>
              <span className="caption">{decision.agentName}</span>
              <span className="muted">{decision.outcome}</span>
              <a className="link" href={`/runs/${decision.runId}`}>
                View the run
              </a>
            </li>
          ))}
        </ul>
      </Card>
    </section>
  )
}

export function Approvals() {
  const toast = useToast()
  const { hash } = useRouter()
  const approvalsQuery = useApprovals()
  const agentNames = useAgentNames()
  const agentsKnown = useAgents().data !== undefined
  const decideApproval = useDecideApproval()
  const canRead = can('approval:read')
  const canDecide = can('approval:decide')
  const canReadAudit = can('audit:read')

  // Approving resumes the run before the request returns, so several approvals can be in flight
  // at once; each card keeps its own busy state until its own request settles.
  const [approvingIds, setApprovingIds] = useState<ReadonlySet<string>>(() => new Set())
  const [rejectingId, setRejectingId] = useState<string | null>(null)
  const [rejectTarget, setRejectTarget] = useState<Approval | null>(null)
  // The note belongs to one request: it survives a failed attempt and a reopened dialog for the
  // same request, and starts empty for a different one.
  const [note, setNote] = useState({ approvalId: '', text: '' })
  const [rejectError, setRejectError] = useState<string | null>(null)
  // What was decided here, newest first. Component state on purpose: it is a receipt for this
  // visit, not a history, and it clears when the person leaves the page.
  const [decisions, setDecisions] = useState<Decision[]>([])

  // A request decided here leaves the queue at once, rather than staying clickable until the
  // refreshed queue arrives (a second click would read "Someone already decided this request").
  const pending = useMemo(() => {
    const decided = new Set(decisions.map((decision) => decision.id))
    return approvalsQuery.data?.filter((approval) => approval.status === 'pending' && !decided.has(approval.id))
  }, [approvalsQuery.data, decisions])

  const searchText = useCallback(
    (approval: Approval) =>
      [
        readableSummary(approval),
        toolLabel(approval.tool),
        actionClassLabel(approval.actionClass),
        agentNames[approval.agentId]?.name ?? '',
      ].join(' '),
    [agentNames],
  )
  const facets = useMemo(() => ({ agent: (approval: Approval) => approval.agentId }), [])
  const filter = useListFilter({ rows: pending, text: searchText, facets })

  // A link from a run trace (/approvals#approval-<id>) lands on its card once the queue has
  // loaded, however long that took, and only once per link.
  const target = hash.startsWith('#approval-') ? hash.slice(1) : null
  const landedOn = useRef<string | null>(null)
  useEffect(() => {
    if (!target || !pending || landedOn.current === target) return
    const card = document.getElementById(target)
    if (!card) return
    landedOn.current = target
    card.scrollIntoView?.({ block: 'start' })
    card.focus({ preventScroll: true })
  }, [target, pending])
  // Not after the person decided it here: the receipt below already says what happened.
  const targetGone =
    target !== null &&
    pending !== undefined &&
    !pending.some((approval) => anchorFor(approval.id) === target) &&
    !decisions.some((decision) => anchorFor(decision.id) === target)

  const decisionFacts = (approval: Approval) => ({
    what: toolLabel(approval.tool),
    agentName: agentNames[approval.agentId]?.name ?? 'Unknown agent',
    runId: approval.runId,
  })

  const setApproving = (id: string, busy: boolean) =>
    setApprovingIds((current) => {
      const next = new Set(current)
      if (busy) next.add(id)
      else next.delete(id)
      return next
    })

  const handleApprove = async (approval: Approval) => {
    if (approvingIds.has(approval.id)) return
    setApproving(approval.id, true)
    try {
      // Approving resumes the run before the request returns, so this can take a while.
      const result = await decideApproval.mutateAsync({ id: approval.id, approved: true })
      const outcome = runOutcome(result.runStatus)
      setDecisions((current) => [{ id: approval.id, approved: true, outcome, ...decisionFacts(approval) }, ...current])
      toast.success(`Approved. ${outcome}`)
    } catch (error) {
      toast.error(decisionError(error))
    } finally {
      setApproving(approval.id, false)
    }
  }

  const openReject = (approval: Approval) => {
    if (note.approvalId !== approval.id) setNote({ approvalId: approval.id, text: '' })
    setRejectError(null)
    setRejectTarget(approval)
  }

  const confirmReject = async () => {
    const approval = rejectTarget
    if (!approval) return
    setRejectError(null)
    setRejectingId(approval.id)
    try {
      const text = note.approvalId === approval.id ? note.text.trim() : ''
      const result = await decideApproval.mutateAsync({
        id: approval.id,
        approved: false,
        ...(text ? { note: text } : {}),
      })
      const outcome = runOutcome(result.runStatus)
      setDecisions((current) => [{ id: approval.id, approved: false, outcome, ...decisionFacts(approval) }, ...current])
      toast.success(`Rejected. ${outcome}`)
      setRejectTarget(null)
      setNote({ approvalId: '', text: '' })
    } catch (error) {
      // The dialog stays open with the reason, and the note is kept for another try.
      setRejectError(decisionError(error))
    } finally {
      setRejectingId(null)
    }
  }

  return (
    <div className="page">
      <PageHeader
        eyebrow="Waiting on a decision"
        title="Approvals"
        description="Agents stop here before doing anything that leaves the workspace or cannot be undone."
      />

      {/* A role that cannot read the queue gets only the permission panel below, not notices
          about requests it cannot see. */}
      <div className="stack" style={{ gap: 'var(--space-3)' }}>
        {canRead && (
          <Notice tone="info">
            A request nobody decides by its deadline expires, and its run stops. Nothing is sent because a deadline
            passed.
          </Notice>
        )}
        {canRead && !canDecide && (
          <Notice tone="info">
            Your role can see these requests but cannot decide them. Someone whose role includes deciding approvals
            can approve or reject them.{' '}
            <a className="link" href="/profile">
              See what your role allows
            </a>
          </Notice>
        )}
        {targetGone && (
          <Notice tone="warning">That request is no longer waiting. It was decided, withdrawn or expired.</Notice>
        )}
      </div>

      <QueryState
        query={approvalsQuery}
        permission="approval:read"
        what="the approvals queue"
        rows={4}
        isEmpty={() => (pending?.length ?? 0) === 0}
        empty={
          <div style={{ marginTop: 'var(--space-7)' }}>
            <Card>
              <EmptyState
                icon={<EmptyIcon kind="inbox" />}
                title="No approvals waiting"
                body="When an agent needs a person to approve an action, such as sending an email, the request appears here."
                action={
                  canReadAudit ? (
                    <a className="button button-outline" href="/audit">
                      Open the audit log
                    </a>
                  ) : undefined
                }
              />
            </Card>
          </div>
        }
      >
        {() => {
          const queue = pending ?? []
          const filtering = queue.length > FILTER_THRESHOLD || filter.active
          const shown = filtering ? filter.filtered : queue
          const agentCounts = filter.counts.agent ?? {}
          const selectedAgents = filter.selected.agent ?? []
          const agentIds = [...new Set(queue.map((approval) => approval.agentId))]
          const agentFacet: FilterFacet = {
            param: 'agent',
            label: 'Agent',
            options: agentIds
              .map((agentId) => ({
                value: agentId,
                label: agentNames[agentId]?.name ?? 'Unknown agent',
                count: agentCounts[agentId] ?? 0,
              }))
              .sort((a, b) => a.label.localeCompare(b.label)),
            selected: selectedAgents,
            onToggle: (value) => filter.toggle('agent', value),
          }
          return (
            <div style={{ marginTop: 'var(--space-7)' }}>
              {filtering && (
                <FilterBar
                  searchLabel="Search approvals"
                  query={filter.query}
                  onQueryChange={filter.setQuery}
                  placeholder="Action, tool or agent"
                  facets={agentIds.length > 1 ? [agentFacet] : []}
                  shown={shown.length}
                  total={queue.length}
                  active={filter.active}
                  onClear={filter.clear}
                />
              )}
              {shown.length === 0 ? (
                <Card>
                  <FilterEmpty onClear={filter.clear} what="approvals" />
                </Card>
              ) : (
                <div className="stack" style={{ gap: 'var(--space-5)' }}>
                  {shown.map((approval) => (
                    <ApprovalCard
                      key={approval.id}
                      approval={approval}
                      agent={agentNames[approval.agentId]}
                      agentsKnown={agentsKnown}
                      onApprove={() => void handleApprove(approval)}
                      onReject={() => openReject(approval)}
                      canDecide={canDecide}
                      isApproving={approvingIds.has(approval.id)}
                      isRejecting={rejectingId === approval.id}
                    />
                  ))}
                </div>
              )}
            </div>
          )
        }}
      </QueryState>

      {decisions.length > 0 && <DecidedList decisions={decisions} />}

      {canDecide && (
        <ConfirmDialog
          open={rejectTarget !== null}
          onClose={() => setRejectTarget(null)}
          onConfirm={confirmReject}
          eyebrow="Reject request"
          title="Reject this action?"
          description="The agent does not take this action, and its run stops. Your note is saved with the decision."
          confirmLabel="Reject"
          cancelLabel="Go back"
          tone="danger"
          loading={rejectingId !== null}
          error={rejectError}
        >
          <Textarea
            label="Note"
            optional
            value={note.text}
            onChange={(e) =>
              setNote({ approvalId: rejectTarget?.id ?? '', text: e.target.value.slice(0, NOTE_MAX) })
            }
            placeholder="Why is this being rejected?"
            maxLength={NOTE_MAX}
            rows={4}
            // The note is stored on the approval only: the agent never reads it, and neither the
            // audit log nor any screen shows it yet, so the hint promises no reader.
            hint="Kept on record with the decision. The agent does not see it. Up to 1,000 characters."
          />
        </ConfirmDialog>
      )}
    </div>
  )
}
