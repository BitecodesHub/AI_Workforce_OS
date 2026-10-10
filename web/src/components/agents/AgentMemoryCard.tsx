// @find: agent memory, what the agent remembers, notes, add note, edit note, pin note, unpin note, forget everything, memory kind, secret refused, agent page, Memory card, PUT/DELETE memory
// @what: Card on an agent's page to list, add, edit, pin and forget the notes the agent remembers.
// @flow: Used by the agent detail page; calls lib/agentMemoryQueries.
import { useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Card, ConfirmDialog, Eyebrow, Notice, Select, Tag, Textarea, Time } from '../ui'
import { QueryState } from '../ui/QueryState'
import { ApiError, describeApiError } from '../../lib/api'
import {
  MEMORY_KIND_LABEL,
  MEMORY_MAX_CHARS,
  memorySourceLabel,
  useAddMemory,
  useAgentMemories,
  useDeleteMemory,
  useForgetAllMemories,
  usePinMemory,
  useUpdateMemory,
} from '../../lib/agentMemoryQueries'
import type { AgentMemory, MemoryKind } from '../../lib/agentMemoryQueries'
import { can } from '../../lib/session'
import { useToast } from '../../lib/toast'

/*
 * What an AI employee remembers: the short notes it keeps so it knows them next time. The agent
 * reads the ones that fit each new piece of work before it starts, and people who can change the
 * agent add, correct and remove them here. Passwords, keys and card numbers are refused.
 */

const KINDS = Object.keys(MEMORY_KIND_LABEL) as MemoryKind[]

function noteLabel(content: string): string {
  const flat = content.replace(/\s+/g, ' ').trim()
  return flat.length > 60 ? `${flat.slice(0, 59).trimEnd()}…` : flat
}

