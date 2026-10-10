// @find: approvals, approve, reject, decide request, pending queue, waiting for approval, decided history, human in the loop, questions waiting, approval expires, who asked, email approval, tool call payload, /approvals, Approvals page
// @what: The Approvals page: where people approve or reject what an assistant wants to do (such as send an email) and answer assistant questions, plus the history of decisions.
// @flow: Routed from App.tsx at /approvals; decisions go to the approvals API in lib/queries and resume the run
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Button,
  Card,
  ConfirmDialog,
  EmptyState,
  Eyebrow,
  Notice,
  PageHeader,
  Textarea,
} from '../components/ui'
import type { FilterFacet, FilterSelect } from '../components/ui'
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
// Imported from its own module, not the ../components/ui barrel: this screen is lazy-loaded, and
// the barrel is also part of the main bundle, so going through it created a circular chunk
// dependency (Rollup warned of a "broken execution order").
import { FilterBar, FilterEmpty } from '../components/ui/FilterBar'
import { ApprovalCard, DecidedCard } from '../components/approvals/ApprovalCard'
import { BulkDialog, SimilarRequests } from '../components/approvals/BulkDecision'
import type { BulkTarget } from '../components/approvals/BulkDecision'
import { QuestionCard } from '../components/run/QuestionCard'
import { NOTE_MAX, approvalAnchor, bulkSummary, decisionError, groupIdentical, readableSummary, runOutcome } from '../lib/approvals'
import {
  flattenApprovalPages,
  useApproval,
  useApprovalCount,
  useDecideApprovals,
  useDecidedApprovalPages,
  useMemberNamer,
  usePendingApprovalPages,
} from '../lib/approvalQueries'
import type { ApprovalItem } from '../lib/approvalQueries'
import { actionClassLabel, statusLabel, toolLabel } from '../lib/labels'
import { useAgentNames, useAgents, useDecideApproval, useQuestions } from '../lib/queries'
import { useRouter } from '../lib/router'
import { useToast } from '../lib/toast'
import { can } from '../lib/session'
import { useListFilter } from '../lib/useListFilter'

/** A search bar earns its place once the queue no longer fits on a screen. */
const FILTER_THRESHOLD = 5

type Tab = 'pending' | 'decided'

/** What a person chooses to look at in the history, as the server's statuses. */
const OUTCOMES: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'approved', label: 'Approved' },
  { value: 'rejected', label: 'Rejected' },
  { value: 'expired', label: 'Expired' },
  { value: 'cancelled', label: 'Cancelled' },
]

/** Where focus goes when nothing is left to move it to: the control that brought the person here. */
const TAB_ID = 'approvals-tab-pending'

/**
 * The card to move focus to once `ids` leave the queue: the next one down, else the one above.
 * Read before the cards go, from where they are on the page, so a keyboard user who decides one
 * request lands on the next rather than at the top of the document.
 */
function neighbourOf(ids: readonly string[]): string | null {
  const leaving = new Set(ids.map(approvalAnchor))
  for (const id of ids) {
    const card = document.getElementById(approvalAnchor(id))
    for (const step of ['nextElementSibling', 'previousElementSibling'] as const) {
      for (let at = card?.[step]; at; at = at[step]) {
        if (at.id.startsWith('approval-') && !leaving.has(at.id)) return at.id
      }
    }
  }
  return null
}

/** After the cards have gone and any dialog has closed, so nothing takes focus back. */
function moveFocusTo(cardId: string | null) {
  window.setTimeout(() => (document.getElementById(cardId ?? TAB_ID) ?? document.getElementById(TAB_ID))?.focus(), 0)
}

/** Brings the card a link names into view, once its list has loaded, and only once per link. */
function useLandOnCard(target: string | null, listed: boolean) {
  const landedOn = useRef<string | null>(null)
  useEffect(() => {
    if (!target || !listed || landedOn.current === target) return
    const card = document.getElementById(target)
    if (!card) return
    landedOn.current = target
    card.scrollIntoView?.({ block: 'start' })
    card.focus({ preventScroll: true })
  }, [target, listed])
}

