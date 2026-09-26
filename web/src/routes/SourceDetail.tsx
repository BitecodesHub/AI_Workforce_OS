import { useRef, useState } from 'react'
import { Button, Card, DataTable, EmptyState, Eyebrow, Notice, PageHeader, StatRow, StatTile, StatusTag, Tag } from '../components/ui'
import type { Column } from '../components/ui'
import { BackLink, EmptyIcon, QueryState } from '../components/ui/QueryState'
import { timeAgo } from '../lib/api'
import { useSource, useSourceDocuments, useReindexSource, useUploadDocument } from '../lib/queries'
import { useToast } from '../lib/toast'
import type { SourceDocument } from '../lib/queries'

const COLUMNS: Column<SourceDocument>[] = [
  {
    key: 'title',
    header: 'Document',
    render: (row) => (
      <div>
        <span>{row.title}</span>
        {row.skipReason && <p className="caption">{row.skipReason}</p>}
      </div>
    ),
  },
  { key: 'type', header: 'Type', render: (row) => <span className="muted">{row.mediaType}</span> },
  {
    key: 'status',
    header: 'Status',
    render: (row) => (row.removedAtSource ? <Tag tone="neutral">Removed at source</Tag> : <StatusTag status={row.status} />),
  },
  { key: 'chunks', header: 'Passages', numeric: true, render: (row) => row.chunkCount },
  { key: 'indexed', header: 'Indexed', render: (row) => <span className="muted">{timeAgo(row.indexedAt)}</span> },
]

