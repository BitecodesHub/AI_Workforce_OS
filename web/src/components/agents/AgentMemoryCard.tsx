import { useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Card, ConfirmDialog, Eyebrow, Notice, Select, Tag, Textarea, Time } from '../ui'
import { QueryState } from '../ui/QueryState'
import { describeApiError } from '../../lib/api'
import {
  MEMORY_KIND_LABEL,
  MEMORY_MAX_CHARS,
  memorySourceLabel,
  useAddMemory,
  useAgentMemories,
  useDeleteMemory,
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

function NoteRow({
  note,
  canEdit,
  onEdit,
  onRemove,
}: {
  note: AgentMemory
  canEdit: boolean
  onEdit: () => void
  onRemove: () => void
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
        <Tag tone="neutral">{MEMORY_KIND_LABEL[note.kind]}</Tag>
        <span className="caption muted">
          {memorySourceLabel(note.source)} · changed <Time iso={note.updatedAt} />
          {note.recallCount > 0 ? ` · used ${note.recallCount} ${note.recallCount === 1 ? 'time' : 'times'}` : ''}
        </span>
        {canEdit && (
          <span className="row" style={{ gap: 'var(--space-2)', marginLeft: 'auto' }}>
            <Button variant="quiet" onClick={onEdit}>
              Edit
            </Button>
            <Button variant="quiet" onClick={onRemove}>
              Remove
            </Button>
          </span>
        )}
      </div>
    </li>
  )
}

export function AgentMemoryCard({ agentId, agentName }: { agentId: string; agentName: string }) {
  const toast = useToast()
  const canEdit = can('agent:update')
  const memories = useAgentMemories(agentId, { enabled: can('agent:read') })
  const add = useAddMemory(agentId)
  const update = useUpdateMemory(agentId)
  const remove = useDeleteMemory(agentId)

  const [content, setContent] = useState('')
  const [kind, setKind] = useState<MemoryKind>('fact')
  const [failure, setFailure] = useState<string | null>(null)
  const [editing, setEditing] = useState<AgentMemory | null>(null)
  const [editText, setEditText] = useState('')
  const [editError, setEditError] = useState<string | null>(null)
  const [removing, setRemoving] = useState<AgentMemory | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const text = content.trim()
    if (!text) return
    setFailure(null)
    try {
      await add.mutateAsync({ content: text, kind })
      setContent('')
      toast.success(`${agentName} will remember that.`)
    } catch (error) {
      setFailure(describeApiError(error))
    }
  }

  async function saveEdit() {
    if (!editing) return
    setEditError(null)
    try {
      await update.mutateAsync({ id: editing.id, content: editText.trim(), kind: editing.kind })
      setEditing(null)
      toast.success('Note changed.')
    } catch (error) {
      setEditError(describeApiError(error))
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

  return (
    <Card as="section">
      <Eyebrow as="h2">What it remembers</Eyebrow>
      <p className="muted" style={{ marginBottom: 'var(--space-5)', maxWidth: '62ch' }}>
        Short notes {agentName} keeps so it knows them next time. Before each piece of work it reads the ones that
        fit. Only {agentName} uses these notes. Correct or remove any that are wrong.
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
                  }}
                  onRemove={() => setRemoving(note)}
                />
              ))}
            </ul>
            <p className="caption muted" style={{ marginTop: 'var(--space-3)' }}>
              {data.total} of {data.limit} notes.
            </p>
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
            onChange={(event) => setContent(event.target.value.slice(0, MEMORY_MAX_CHARS))}
            maxLength={MEMORY_MAX_CHARS}
            rows={3}
            hint="One fact in plain words. Do not include passwords, keys or card numbers."
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
          onChange={(event) => setEditText(event.target.value.slice(0, MEMORY_MAX_CHARS))}
          maxLength={MEMORY_MAX_CHARS}
          rows={4}
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
    </Card>
  )
}