/** Questions agents have asked, shown above the approvals queue: another way work waits on a person. */
// @find: questions waiting, assistant asks a question, answer question, ask the user
function QuestionsWaiting() {
  const canRead = can('run:read')
  const questions = useQuestions({ status: 'pending' }, { enabled: canRead })
  const agentNames = useAgentNames()
  const { me, nameOf } = useMemberNamer()

  if (!canRead || !questions.data || questions.data.length === 0) return null

  return (
    <section aria-labelledby="approvals-questions-heading" style={{ marginBottom: 'var(--space-7)' }}>
      <Eyebrow as="h2" id="approvals-questions-heading">
        Questions waiting for an answer
      </Eyebrow>
      <div className="stack" style={{ gap: 'var(--space-5)', marginTop: 'var(--space-4)' }}>
        {questions.data.map((question) => (
          <QuestionCard
            key={question.id}
            question={question}
            agentName={agentNames[question.agentId]?.name ?? 'The agent'}
            agentCategory={agentNames[question.agentId]?.category}
            agentFallback={agentNames[question.agentId]?.fallback}
            via="approvals"
            compact
            me={me}
            nameOf={nameOf}
          />
        ))}
      </div>
    </section>
  )
}

/* ---- The queue ------------------------------------------------------------------------------------ */

