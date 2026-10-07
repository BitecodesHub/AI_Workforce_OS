import { useId, useState } from 'react'
import { Button, Card, ConfirmDialog, Eyebrow, Tag, Textarea, Time } from '../ui'
import { Collapsible } from '../ui/Collapsible'
import { PayloadPreview } from './PayloadPreview'
import { withoutFinalStop } from '../run/traceModel'
import { NOTE_MAX, isDestructive, payloadHeadline, readableSummary } from '../../lib/approvals'
import type { ApprovalGroup } from '../../lib/approvals'
import type { ApprovalItem } from '../../lib/approvalQueries'

/*
 * Several identical requests decided together: the group offered above the queue, and the
 * confirmation that lists every request before anything is sent.
 */

/** Which group is being decided, and which way. */
export type BulkTarget = { group: ApprovalGroup<ApprovalItem>; mode: 'approve' | 'reject' }

/**
 * Identical requests - the same agent, the same tool, the same words - decided together. A
 * schedule that posts a daily summary leaves a queue of them, and reading thirty to click thirty
 * buttons helps nobody. Each is still listed below, and the confirmation lists every one.
 */
export function SimilarRequests({
  groups,
  agentName,
  onDecide,
}: {
  groups: ApprovalGroup<ApprovalItem>[]
  agentName: (agentId: string) => string
  onDecide: (target: BulkTarget) => void
}) {
  const headingId = useId()
  return (
    <section aria-labelledby={headingId} style={{ marginBottom: 'var(--space-6)' }}>
      <Card>
        <Eyebrow as="h2" id={headingId}>
          Similar requests
        </Eyebrow>
        <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
          These ask for the same thing. Decide them together, or read each one below.
        </p>
        <ul className="stack" style={{ gap: 'var(--space-4)', listStyle: 'none', margin: 0, padding: 0 }}>
          {groups.map((group) => {
            const first = group.items[0]
            if (!first) return null
            const n = group.items.length
            return (
              <li
                key={group.key}
                className="row"
                style={{ flexWrap: 'wrap', justifyContent: 'space-between', gap: 'var(--space-3) var(--space-5)' }}
              >
                <span style={{ overflowWrap: 'anywhere' }}>
                  <strong>{agentName(first.agentId)}</strong>: {readableSummary(first)} <Tag>{n} waiting</Tag>
                  {group.items.some((item) => isDestructive(item.actionClass)) && (
                    <>
                      {' '}
                      <Tag tone="danger">Removes something</Tag>
                    </>
                  )}
                </span>
                <span className="row" style={{ gap: 'var(--space-3)' }}>
                  <Button onClick={() => onDecide({ group, mode: 'approve' })}>Approve all {n}</Button>
                  <Button variant="outline" onClick={() => onDecide({ group, mode: 'reject' })}>
                    Reject all {n}
                  </Button>
                </span>
              </li>
            )
          })}
        </ul>
      </Card>
    </section>
  )
}

/**
 * The confirmation for deciding a group. It lists every request it would decide, one line each.
 * Approving leaves out the ones that remove something until the person ticks them, so a deletion
 * is never approved by a button that also approved thirty emails; rejecting needs no such care.
 */
export function BulkDialog({
  target,
  onClose,
  onConfirm,
  loading,
  error,
}: {
  target: BulkTarget | null
  onClose: () => void
  onConfirm: (ids: string[], note: string) => void | Promise<unknown>
  loading: boolean
  error: string | null
}) {
  // Mounted only while a group is chosen, and keyed by it, so each opening starts with its own
  // ticks and its own note.
  return target ? (
    <BulkDialogBody key={`${target.mode}:${target.group.key}`} target={target} onClose={onClose} onConfirm={onConfirm} loading={loading} error={error} />
  ) : null
}

/**
 * One request in full - every field, the recipients including Cc and Bcc, and the whole body -
 * because approving "exactly as written" means the person read what was written. Open from the
 * start when approving, one click away when rejecting.
 */