// @find: NoteRow, note row, agent memory, what the agent remembers, notes, add note
export function NoteRow({
  note,
  canEdit,
  onEdit,
  onRemove,
  onPin,
  pinning = false,
}: {
  note: AgentMemory
  canEdit: boolean
  onEdit: () => void
  onRemove: () => void
  /** Pins or unpins the note; absent where pinning is not offered. */
  onPin?: (() => void) | undefined
  pinning?: boolean
}) {
  return (
    <li
      style={{
        borderTop: '1px solid var(--line)',
        paddingTop: 'var(--space-4)',
        paddingBottom: 'var(--space-4)',
      }}
    >
      <p style={{ margin: 0, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{note.content}</p>
      <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap', marginTop: 'var(--space-2)' }}>
        {note.pinned && (
          <Tag tone="blue" title="Read before every piece of work, whatever it is about">
            Pinned
          </Tag>
        )}
        <Tag tone="neutral">{MEMORY_KIND_LABEL[note.kind]}</Tag>
        <span className="caption muted">
          {memorySourceLabel(note.source)} · changed <Time iso={note.updatedAt} />
          {note.recallCount > 0 ? ` · used ${note.recallCount} ${note.recallCount === 1 ? 'time' : 'times'}` : ''}
        </span>
        {canEdit && (
          <span className="row" style={{ gap: 'var(--space-2)', marginLeft: 'auto' }}>
            {/* Every note has these buttons; the label says which note each acts on. */}
            {onPin && (
              <Button
                variant="quiet"
                onClick={onPin}
                loading={pinning}
                aria-pressed={note.pinned === true}
                aria-label={`${note.pinned ? 'Unpin' : 'Pin'} note: ${noteLabel(note.content)}`}
              >
                {note.pinned ? 'Unpin' : 'Pin'}
              </Button>
            )}
            <Button variant="quiet" onClick={onEdit} aria-label={`Edit note: ${noteLabel(note.content)}`}>
              Edit
            </Button>
            <Button variant="quiet" onClick={onRemove} aria-label={`Remove note: ${noteLabel(note.content)}`}>
              Remove
            </Button>
          </span>
        )}
      </div>
    </li>
  )
}

/** What the server said is wrong with a note's words, to show at the field; null for any other failure. */
function contentProblem(error: unknown): string | null {
  return error instanceof ApiError ? (error.fields.content ?? null) : null
}

// @find: AgentMemoryCard, agent memory card, agent memory, what the agent remembers, notes, add note
export function AgentMemoryCard({ agentId, agentName }: { agentId: string; agentName: string }) {
  const toast = useToast()
  const canEdit = can('agent:update')
  const memories = useAgentMemories(agentId, { enabled: can('agent:read') })
  const add = useAddMemory(agentId)
  const update = useUpdateMemory(agentId)
  const remove = useDeleteMemory(agentId)
  const pin = usePinMemory(agentId)
  const forgetAll = useForgetAllMemories(agentId)
  const [forgetting, setForgetting] = useState(false)
  const [forgetError, setForgetError] = useState<string | null>(null)

  const [content, setContent] = useState('')
  const [kind, setKind] = useState<MemoryKind>('fact')
  const [failure, setFailure] = useState<string | null>(null)
  // The server's reason a note was refused (a secret in it, say), shown at the field it is about.
  const [contentError, setContentError] = useState<string | null>(null)
  const [editFieldError, setEditFieldError] = useState<string | null>(null)
  const [editing, setEditing] = useState<AgentMemory | null>(null)
  const [editText, setEditText] = useState('')
  const [editError, setEditError] = useState<string | null>(null)
  const [removing, setRemoving] = useState<AgentMemory | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const text = content.trim()
    if (!text) return
    setFailure(null)
    setContentError(null)
    try {
      await add.mutateAsync({ content: text, kind })
      setContent('')
      toast.success(`${agentName} will remember that.`)
    } catch (error) {
      const field = contentProblem(error)
      if (field) setContentError(field)
      else setFailure(describeApiError(error))
    }
  }

  async function saveEdit() {
    if (!editing) return
    setEditError(null)
    setEditFieldError(null)
    try {
      await update.mutateAsync({ id: editing.id, content: editText.trim(), kind: editing.kind })
      setEditing(null)
      toast.success('Note changed.')
    } catch (error) {
      const field = contentProblem(error)
      if (field) setEditFieldError(field)
      else setEditError(describeApiError(error))
    }
  }

  async function confirmRemove() {
    if (!removing) return
    try {
      await remove.mutateAsync(removing.id)
      setRemoving(null)
      toast.success('Note removed.')
    } catch (error) {
      toast.error(describeApiError(error))
      setRemoving(null)
    }
  }

  async function togglePin(note: AgentMemory) {
    try {
      await pin.mutateAsync({ id: note.id, pinned: !note.pinned })
      toast.success(note.pinned ? 'Note unpinned.' : `Pinned. ${agentName} will read it before every piece of work.`)
    } catch (error) {
      toast.error(describeApiError(error))
    }
  }

  async function confirmForgetAll() {
    setForgetError(null)
    try {
      const result = await forgetAll.mutateAsync()
      setForgetting(false)
      toast.success(
        result.removed === 1 ? `${agentName} forgot 1 note.` : `${agentName} forgot ${result.removed} notes.`,
      )
    } catch (error) {
      setForgetError(describeApiError(error))
    }
  }

  return (
    <Card as="section">
      <Eyebrow as="h2">What it remembers</Eyebrow>
      <p className="muted" style={{ marginBottom: 'var(--space-5)', maxWidth: '62ch' }}>
        Short notes {agentName} keeps so it knows them next time. Before each piece of work it reads the ones that
        fit, and every pinned one. Only {agentName} uses these notes.
        {canEdit ? ' Pin what it must always know; correct or remove any that are wrong.' : ''}
      </p>

      <QueryState
        query={memories}
        permission="agent:read"
        what="what this agent remembers"
        rows={3}
        isEmpty={(data) => data.memories.length === 0}
        empty={<p className="muted">{agentName} has not remembered anything yet.</p>}
      >
        {(data) => (
          <>
            <ul style={{ margin: 0, padding: 0, listStyle: 'none' }}>
              {data.memories.map((note) => (
                <NoteRow
                  key={note.id}
                  note={note}
                  canEdit={canEdit}
                  onEdit={() => {
                    setEditing(note)
                    setEditText(note.content)
                    setEditError(null)
                    setEditFieldError(null)
                  }}
                  onRemove={() => setRemoving(note)}
                  onPin={canEdit ? () => void togglePin(note) : undefined}
                  pinning={pin.isPending && pin.variables?.id === note.id}
                />
              ))}
            </ul>
            <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap', marginTop: 'var(--space-3)', alignItems: 'center' }}>
              <p className="caption muted" style={{ margin: 0 }}>
                {data.total} of {data.limit} notes.
              </p>
              {canEdit && (
                <Button
                  variant="quiet"
                  style={{ marginLeft: 'auto' }}
                  onClick={() => {
                    setForgetError(null)
                    setForgetting(true)
                  }}
                >
                  Forget everything
                </Button>
              )}
            </div>
          </>
        )}
      </QueryState>

      {canEdit && (
        <form className="stack" style={{ gap: 'var(--space-3)', marginTop: 'var(--space-5)' }} onSubmit={submit} noValidate>
          {failure && (
            <Notice tone="warning" live>
              {failure}
            </Notice>
          )}
          <Textarea
            label="Add a note"
            value={content}
            onChange={(event) => {
              setContent(event.target.value.slice(0, MEMORY_MAX_CHARS))
              setContentError(null)
            }}
            maxLength={MEMORY_MAX_CHARS}
            rows={3}
            hint="One fact in plain words. Do not include passwords, keys or card numbers."
            error={contentError ?? undefined}
          />
          <div className="row" style={{ gap: 'var(--space-3)', alignItems: 'flex-end', flexWrap: 'wrap' }}>
            <Select label="Kind" value={kind} onChange={(event) => setKind(event.target.value as MemoryKind)}>
              {KINDS.map((value) => (
                <option key={value} value={value}>
                  {MEMORY_KIND_LABEL[value]}
                </option>
              ))}
            </Select>
            <Button type="submit" loading={add.isPending} disabled={content.trim() === ''}>
              Remember this
            </Button>
          </div>
        </form>
      )}

      <ConfirmDialog
        open={editing !== null}
        onClose={() => setEditing(null)}
        onConfirm={saveEdit}
        eyebrow="What it remembers"
        title="Change this note"
        confirmLabel="Save"
        cancelLabel="Cancel"
        tone="primary"
        loading={update.isPending}
        error={editError}
      >
        <Textarea
          label="Note"
          value={editText}
          onChange={(event) => {
            setEditText(event.target.value.slice(0, MEMORY_MAX_CHARS))
            setEditFieldError(null)
          }}
          maxLength={MEMORY_MAX_CHARS}
          rows={4}
          error={editFieldError ?? undefined}
        />
      </ConfirmDialog>

      <ConfirmDialog
        open={removing !== null}
        onClose={() => setRemoving(null)}
        onConfirm={confirmRemove}
        eyebrow="What it remembers"
        title="Remove this note?"
        description={`${agentName} will no longer know it. This cannot be undone.`}
        confirmLabel="Remove"
        cancelLabel="Keep it"
        tone="danger"
        loading={remove.isPending}
      >
        <p style={{ overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' }}>{removing?.content}</p>
      </ConfirmDialog>

      <ConfirmDialog
        open={forgetting}
        onClose={() => setForgetting(false)}
        onConfirm={confirmForgetAll}
        eyebrow="What it remembers"
        title={`Forget everything ${agentName} remembers?`}
        description={`Every note is removed, pinned ones too. ${agentName} starts its next piece of work knowing none of them. Its runs and traces are kept. This cannot be undone.`}
        confirmLabel="Forget everything"
        cancelLabel="Keep the notes"
        tone="danger"
        loading={forgetAll.isPending}
        error={forgetError}
      />
    </Card>
  )
}