// @find: pending approvals queue, approve request, reject request, decide, comment, payload, expires, POST /api/approvals/:id/decision
function PendingQueue() {
  const toast = useToast()
  const { hash } = useRouter()
  const queueQuery = usePendingApprovalPages()
  const count = useApprovalCount()
  const agentNames = useAgentNames()
  const agentsKnown = useAgents().data !== undefined
  const decideApproval = useDecideApproval()
  const decideMany = useDecideApprovals()
  const { me, nameOf } = useMemberNamer()
  const canRead = can('approval:read')
  const canReadAudit = can('audit:read')

  // Approving resumes the run before the request returns, so several approvals can be in flight
  // at once; each card keeps its own busy state until its own request settles.
  const [approvingIds, setApprovingIds] = useState<ReadonlySet<string>>(() => new Set())
  const [rejectingId, setRejectingId] = useState<string | null>(null)
  const [rejectTarget, setRejectTarget] = useState<ApprovalItem | null>(null)
  const [sendBackTarget, setSendBackTarget] = useState<ApprovalItem | null>(null)
  const [sendBackNote, setSendBackNote] = useState({ approvalId: '', text: '' })
  const [sendBackError, setSendBackError] = useState<string | null>(null)
  // A missing note is said at the field it is about; the dialog's own notice is for a failed send.
  const [sendBackFieldError, setSendBackFieldError] = useState<string | null>(null)
  const [sendingBackId, setSendingBackId] = useState<string | null>(null)
  // The note belongs to one request: it survives a failed attempt and a reopened dialog for the
  // same request, and starts empty for a different one.
  const [note, setNote] = useState({ approvalId: '', text: '' })
  const [rejectError, setRejectError] = useState<string | null>(null)
  const [bulkTarget, setBulkTarget] = useState<BulkTarget | null>(null)
  const [bulkError, setBulkError] = useState<string | null>(null)
  // What was decided here leaves the queue at once, rather than staying clickable until the
  // refreshed queue arrives (a second click would read "Someone already decided this request").
  // The Decided tab keeps the record; this only hides.
  const [gone, setGone] = useState<ReadonlySet<string>>(() => new Set())

  const loaded = useMemo(
    () => (queueQuery.data ? flattenApprovalPages(queueQuery.data.pages) : undefined),
    [queueQuery.data],
  )
  const pending = useMemo(
    () => loaded?.filter((approval) => approval.status === 'pending' && !gone.has(approval.id)),
    [loaded, gone],
  )
  const queueState = {
    data: pending,
    error: queueQuery.error,
    isLoading: queueQuery.isLoading,
    refetch: queueQuery.refetch,
  }

  const searchText = useCallback(
    (approval: ApprovalItem) =>
      [
        readableSummary(approval),
        toolLabel(approval.tool),
        actionClassLabel(approval.actionClass),
        agentNames[approval.agentId]?.name ?? '',
        approval.goalTitle ?? '',
      ].join(' '),
    [agentNames],
  )
  const facets = useMemo(() => ({ agent: (approval: ApprovalItem) => approval.agentId }), [])
  const filter = useListFilter({ rows: pending, text: searchText, facets })

  // The same request, said again and again, can be decided in one step (and only by someone who
  // may decide it).
  const groups = useMemo(() => groupIdentical((pending ?? []).filter((approval) => approval.canDecide)), [pending])

  const hasMore = queueQuery.hasNextPage
  const fetchingMore = queueQuery.isFetchingNextPage
  const { fetchNextPage } = queueQuery

  // A link from a run trace (/approvals#approval-<id>) lands on its card once the queue has
  // loaded, however long that took, and only once per link. A card on a page not yet loaded is
  // looked for a page at a time.
  const target = hash.startsWith('#approval-') ? hash.slice(1) : null
  const targetListed = target !== null && (pending?.some((approval) => approvalAnchor(approval.id) === target) ?? false)
  useLandOnCard(target, pending !== undefined)
  useEffect(() => {
    if (target && pending !== undefined && !targetListed && hasMore && !fetchingMore) void fetchNextPage()
  }, [target, pending, targetListed, hasMore, fetchingMore, fetchNextPage])
  // Not found in the queue, and no page left to look in: ask what became of it.
  const wasDecidedHere = target !== null && gone.has(target.slice('approval-'.length))
  const lookup = useApproval(target ? target.slice('approval-'.length) : null, {
    enabled: canRead && target !== null && pending !== undefined && !targetListed && !hasMore && !wasDecidedHere,
  })

  const setApproving = (id: string, busy: boolean) =>
    setApprovingIds((current) => {
      const next = new Set(current)
      if (busy) next.add(id)
      else next.delete(id)
      return next
    })

  const leave = (ids: Iterable<string>) =>
    setGone((current) => {
      const next = new Set(current)
      for (const id of ids) next.add(id)
      return next
    })

  const handleApprove = async (approval: ApprovalItem) => {
    if (approvingIds.has(approval.id)) return
    setApproving(approval.id, true)
    try {
      const result = await decideApproval.mutateAsync({ id: approval.id, approved: true })
      const outcome = runOutcome(result.runStatus)
      const next = neighbourOf([approval.id])
      leave([approval.id])
      moveFocusTo(next)
      // runOutcome('running') already opens with 'Approved.'; say it once.
      toast.success(outcome.startsWith('Approved.') ? outcome : `Approved. ${outcome}`)
    } catch (error) {
      toast.error(decisionError(error))
    } finally {
      setApproving(approval.id, false)
    }
  }

  const openReject = (approval: ApprovalItem) => {
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
      const next = neighbourOf([approval.id])
      leave([approval.id])
      moveFocusTo(next)
      toast.success(`Rejected. ${runOutcome(result.runStatus)}`)
      setRejectTarget(null)
      setNote({ approvalId: '', text: '' })
    } catch (error) {
      // The dialog stays open with the reason, and the note is kept for another try.
      setRejectError(decisionError(error))
    } finally {
      setRejectingId(null)
    }
  }

  const openSendBack = (approval: ApprovalItem) => {
    if (sendBackNote.approvalId !== approval.id) setSendBackNote({ approvalId: approval.id, text: '' })
    setSendBackError(null)
    setSendBackFieldError(null)
    setSendBackTarget(approval)
  }

  const confirmSendBack = async () => {
    const approval = sendBackTarget
    if (!approval) return
    const text = sendBackNote.approvalId === approval.id ? sendBackNote.text.trim() : ''
    if (!text) {
      setSendBackFieldError('Say what should change, so the agent has something to revise.')
      return
    }
    setSendBackError(null)
    setSendingBackId(approval.id)
    try {
      await decideApproval.mutateAsync({ id: approval.id, approved: false, mode: 'request_changes', note: text })
      const next = neighbourOf([approval.id])
      leave([approval.id])
      moveFocusTo(next)
      toast.success('Sent back. The agent will revise it and ask again.')
      setSendBackTarget(null)
      setSendBackNote({ approvalId: '', text: '' })
    } catch (error) {
      setSendBackError(decisionError(error))
    } finally {
      setSendingBackId(null)
    }
  }

  const confirmBulk = async (ids: string[], text: string) => {
    if (!bulkTarget) return
    const approved = bulkTarget.mode === 'approve'
    setBulkError(null)
    try {
      const result = await decideMany.mutateAsync({ ids, approved, ...(text ? { note: text } : {}) })
      // Only a request still waiting for this person stays; everything else has left the queue.
      const leaving = result.results.filter((item) => item.result !== 'forbidden').map((item) => item.id)
      const next = neighbourOf(leaving)
      leave(leaving)
      moveFocusTo(next)
      const summary = bulkSummary(result.results, approved)
      if (result.results.some((item) => item.result === 'decided')) toast.success(summary)
      else toast.error(summary)
      setBulkTarget(null)
    } catch (error) {
      setBulkError(decisionError(error))
    }
  }

  const loadMore = async () => {
    const result = await fetchNextPage()
    if (result.isError) toast.error('More requests could not be loaded. Try again.')
  }

  const lookedUp = lookup.data
  const targetNotice =
    target === null || targetListed || pending === undefined || hasMore || wasDecidedHere || lookup.isLoading
      ? null
      : lookedUp && lookedUp.status !== 'pending'
        ? { missing: false as const, status: lookedUp.status }
        : { missing: true as const, status: null }

  return (
    <>
      {targetNotice && (
        <div style={{ marginBottom: 'var(--space-5)' }}>
          <Notice tone="warning">
            {targetNotice.missing
              ? 'That request is no longer waiting. It was decided, withdrawn or expired.'
              : `That request is no longer waiting. It was ${statusLabel('approval', targetNotice.status).label.toLowerCase()}. `}
            {!targetNotice.missing && (
              <a className="link" href={`/approvals?tab=decided#${target}`}>
                See it under Decided
              </a>
            )}
          </Notice>
        </div>
      )}

      <QueryState
        query={queueState}
        permission="approval:read"
        what="the approvals queue"
        rows={4}
        isEmpty={() => (pending?.length ?? 0) === 0 && !hasMore}
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
          const filtering = queue.length > FILTER_THRESHOLD || filter.active || hasMore
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
          const waiting = count.data?.pending
          return (
            <div style={{ marginTop: 'var(--space-6)' }}>
              {groups.length > 0 && !filter.active && (
                <SimilarRequests
                  groups={groups}
                  agentName={(agentId) => agentNames[agentId]?.name ?? 'Unknown agent'}
                  onDecide={(next) => {
                    setBulkError(null)
                    setBulkTarget(next)
                  }}
                />
              )}
              {filtering && (
                <FilterBar
                  searchLabel="Search approvals"
                  query={filter.query}
                  onQueryChange={filter.setQuery}
                  placeholder="Action, tool, goal or agent"
                  facets={agentIds.length > 1 ? [agentFacet] : []}
                  shown={shown.length}
                  total={queue.length}
                  scopeNote={hasMore ? `Search covers the ${queue.length} requests loaded so far.` : undefined}
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
                      me={me}
                      nameOf={nameOf}
                      onApprove={() => void handleApprove(approval)}
                      onReject={() => openReject(approval)}
                      onSendBack={() => openSendBack(approval)}
                      isSendingBack={sendingBackId === approval.id}
                      isApproving={approvingIds.has(approval.id)}
                      isRejecting={rejectingId === approval.id}
                    />
                  ))}
                </div>
              )}
              {hasMore && (
                <div
                  className="row"
                  style={{ justifyContent: 'center', alignItems: 'center', gap: 'var(--space-4)', marginTop: 'var(--space-6)', flexWrap: 'wrap' }}
                >
                  <span className="caption muted" role="status">
                    {waiting !== undefined ? `Showing ${queue.length} of ${waiting} waiting` : `Showing ${queue.length}`}
                  </span>
                  <Button variant="outline" onClick={() => void loadMore()} loading={fetchingMore}>
                    Load more
                  </Button>
                </div>
              )}
            </div>
          )
        }}
      </QueryState>

      <BulkDialog
        target={bulkTarget}
        onClose={() => setBulkTarget(null)}
        onConfirm={confirmBulk}
        loading={decideMany.isPending}
        error={bulkError}
      />

      {can('approval:decide') && (
        <ConfirmDialog
          open={sendBackTarget !== null}
          onClose={() => setSendBackTarget(null)}
          onConfirm={confirmSendBack}
          eyebrow="Send back"
          title="Send this back with feedback?"
          description="The agent does not take this action as written. It is told what you want changed, may revise it, and asks again. The run keeps going."
          confirmLabel="Send back"
          cancelLabel="Go back"
          tone="primary"
          loading={sendingBackId !== null}
          error={sendBackError}
        >
          <Textarea
            label="What should change"
            value={sendBackNote.text}
            onChange={(e) => {
              setSendBackNote({ approvalId: sendBackTarget?.id ?? '', text: e.target.value.slice(0, NOTE_MAX) })
              setSendBackFieldError(null)
            }}
            error={sendBackFieldError ?? undefined}
            placeholder="For example: use a softer tone, and leave out the discount."
            maxLength={NOTE_MAX}
            rows={4}
            hint="The agent reads this, and it is recorded in the audit log. Up to 1,000 characters."
          />
        </ConfirmDialog>
      )}

      {can('approval:decide') && (
        <ConfirmDialog
          open={rejectTarget !== null}
          onClose={() => setRejectTarget(null)}
          onConfirm={confirmReject}
          eyebrow="Reject request"
          title="Reject this action?"
          description="The agent does not take this action, and its run stops. Your note is saved with the decision."
          confirmLabel="Reject and stop"
          cancelLabel="Go back"
          tone="danger"
          loading={rejectingId !== null}
          error={rejectError}
        >
          <Textarea
            label="Note"
            optional
            value={note.text}
            onChange={(e) => setNote({ approvalId: rejectTarget?.id ?? '', text: e.target.value.slice(0, NOTE_MAX) })}
            placeholder="Why is this being rejected?"
            maxLength={NOTE_MAX}
            rows={4}
            // The note goes onto the run's failure reason, which the person who asked sees, and
            // into the audit log. The agent itself never reads it.
            hint="Shown to the person who asked and recorded in the audit log. Up to 1,000 characters."
          />
        </ConfirmDialog>
      )}
    </>
  )
}

