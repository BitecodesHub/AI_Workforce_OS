import { useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Card, ConfirmDialog, Eyebrow, Input, Notice, Tag, Textarea } from '../ui'
import { QueryState } from '../ui/QueryState'
import { describeApiError } from '../../lib/api'
import {
  useAddAgentNote,
  useAgentDocuments,
  useDeleteAgentDocument,
  useUploadAgentDocument,
} from '../../lib/agentMemoryQueries'
import type { SourceDocument } from '../../lib/queries'
import { can } from '../../lib/session'
import { useToast } from '../../lib/toast'

/*
 * An AI employee's own documents: files and typed notes that only this agent reads when it
 * searches. They are kept apart from the workspace's Knowledge, which every agent and Chat search.
 */

const NOTE_MAX = 20_000

export function AgentDocumentsCard({ agentId, agentName }: { agentId: string; agentName: string }) {
  const toast = useToast()
  const canEdit = can('agent:update')
  const documents = useAgentDocuments(agentId, { enabled: can('agent:read') })
  const upload = useUploadAgentDocument(agentId)
  const addNote = useAddAgentNote(agentId)
  const remove = useDeleteAgentDocument(agentId)
  const fileInput = useRef<HTMLInputElement | null>(null)

  const [title, setTitle] = useState('')
  const [text, setText] = useState('')
  const [failure, setFailure] = useState<string | null>(null)
  const [removing, setRemoving] = useState<SourceDocument | null>(null)

  async function chooseFile(file: File | undefined) {
    if (!file) return
    setFailure(null)
    try {
      await upload.mutateAsync(file)
      toast.success(`Added ${file.name}. ${agentName} can search it once it has been read.`)
    } catch (error) {
      setFailure(describeApiError(error))
    } finally {
      if (fileInput.current) fileInput.current.value = ''
    }
  }

  async function submitNote(event: FormEvent) {
    event.preventDefault()
    if (!title.trim() || !text.trim()) return
    setFailure(null)
    try {
      await addNote.mutateAsync({ title: title.trim(), text: text.trim() })
      setTitle('')
      setText('')
      toast.success('Note added.')
    } catch (error) {
      setFailure(describeApiError(error))
    }
  }

  async function confirmRemove() {
    if (!removing) return
    try {
      await remove.mutateAsync(removing.id)
      toast.success('Removed.')
    } catch (error) {
      toast.error(describeApiError(error))
    } finally {
      setRemoving(null)
    }
  }

  return (
    <Card as="section">
      <Eyebrow as="h2">Its own documents</Eyebrow>
      <p className="muted" style={{ marginBottom: 'var(--space-5)', maxWidth: '62ch' }}>
        Files and notes only {agentName} reads. They sit beside the workspace&apos;s Knowledge, which everyone and every
        agent can search, and are not part of it.
      </p>

      <QueryState
        query={documents}
        permission="agent:read"
        what="this agent's documents"
        rows={2}
        isEmpty={(rows) => rows.length === 0}
        empty={<p className="muted">{agentName} has no documents of its own yet.</p>}
      >
        {(rows) => (
          <ul style={{ margin: 0, padding: 0, listStyle: 'none' }}>
            {rows.map((document) => (
              <li
                key={document.id}
                className="row"
                style={{
                  justifyContent: 'space-between',
                  gap: 'var(--space-3)',
                  borderTop: '1px solid var(--line)',
                  paddingTop: 'var(--space-3)',
                  paddingBottom: 'var(--space-3)',
                }}
              >
                <span style={{ overflowWrap: 'anywhere' }}>
                  {document.title}{' '}
                  {document.status !== 'ready' && document.status !== 'indexed' && (
                    <Tag tone="warning">{document.skipReason ?? 'Not searchable yet'}</Tag>
                  )}
                </span>
                {canEdit && (
                  <Button variant="quiet" onClick={() => setRemoving(document)}>
                    Remove
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </QueryState>

      {canEdit && (
        <div className="stack" style={{ gap: 'var(--space-4)', marginTop: 'var(--space-5)' }}>
          {failure && (
            <Notice tone="warning" live>
              {failure}
            </Notice>
          )}
          <div>
            <input
              ref={fileInput}
              type="file"
              hidden
              aria-label={`Add a file for ${agentName}`}
              onChange={(event) => void chooseFile(event.target.files?.[0])}
            />
            <Button variant="outline" loading={upload.isPending} onClick={() => fileInput.current?.click()}>
              Add a file
            </Button>
          </div>
          <form className="stack" style={{ gap: 'var(--space-3)' }} onSubmit={submitNote} noValidate>
            <Input
              label="Note title"
              value={title}
              maxLength={120}
              onChange={(event) => setTitle(event.target.value)}
              placeholder="For example: How we word refund replies"
            />
            <Textarea
              label="Note"
              value={text}
              rows={4}
              maxLength={NOTE_MAX}
              onChange={(event) => setText(event.target.value.slice(0, NOTE_MAX))}
            />
            <div>
              <Button type="submit" loading={addNote.isPending} disabled={!title.trim() || !text.trim()}>
                Add the note
              </Button>
            </div>
          </form>
        </div>
      )}

      <ConfirmDialog
        open={removing !== null}
        onClose={() => setRemoving(null)}
        onConfirm={confirmRemove}
        eyebrow="Its own documents"
        title={`Remove ${removing?.title ?? 'this'}?`}
        description={`${agentName} will no longer find it when it searches. This cannot be undone.`}
        confirmLabel="Remove"
        cancelLabel="Keep it"
        tone="danger"
        loading={remove.isPending}
      />
    </Card>
  )
}