export function SourceDetail({ id }: { id: string }) {
  const sourceQuery = useSource(id)
  const documentsQuery = useSourceDocuments(id)
  const reindexSource = useReindexSource(id)
  const uploadDocument = useUploadDocument(id)
  const { success, error, info } = useToast()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [dragActive, setDragActive] = useState(false)

  async function handleReindex() {
    try {
      await reindexSource.mutateAsync()
      success('Reindex queued')
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Could not reindex source'
      error(message)
    }
  }

  async function handleFileSelect(files: FileList | null) {
    if (!files || files.length === 0) return
    const file = files[0]
    if (!file) return
    if (file.size > 25 * 1024 * 1024) {
      error('File exceeds 25 MB limit')
      return
    }
    try {
      const result = await uploadDocument.mutateAsync(file)
      const statusMessages: Record<string, string> = {
        indexed: 'Document indexed',
        skipped: 'Document skipped',
        unchanged: 'Document unchanged',
        failed: 'Document failed to index',
      }
      const message = statusMessages[result.status] || `Document ${result.status}`
      const detail = result.detail ? ` - ${result.detail}` : ''
      if (result.status === 'indexed') {
        success(`${message}${detail}`)
      } else if (result.status === 'failed') {
        error(`${message}${detail}`)
      } else {
        info(`${message}${detail}`)
      }
    } catch (err) {
      const apiErr = err as { status?: number; message?: string }
      if (apiErr.status === 422) {
        error('File exceeds 25 MB limit')
      } else {
        const message = err instanceof Error ? err.message : 'Could not upload document'
        error(message)
      }
    }
  }

  function handleDragOver(event: React.DragEvent) {
    event.preventDefault()
    setDragActive(true)
  }

  function handleDragLeave(event: React.DragEvent) {
    event.preventDefault()
    setDragActive(false)
  }

  function handleDrop(event: React.DragEvent) {
    event.preventDefault()
    setDragActive(false)
    handleFileSelect(event.dataTransfer.files)
  }

  function openFileDialog() {
    fileInputRef.current?.click()
  }

  return (
    <div className="page">
      <BackLink href="/knowledge" label="Back to knowledge" />
      <QueryState query={sourceQuery} permission="knowledge:read" what="this source" rows={4}>
        {(source) => (
          <>
            <PageHeader
              eyebrow={source.kind}
              title={source.name}
              description={
                source.lastIngestedAt
                  ? `Last indexed ${timeAgo(source.lastIngestedAt)}.`
                  : 'This source has not been indexed yet.'
              }
              action={
                <div className="row" style={{ gap: 'var(--space-3)', alignItems: 'center' }}>
                  <StatusTag status={source.status} />
                  <Button
                    variant="outline"
                    onClick={handleReindex}
                    loading={reindexSource.isPending}
                    disabled={source.status === 'ingesting'}
                    style={{ padding: '6px var(--space-4)', fontSize: 'var(--text-caption)' }}
                  >
                    Index again
                  </Button>
                </div>
              }
            />

            {source.lastError && <Notice tone="warning">{source.lastError}</Notice>}

            <div style={{ marginTop: 'var(--space-6)' }}>
              <StatRow>
                <StatTile label="Documents" value={source.documentCount.toLocaleString('en-AU')} unit="found" />
                <StatTile label="Passages" value={source.chunkCount.toLocaleString('en-AU')} />
                <StatTile
                  label="Embedding"
                  value={source.embeddingModel || 'Not configured'}
                  note={`${source.embeddingProvider || 'No provider'}, ${source.embeddingDimension} dimensions`}
                />
                <StatTile label="Last indexed" value={source.lastIngestedAt ? timeAgo(source.lastIngestedAt) : 'Never'} />
              </StatRow>
              <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
                Source: the most recent ingestion run for this source.
              </p>
            </div>

            <section style={{ marginTop: 'var(--space-7)' }}>
              <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: 'var(--space-4)' }}>
                <Eyebrow>Documents</Eyebrow>
                <Button
                  variant="outline"
                  icon={
                    <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
                      <path d="M7 1.5V12.5M1.5 7h11" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
                    </svg>
                  }
                  onClick={openFileDialog}
                  disabled={uploadDocument.isPending}
                >
                  Upload document
                </Button>
              </div>

              <div
                className={`dropzone ${dragActive ? 'dropzone-active' : ''}`}
                onDragOver={handleDragOver}
                onDragLeave={handleDragLeave}
                onDrop={handleDrop}
                onClick={openFileDialog}
                role="button"
                tabIndex={0}
                aria-label="Upload a document"
              >
                <input
                  ref={fileInputRef}
                  type="file"
                  className="dropzone-input"
                  onChange={(e) => handleFileSelect(e.target.files)}
                  aria-hidden="true"
                />
                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" aria-hidden="true" style={{ marginBottom: 'var(--space-3)' }}>
                  <path d="M12 4v12M4 12l8-8 8 8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
                <p className="muted">Drag and drop a file, or click to browse</p>
                <p className="caption" style={{ marginTop: 'var(--space-1)' }}>Maximum 25 MB. PDF, DOCX, TXT, MD and more.</p>
              </div>

              <QueryState
                query={documentsQuery}
                permission="knowledge:read"
                what="this source's documents"
                isEmpty={(documents) => documents.length === 0}
                empty={
                  <Card as="section">
                    <EmptyState
                      icon={<EmptyIcon kind="document" />}
                      title="No documents in this source"
                      body="Documents added to this source will appear here with their indexing outcome."
                    />
                  </Card>
                }
                rows={5}
              >
                {(documents) => {
                  const problemDocuments = documents.filter(
                    (document) =>
                      document.removedAtSource ||
                      document.skipReason !== null ||
                      document.status === 'skipped' ||
                      document.status === 'failed',
                  )
                  return (
                    <>
                      {problemDocuments.length > 0 && (
                        <div style={{ marginBottom: 'var(--space-4)' }}>
                          <Notice tone="warning">
                            {problemDocuments.length} {problemDocuments.length === 1 ? 'document needs' : 'documents need'} attention.
                          </Notice>
                        </div>
                      )}
                      <Card as="section">
                        <DataTable
                          columns={COLUMNS}
                          rows={documents}
                          getKey={(row) => row.id}
                          caption="Documents in this source and whether each one could be indexed."
                        />
                      </Card>
                    </>
                  )
                }}
              </QueryState>
            </section>
          </>
        )}
      </QueryState>
    </div>
  )
}