/* ---- The history ---------------------------------------------------------------------------------- */

// @find: decided approvals history, who approved, past decisions, rejected list
function DecidedHistory() {
  const toast = useToast()
  const { search, hash } = useRouter()
  const agentNames = useAgentNames()
  const agentsKnown = useAgents().data !== undefined
  const { me, nameOf } = useMemberNamer()

  // What to ask the server for comes straight from the address; the filter hook below owns writing it.
  const agentParam = search.get('agent')?.split(',')[0] ?? null
  const outcomeParam = search.get('outcome')?.split(',')[0] ?? null
  const decidedQuery = useDecidedApprovalPages({ agentId: agentParam, status: outcomeParam })
  const items = useMemo(
    () => (decidedQuery.data ? flattenApprovalPages(decidedQuery.data.pages) : undefined),
    [decidedQuery.data],
  )

  const decidedState = {
    data: items,
    error: decidedQuery.error,
    isLoading: decidedQuery.isLoading,
    refetch: decidedQuery.refetch,
  }

  const searchText = useCallback(
    (approval: ApprovalItem) =>
      [
        readableSummary(approval),
        toolLabel(approval.tool),
        agentNames[approval.agentId]?.name ?? '',
        approval.goalTitle ?? '',
        approval.decisionNote ?? '',
        approval.decidedBy ? nameOf(approval.decidedBy) : '',
      ].join(' '),
    [agentNames, nameOf],
  )
  const facets = useMemo(
    () => ({ agent: (approval: ApprovalItem) => approval.agentId, outcome: (approval: ApprovalItem) => approval.status }),
    [],
  )
  const filter = useListFilter({ rows: items, text: searchText, facets })

  const target = hash.startsWith('#approval-') ? hash.slice(1) : null
  const hasMore = decidedQuery.hasNextPage
  const fetchingMore = decidedQuery.isFetchingNextPage
  const { fetchNextPage } = decidedQuery
  const listed = target !== null && (items?.some((approval) => approvalAnchor(approval.id) === target) ?? false)
  useLandOnCard(target, listed)
  useEffect(() => {
    if (target && items !== undefined && !listed && hasMore && !fetchingMore) void fetchNextPage()
  }, [target, items, listed, hasMore, fetchingMore, fetchNextPage])

  const loadMore = async () => {
    const result = await fetchNextPage()
    if (result.isError) toast.error('More decisions could not be loaded. Try again.')
  }

  return (
    <QueryState
      query={decidedState}
      permission="approval:read"
      what="the decided requests"
      rows={4}
      isEmpty={(rows) => rows.length === 0 && !agentParam && !outcomeParam}
      empty={
        <div style={{ marginTop: 'var(--space-7)' }}>
          <Card>
            <EmptyState
              icon={<EmptyIcon kind="inbox" />}
              title="Nothing decided yet"
              body="Requests that were approved, rejected or expired appear here, with who decided and why."
            />
          </Card>
        </div>
      }
    >
      {(rows) => {
        const shown = filter.filtered
        const agentIdsKnown = Object.keys(agentNames).sort((a, b) =>
          (agentNames[a]?.name ?? '').localeCompare(agentNames[b]?.name ?? ''),
        )
        const selects: FilterSelect[] = [
          {
            label: 'Agent',
            value: agentParam ?? '',
            options: [
              { value: '', label: 'Every agent' },
              ...agentIdsKnown.map((agentId) => ({ value: agentId, label: agentNames[agentId]?.name ?? 'Unknown agent' })),
            ],
            onChange: (value) => filter.setOnly('agent', value || null),
          },
          {
            label: 'Outcome',
            value: outcomeParam ?? '',
            options: [{ value: '', label: 'Every outcome' }, ...OUTCOMES],
            onChange: (value) => filter.setOnly('outcome', value || null),
          },
        ]
        return (
          <div style={{ marginTop: 'var(--space-6)' }}>
            <FilterBar
              searchLabel="Search decisions"
              query={filter.query}
              onQueryChange={filter.setQuery}
              placeholder="Action, goal, agent, person or reason"
              selects={selects}
              shown={shown.length}
              total={rows.length}
              scopeNote={hasMore ? `Search covers the ${rows.length} decisions loaded so far.` : undefined}
              active={filter.active}
              onClear={filter.clear}
            />
            {shown.length === 0 ? (
              <Card>
                <FilterEmpty onClear={filter.clear} what="decisions" />
              </Card>
            ) : (
              <div className="stack" style={{ gap: 'var(--space-5)' }}>
                {shown.map((approval) => (
                  <DecidedCard
                    key={approval.id}
                    approval={approval}
                    agent={agentNames[approval.agentId]}
                    agentsKnown={agentsKnown}
                    me={me}
                    nameOf={nameOf}
                  />
                ))}
              </div>
            )}
            {hasMore && (
              <div className="row" style={{ justifyContent: 'center', marginTop: 'var(--space-6)' }}>
                <Button variant="outline" onClick={() => void loadMore()} loading={fetchingMore}>
                  Load more
                </Button>
              </div>
            )}
          </div>
        )
      }}
    </QueryState>
  )
}

