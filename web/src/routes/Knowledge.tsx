import { useState } from 'react'
import { Button, Card, DataTable, Dialog, EmptyState, Eyebrow, Input, Notice, PageHeader, Select, StatRow, StatTile, StatusTag } from '../components/ui'
import type { Column } from '../components/ui'
import { EmptyIcon } from '../components/ui/QueryState'
import { useToast } from '../lib/toast'
import { useSources, useCreateSource } from '../lib/queries'
import { can } from '../lib/session'
import { QueryState } from '../components/ui/QueryState'
import { timeAgo } from '../lib/api'

const KINDS = [
  { value: 'upload', label: 'Upload' },
  { value: 'gdrive', label: 'Google Drive' },
  { value: 'onedrive', label: 'OneDrive' },
  { value: 'sharepoint', label: 'SharePoint' },
  { value: 'confluence', label: 'Confluence' },
  { value: 'github', label: 'GitHub' },
] as const

const COLUMNS: Column<Source>[] = [
  { key: 'name', header: 'Source', render: (row) => (
    <a href={`/knowledge/${row.id}`} className="link">{row.name}</a>
  )},
  { key: 'kind', header: 'Type', render: (row) => <span className="muted">{row.kind}</span> },
  {
    key: 'status',
    header: 'Status',
    render: (row) => <StatusTag status={row.status} />,
  },
  { key: 'documentCount', header: 'Indexed', numeric: true, render: (row) => row.documentCount.toLocaleString('en-AU') },
  { key: 'chunkCount', header: 'Passages', numeric: true, render: (row) => row.chunkCount.toLocaleString('en-AU') },
  { key: 'lastIngestedAt', header: 'Last indexed', render: (row) => <span className="muted">{row.lastIngestedAt ? timeAgo(row.lastIngestedAt) : 'Never'}</span> },
]

type Source = {
  id: string
  name: string
  kind: string
  status: string
  documentCount: number
  chunkCount: number
  embeddingProvider: string
  embeddingModel: string
  embeddingDimension: number
  lastIngestedAt: string | null
  lastError: string | null
}

export function Knowledge() {
  const [dialogOpen, setDialogOpen] = useState(false)
  const [name, setName] = useState('')
  const [kind, setKind] = useState('upload')
  const { success, error } = useToast()
  const sourcesQuery = useSources()
  const createSource = useCreateSource()
  const canManage = can('knowledge:source_manage')

  async function handleCreate(event: React.FormEvent) {
    event.preventDefault()
    if (!name.trim()) return
    try {
      await createSource.mutateAsync({ name: name.trim(), kind })
      success('Source created')
      setDialogOpen(false)
      setName('')
      setKind('upload')
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Could not create source'
      error(message)
    }
  }

  return (
    <div className="page">
      <PageHeader
        eyebrow="What the workforce knows"
        title="Knowledge"
        description="Documents agents answer from. Every answer cites the passages it relied on."
        action={canManage && (
          <Button onClick={() => setDialogOpen(true)}>Connect a source</Button>
        )}
      />

      <QueryState
        query={sourcesQuery}
        permission="knowledge:read"
        what="knowledge sources"
        isEmpty={(sources) => sources.length === 0}
        empty={
          <Card as="section">
            <EmptyState
              icon={<EmptyIcon kind="document" />}
              title="No knowledge sources yet"
              body="Connect a source so agents can answer from your documents."
              action={canManage && (
                <Button onClick={() => setDialogOpen(true)}>Connect your first source</Button>
              )}
            />
          </Card>
        }
        rows={4}
      >
        {(sources) => (
          <>
            {sources.some((s) => s.status === 'reconnect_required' || s.status === 'failed') && (
              <Notice tone="warning">
                Some sources need attention. Check the status column for details.
              </Notice>
            )}

            <div style={{ marginTop: 'var(--space-6)' }}>
              <StatRow>
                <StatTile label="Sources" value={sources.length} unit="connected" note={sources.some((s) => s.status !== 'ready' && s.status !== 'idle') ? 'Some need attention' : 'All healthy'} />
                <StatTile label="Documents" value={sources.reduce((sum, s) => sum + s.documentCount, 0).toLocaleString('en-AU')} unit="indexed" />
                <StatTile label="Passages" value={sources.reduce((sum, s) => sum + s.chunkCount, 0).toLocaleString('en-AU')} note="Searchable with citations" />
                <StatTile label="Embedding model" value={sources[0]?.embeddingModel || 'Not configured'} note={`${sources[0]?.embeddingProvider || 'No provider'}, ${sources[0]?.embeddingDimension || 0} dimensions`} />
              </StatRow>
              <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
                Source: ingestion run records. A skipped document is one with no text that can be indexed,
                such as a scanned image or an encrypted file.
              </p>
            </div>

            <section style={{ marginTop: 'var(--space-7)' }}>
              <Card as="section">
                <Eyebrow>Connected sources</Eyebrow>
                <DataTable
                  columns={COLUMNS}
                  rows={sources}
                  getKey={(row) => row.id}
                  caption="Knowledge sources and the state of their most recent ingestion."
                />
              </Card>
            </section>
          </>
        )}
      </QueryState>

      <Dialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        eyebrow="Add knowledge source"
        title="Connect a source"
        description="Choose the kind of source and give it a name. The kind determines how documents are ingested."
      >
        <form onSubmit={handleCreate} className="stack" style={{ gap: 'var(--space-4)' }}>
          <Input
            label="Name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Policies and agreements"
            required
            autoFocus
          />
          <Select label="Kind" value={kind} onChange={(e) => setKind(e.target.value)}>
            {KINDS.map((k) => (
              <option key={k.value} value={k.value}>{k.label}</option>
            ))}
          </Select>
        </form>
        <div className="dialog-footer" style={{ marginTop: 'var(--space-6)', display: 'flex', justifyContent: 'flex-end', gap: 'var(--space-3)' }}>
          <Button variant="outline" onClick={() => setDialogOpen(false)}>Cancel</Button>
          <Button loading={createSource.isPending} onClick={handleCreate}>Create</Button>
        </div>
      </Dialog>
    </div>
  )
}