function RequestInFull({ item, startOpen }: { item: ApprovalItem; startOpen: boolean }) {
  const [open, setOpen] = useState(startOpen)
  return (
    <Collapsible title="The whole request" open={open} onToggle={setOpen} headingLevel="p">
      <PayloadPreview payload={item.payload} actionClass={item.actionClass} />
    </Collapsible>
  )
}

function BulkDialogBody({
  target,
  onClose,
  onConfirm,
  loading,
  error,
}: {
  target: BulkTarget
  onClose: () => void
  onConfirm: (ids: string[], note: string) => void | Promise<unknown>
  loading: boolean
  error: string | null
}) {
  const { mode, group } = target
  const approving = mode === 'approve'
  const [selected, setSelected] = useState<ReadonlySet<string>>(
    () => new Set(group.items.filter((item) => !approving || !isDestructive(item.actionClass)).map((item) => item.id)),
  )
  const [note, setNote] = useState('')
  const [pickOne, setPickOne] = useState(false)
  const leftOut = approving ? group.items.filter((item) => isDestructive(item.actionClass)).length : 0

  const toggle = (id: string) =>
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const first = group.items[0]
  return (
    <ConfirmDialog
      open
      onClose={onClose}
      onConfirm={() => {
        if (selected.size === 0) {
          setPickOne(true)
          return undefined
        }
        setPickOne(false)
        return onConfirm(
          group.items.filter((item) => selected.has(item.id)).map((item) => item.id),
          note.trim(),
        )
      }}
      eyebrow={approving ? 'Approve together' : 'Reject together'}
      title={approving ? `Approve ${selected.size} of ${group.items.length} requests?` : `Reject ${selected.size} of ${group.items.length} requests?`}
      description={
        approving
          ? `${first ? withoutFinalStop(readableSummary(first)) : 'These requests'}. Each request below is approved exactly as written.${
              leftOut > 0 ? ' The ones that remove something start unticked: tick any you want included.' : ''
            }`
          : 'The agent takes none of these actions, and each of their runs stops. Your note is saved with every decision.'
      }
      confirmLabel={approving ? `Approve ${selected.size}` : `Reject ${selected.size}`}
      cancelLabel="Go back"
      tone={approving ? 'primary' : 'danger'}
      loading={loading}
      error={pickOne && selected.size === 0 ? 'Tick at least one request to decide.' : error}
    >
      <fieldset style={{ border: 'none', margin: 0, padding: 0 }}>
        <legend className="caption muted" style={{ marginBottom: 'var(--space-2)' }}>
          Requests to {approving ? 'approve' : 'reject'}
        </legend>
        <div className="approval-payload" style={{ maxHeight: '420px' }}>
          {group.items.map((item) => {
            const destructive = isDestructive(item.actionClass)
            return (
              <div key={item.id} style={{ marginTop: 'var(--space-3)' }}>
                <label className="question-option">
                  <input type="checkbox" checked={selected.has(item.id)} onChange={() => toggle(item.id)} />
                  <span className="question-option-label">
                    {destructive && <Tag tone="danger">Removes something</Tag>}{' '}
                    {payloadHeadline(item.payload) || 'No details'}
                    <span className="caption muted">
                      {' '}
                      · requested <Time iso={item.requestedAt} />
                    </span>
                  </span>
                </label>
                <RequestInFull item={item} startOpen={approving} />
              </div>
            )
          })}
        </div>
      </fieldset>
      {!approving && (
        <div style={{ marginTop: 'var(--space-4)' }}>
          <Textarea
            label="Note"
            optional
            value={note}
            onChange={(event) => setNote(event.target.value.slice(0, NOTE_MAX))}
            placeholder="Why are these being rejected?"
            maxLength={NOTE_MAX}
            rows={3}
            hint="Shown to the person who asked and recorded in the audit log. Up to 1,000 characters."
          />
        </div>
      )}
    </ConfirmDialog>
  )
}