/* ---- The page ------------------------------------------------------------------------------------- */

// @find: Approvals component, approvals page, approve or reject, waiting and decided tabs, /approvals
export function Approvals() {
  const { search, navigate } = useRouter()
  const canRead = can('approval:read')
  const canDecide = can('approval:decide')
  const tab: Tab = search.get('tab') === 'decided' ? 'decided' : 'pending'
  const count = useApprovalCount({ enabled: canRead })

  // Each tab has its own filters, so moving between them starts the other clean.
  const showTab = (next: Tab) => {
    if (next === tab) return
    navigate(next === 'decided' ? '/approvals?tab=decided' : '/approvals', { replace: true, scroll: false })
  }

  return (
    <div className="page">
      <PageHeader
        eyebrow="Waiting on a decision"
        title="Approvals"
        description="Agents stop here before doing anything that leaves the workspace or cannot be undone. Questions agents asked are here too."
      />

      <QuestionsWaiting />

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
      </div>

      {canRead && (
        <div className="orc-segmented" role="group" aria-label="Which requests to show" style={{ marginTop: 'var(--space-6)' }}>
          <button id={TAB_ID} type="button" aria-pressed={tab === 'pending'} onClick={() => showTab('pending')}>
            Waiting{count.data ? ` (${count.data.pending})` : ''}
          </button>
          <button type="button" aria-pressed={tab === 'decided'} onClick={() => showTab('decided')}>
            Decided
          </button>
        </div>
      )}

      {tab === 'decided' && canRead ? <DecidedHistory /> : <PendingQueue />}
    </div>
  )
}
