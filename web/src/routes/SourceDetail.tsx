import { useId, useRef, useState } from 'react'
import type { DragEvent, KeyboardEvent } from 'react'
import {
  Button,
  Card,
  DataTable,
  EmptyState,
  Eyebrow,
  Notice,
  PageHeader,
  StatRow,
  StatTile,
  StatusTag,
  Time,
} from '../components/ui'
import type { Column } from '../components/ui'
import { BackLink, EmptyIcon, QueryState } from '../components/ui/QueryState'
// Imported from its own module, not the ../components/ui barrel: this screen is lazy-loaded, and
// the barrel is also part of the main bundle, so going through it created a circular chunk
// dependency (Rollup warned of a "broken execution order").
import { FilterBar, FilterEmpty } from '../components/ui/FilterBar'
import { ApiError, describeApiError } from '../lib/api'
import { formatCount, formatRelative, nameList } from '../lib/format'
import { embeddingProviderLabel, mediaTypeLabel, sourceKindLabel } from '../lib/labels'
import { useSource, useSourceDocuments, useReindexSource, useUploadDocument } from '../lib/queries'
import type { IngestResult, SourceDocument } from '../lib/queries'
import { useDocumentTitle } from '../lib/router'
import { can } from '../lib/session'
import { useToast } from '../lib/toast'
import { useListFilter } from '../lib/useListFilter'
import { useNow } from '../lib/useNow'

/** The service refuses anything larger, so a file over it is caught here before it is sent. */
const MAX_UPLOAD_BYTES = 25 * 1024 * 1024

const isRemoved = (document: SourceDocument) => document.removedAtSource || document.status === 'tombstoned'

/**
 * A document somebody should look at: one that could not be indexed, or one that is gone from
 * where it came from. Decided by status alone. skipReason is omitted from the JSON when it is null,
 * so comparing it with null once counted every indexed document as a problem.
 */
function needsAttention(document: SourceDocument): boolean {
  return isRemoved(document) || document.status === 'skipped' || document.status === 'failed'
}

const DOCUMENT_FACETS = {
  state: (document: SourceDocument) =>
    needsAttention(document) ? 'attention' : document.status === 'indexed' ? 'indexed' : null,
}

const documentText = (document: SourceDocument) => document.title

const countOf = (count: number, noun: string) => `${formatCount(count)} ${noun}${count === 1 ? '' : 's'}`

const withStop = (text: string) => {
  const trimmed = text.trim()
  return /[.?]$/.test(trimmed) ? trimmed : `${trimmed}.`
}

/**
 * How the service begins the one source error it records today: documents indexed for keyword
 * search whose vectors could not be written, because the vector store or the embedding provider
 * failed. The rest of the message is the underlying exception, which is kept as the detail.
 */
const VECTOR_ERROR_PREFIX = 'Vector indexing is unavailable'

function vectorErrorDetail(lastError: string): string | null {
  if (!lastError.startsWith(VECTOR_ERROR_PREFIX)) return null
  return lastError.slice(VECTOR_ERROR_PREFIX.length).replace(/^[\s:]+/, '').trim()
}

const COLUMNS: Column<SourceDocument>[] = [
  {
    key: 'title',
    header: 'Document',
    sortValue: (row) => row.title,
    render: (row) => (
      <div className="stack" style={{ gap: 'var(--space-1)' }}>
        <span>{row.title}</span>
        {row.skipReason && <p className="caption">{row.skipReason}</p>}
      </div>
    ),
  },
  {
    key: 'type',
    header: 'Type',
    sortValue: (row) => mediaTypeLabel(row.mediaType, row.title),
    render: (row) => (
      <span className="muted" title={row.mediaType}>
        {mediaTypeLabel(row.mediaType, row.title)}
      </span>
    ),
  },
  {
    key: 'status',
    header: 'Status',
    render: (row) => <StatusTag kind="document" status={isRemoved(row) ? 'tombstoned' : row.status} />,
  },
  {
    key: 'chunks',
    header: 'Passages',
    numeric: true,
    sortValue: (row) => row.chunkCount,
    render: (row) => formatCount(row.chunkCount),
  },
  {
    key: 'indexed',
    header: 'Indexed',
    sortValue: (row) => (row.indexedAt ? Date.parse(row.indexedAt) : null),
    render: (row) => <Time iso={row.indexedAt} className="muted" />,
  },
]

/** The source's last error in plain words, with the service's own message kept as the detail. */
function SourceErrorNotice({ lastError, canManage }: { lastError: string; canManage: boolean }) {
  const vectorDetail = vectorErrorDetail(lastError)
  return (
    <Notice tone="warning">
      <div className="stack" style={{ gap: 'var(--space-1)', flex: 1, minWidth: 0 }}>
        <p>
          {vectorDetail === null
            ? withStop(lastError)
            : 'Meaning-based search is not available for this source, because its documents could not be added to the vector index.'}
        </p>
        <p>
          Keyword search still works.
          {canManage ? ' Use Index again to retry once the cause is fixed.' : ''}
        </p>
        {vectorDetail && (
          <p className="caption" style={{ overflowWrap: 'anywhere' }}>
            Detail from the service: {withStop(vectorDetail)}
          </p>
        )}
      </div>
    </Notice>
  )
}

/* ---- Upload outcomes ---------------------------------------------------------------------------- */

/** A file the service took, whatever it then made of it. */
type Ingested = { name: string; status: IngestResult['status']; chunkCount: number; detail: string | null }

/** A file that never reached the index: refused before sending, or by the service. */
type Rejected = { name: string; status: 'rejected'; message: string }

type UploadOutcome = Ingested | Rejected

type Feedback = { tone: 'success' | 'info' | 'error'; text: string }

/** A file the service would refuse, caught before it is sent. */
function checkFile(file: File): string | null {
  if (file.size === 0) return `${file.name} is empty.`
  if (file.size > MAX_UPLOAD_BYTES) return `${file.name} is larger than 25 MB.`
  return null
}

function uploadFailure(name: string, err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 413) return `${name} is larger than 25 MB.`
    const problem = err.code === 'validation_failed' ? err.fields.file : undefined
    if (problem) return problem === 'must not be empty' ? `${name} is empty.` : withStop(`${name} ${problem}`)
    return `${name} could not be uploaded. ${describeApiError(err)}`
  }
  return `Could not upload ${name}.`
}

function singleFeedback(outcome: UploadOutcome): Feedback {
  switch (outcome.status) {
    case 'rejected':
      return { tone: 'error', text: outcome.message }
    case 'indexed':
      // The detail is only present when the vector store was down: the document is findable by
      // keyword, and meaning-based search is missing until the source is indexed again.
      return outcome.detail
        ? { tone: 'info', text: `${outcome.name}: ${withStop(outcome.detail)}` }
        : { tone: 'success', text: `${outcome.name} was indexed into ${countOf(outcome.chunkCount, 'passage')}.` }
    case 'unchanged':
      return { tone: 'info', text: `${outcome.name} has not changed since it was last indexed.` }
    case 'skipped':
      return {
        tone: 'info',
        text: outcome.detail
          ? `${outcome.name} could not be indexed. ${withStop(outcome.detail)}`
          : `${outcome.name} could not be indexed.`,
      }
    case 'failed':
      return {
        tone: 'error',
        text: outcome.detail
          ? `${outcome.name} could not be indexed. ${withStop(outcome.detail)}`
          : `${outcome.name} could not be indexed.`,
      }
  }
}

/** One message for several files, so four uploads do not stack four toasts. */
function summaryFeedback(outcomes: UploadOutcome[]): Feedback {
  const total = outcomes.length
  const ingested = outcomes.filter((outcome): outcome is Ingested => outcome.status !== 'rejected')
  const rejected = outcomes.filter((outcome): outcome is Rejected => outcome.status === 'rejected').map((o) => o.message)
  const named = (status: Ingested['status']) => ingested.filter((outcome) => outcome.status === status)
  const indexed = named('indexed')
  const unchanged = named('unchanged')
  const skipped = named('skipped')
  const failed = named('failed')
  const uploaded = ingested.length

  const parts: string[] = []
  if (indexed.length > 0) parts.push(`${formatCount(indexed.length)} indexed`)
  if (unchanged.length > 0) parts.push(`${formatCount(unchanged.length)} unchanged`)
  if (skipped.length > 0) {
    parts.push(`${formatCount(skipped.length)} not indexable (${nameList(skipped.map((outcome) => outcome.name))})`)
  }
  if (failed.length > 0) {
    parts.push(`${formatCount(failed.length)} failed (${nameList(failed.map((outcome) => outcome.name))})`)
  }

  const sentences: string[] = []
  if (uploaded > 0) {
    const lead = rejected.length === 0 ? `Uploaded ${formatCount(total)} files` : `Uploaded ${formatCount(uploaded)} of ${formatCount(total)} files`
    sentences.push(`${lead}: ${parts.join(', ')}.`)
  } else {
    sentences.push(`None of the ${formatCount(total)} files was uploaded.`)
  }
  sentences.push(...rejected.slice(0, 3))
  if (rejected.length > 3) sentences.push(`${countOf(rejected.length - 3, 'more file')} could not be uploaded.`)

  // Every indexed file carries the same vector-store detail when the store is down, so it is said once.
  const vectorDetail = indexed.find((outcome) => outcome.detail)?.detail
  if (vectorDetail) sentences.push(withStop(vectorDetail))

  const tone = failed.length > 0 || rejected.length > 0 ? 'error' : skipped.length > 0 || vectorDetail ? 'info' : 'success'
  return { tone, text: sentences.join(' ') }
}

/* ---- Screen ------------------------------------------------------------------------------------- */

export function SourceDetail({ id }: { id: string }) {
  const sourceQuery = useSource(id)
  const documentsQuery = useSourceDocuments(id)
  const reindexSource = useReindexSource(id)
  const uploadDocument = useUploadDocument(id)
  const canManage = can('knowledge:source_manage')
  // Chat is where documents are searched: the screen needs chat:use and the search knowledge:query.
  const canSearch = can('chat:use') && can('knowledge:query')
  const toast = useToast()
  const now = useNow()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const dropzoneHintId = useId()
  const busyRef = useRef(false)
  const [dragActive, setDragActive] = useState(false)
  const [progress, setProgress] = useState<{ index: number; total: number; name: string } | null>(null)
  const uploading = progress !== null

  useDocumentTitle(sourceQuery.data?.name)

  const filter = useListFilter({ rows: documentsQuery.data, text: documentText, facets: DOCUMENT_FACETS })

  function show(feedback: Feedback) {
    toast[feedback.tone](feedback.text)
  }

  async function handleReindex() {
    try {
      const result = await reindexSource.mutateAsync()
      if (result.vectorised) {
        toast.success(`Indexed ${countOf(result.documentsQueued, 'document')} again.`)
      } else if (result.documentsQueued === 0) {
        toast.info('There are no indexed documents to index again.')
      } else {
        toast.info(result.detail ?? 'The vector store is still unavailable. Keyword search is unaffected.')
      }
    } catch (err) {
      toast.error(describeApiError(err))
    }
  }

  /** Uploads the files one after another, then reports once. */
  async function uploadFiles(files: File[]) {
    if (files.length === 0 || busyRef.current) return
    busyRef.current = true
    const outcomes: UploadOutcome[] = []
    try {
      for (const [index, file] of files.entries()) {
        setProgress({ index: index + 1, total: files.length, name: file.name })
        const problem = checkFile(file)
        if (problem) {
          outcomes.push({ name: file.name, status: 'rejected', message: problem })
          continue
        }
        try {
          const result = await uploadDocument.mutateAsync(file)
          outcomes.push({ name: file.name, status: result.status, chunkCount: result.chunkCount, detail: result.detail ?? null })
        } catch (err) {
          outcomes.push({ name: file.name, status: 'rejected', message: uploadFailure(file.name, err) })
        }
      }
    } finally {
      busyRef.current = false
      setProgress(null)
    }
    const [only] = outcomes
    if (outcomes.length === 1 && only) show(singleFeedback(only))
    else if (outcomes.length > 1) show(summaryFeedback(outcomes))
  }

  function openFileDialog() {
    if (busyRef.current) return
    fileInputRef.current?.click()
  }

  function handleDragOver(event: DragEvent<HTMLDivElement>) {
    // Always refused to the browser, even mid-upload, or a dropped file would replace the page.
    event.preventDefault()
    if (!busyRef.current) setDragActive(true)
  }

  function handleDragLeave(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    // Moving over the zone's own text fires dragleave too; only leaving the zone counts.
    if (event.relatedTarget instanceof Node && event.currentTarget.contains(event.relatedTarget)) return
    setDragActive(false)
  }

  function handleDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setDragActive(false)
    if (busyRef.current) return
    void uploadFiles(Array.from(event.dataTransfer.files))
  }

  function handleDropzoneKey(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key !== 'Enter' && event.key !== ' ') return
    event.preventDefault()
    openFileDialog()
  }

  const progressText = progress
    ? progress.total > 1
      ? `Uploading ${progress.index} of ${progress.total}: ${progress.name}`
      : `Uploading ${progress.name}`
    : ''

  return (
    <div className="page">
      <BackLink href="/knowledge" label="Back to knowledge" />
      <QueryState
        query={sourceQuery}
        permission="knowledge:read"
        what="this source"
        rows={4}
      >
        {(source) => (
          <>
            <PageHeader
              eyebrow={sourceKindLabel(source.kind)}
              title={source.name}
              description="Documents uploaded here can be searched in Chat, with the passage each result came from."
              meta={<StatusTag kind="source" status={source.status} />}
              action={
                <>
                  {canSearch && source.documentCount > 0 && (
                    <a className="button button-outline button-sm" href="/chat">
                      Search your documents
                    </a>
                  )}
                  {canManage && (
                    <Button
                      variant="outline"
                      className="button-sm"
                      onClick={handleReindex}
                      loading={reindexSource.isPending}
                      disabled={source.status === 'ingesting' || uploading}
                      title="Rebuild meaning-based search for the documents already indexed"
                    >
                      Index again
                    </Button>
                  )}
                </>
              }
            />

            {source.lastError && <SourceErrorNotice lastError={source.lastError} canManage={canManage} />}

            <div style={{ marginTop: 'var(--space-6)' }}>
              <StatRow>
                <StatTile label="Documents" value={formatCount(source.documentCount)} unit="indexed" />
                <StatTile label="Passages" value={formatCount(source.chunkCount)} />
                <StatTile
                  label="Embedding"
                  value={source.embeddingModel || 'Not configured'}
                  note={`${embeddingProviderLabel(source.embeddingProvider)}, ${formatCount(source.embeddingDimension)} dimensions`}
                />
                <StatTile
                  label="Last indexed"
                  value={source.lastIngestedAt ? formatRelative(source.lastIngestedAt, now) : 'Never'}
                />
              </StatRow>
              <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
                Counts cover indexed documents only, as of the most recent indexing of this source.
              </p>
            </div>

            <section style={{ marginTop: 'var(--space-7)' }} aria-labelledby="source-documents-heading">
              <div
                className="row"
                style={{
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  flexWrap: 'wrap',
                  gap: 'var(--space-3)',
                  marginBottom: 'var(--space-4)',
                }}
              >
                <Eyebrow as="h2" id="source-documents-heading">
                  Documents
                </Eyebrow>
                {canManage && (
                  <Button
                    variant="outline"
                    icon={
                      <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
                        <path d="M7 1.5V12.5M1.5 7h11" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
                      </svg>
                    }
                    onClick={openFileDialog}
                    loading={uploading}
                  >
                    Upload documents
                  </Button>
                )}
              </div>

              {canManage && (
                <>
                  {/* Outside the drop zone, so the picker's own click does not bubble back into it. */}
                  <input
                    ref={fileInputRef}
                    type="file"
                    multiple
                    className="dropzone-input"
                    tabIndex={-1}
                    aria-hidden="true"
                    onChange={(event) => {
                      const files = Array.from(event.target.files ?? [])
                      // Cleared so choosing the same file again still counts as a change.
                      event.target.value = ''
                      void uploadFiles(files)
                    }}
                  />
                  <div
                    className={`dropzone ${dragActive ? 'dropzone-active' : ''}`.trim()}
                    onDragOver={handleDragOver}
                    onDragLeave={handleDragLeave}
                    onDrop={handleDrop}
                    onClick={openFileDialog}
                    onKeyDown={handleDropzoneKey}
                    role="button"
                    tabIndex={0}
                    // The same words as the visible label, so a voice command naming what is on
                    // screen finds the control; the size limit is its description.
                    aria-label="Drag files here, or click to choose them"
                    aria-describedby={dropzoneHintId}
                    aria-disabled={uploading || undefined}
                  >
                    <svg
                      width="32"
                      height="32"
                      viewBox="0 0 24 24"
                      fill="none"
                      aria-hidden="true"
                      // An svg is a block here, so text-align alone left it at the start of the zone.
                      style={{ display: 'block', margin: '0 auto var(--space-3)' }}
                    >
                      <path
                        d="M12 4v12M4 12l8-8 8 8"
                        stroke="currentColor"
                        strokeWidth="1.5"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                      />
                    </svg>
                    <p className="muted">Drag files here, or click to choose them</p>
                    <p className="caption" id={dropzoneHintId} style={{ marginTop: 'var(--space-1)' }}>
                      Up to 25 MB each. PDF, Word, plain text, Markdown and more.
                    </p>
                  </div>
                  <p
                    className="caption"
                    role="status"
                    aria-live="polite"
                    style={{ marginTop: 'var(--space-2)', marginBottom: 'var(--space-4)', minHeight: '1lh' }}
                  >
                    {progressText}
                  </p>
                </>
              )}

              <QueryState
                query={documentsQuery}
                permission="knowledge:read"
                what="the document list"
                isEmpty={(documents) => documents.length === 0}
                empty={
                  <Card as="section">
                    <EmptyState
                      icon={<EmptyIcon kind="document" />}
                      title="No documents in this source"
                      titleAs="h3"
                      body={
                        canManage
                          ? 'Upload a document above. Each one is listed here with whether it could be indexed.'
                          : 'Documents added to this source will appear here with whether each could be indexed.'
                      }
                    />
                  </Card>
                }
                rows={5}
              >
                {(documents) => {
                  const unindexable = documents.filter((document) => needsAttention(document) && !isRemoved(document))
                  const removed = documents.filter(isRemoved)
                  const selectedStates = filter.selected.state ?? []
                  const showingAttention = selectedStates.length === 1 && selectedStates[0] === 'attention'
                  const stateCounts = filter.counts.state ?? {}
                  return (
                    <>
                      {unindexable.length + removed.length > 0 && (
                        <div style={{ marginBottom: 'var(--space-4)' }}>
                          <Notice tone="warning">
                            <div className="stack" style={{ gap: 'var(--space-1)', flex: 1, minWidth: 0 }}>
                              {unindexable.length > 0 && (
                                <p>
                                  {countOf(unindexable.length, 'document')} could not be indexed:{' '}
                                  {nameList(unindexable.map((document) => document.title))}.
                                </p>
                              )}
                              {removed.length > 0 && (
                                <p>
                                  {countOf(removed.length, 'document')} {removed.length === 1 ? 'was' : 'were'} removed
                                  at source: {nameList(removed.map((document) => document.title))}.
                                </p>
                              )}
                            </div>
                            {/* One button that changes its words rather than one that disappears, so
                                focus stays on it after the list changes. */}
                            <Button
                              variant="quiet"
                              className="button-sm"
                              onClick={() => filter.setOnly('state', showingAttention ? null : 'attention')}
                            >
                              {showingAttention ? 'Show all documents' : 'Show them'}
                            </Button>
                          </Notice>
                        </div>
                      )}

                      <FilterBar
                        searchLabel="Search documents"
                        query={filter.query}
                        onQueryChange={filter.setQuery}
                        placeholder="Document title"
                        facets={[
                          {
                            param: 'state',
                            label: 'Show',
                            options: [
                              { value: 'indexed', label: 'Indexed', count: stateCounts.indexed ?? 0 },
                              { value: 'attention', label: 'Needs attention', count: stateCounts.attention ?? 0 },
                            ],
                            selected: selectedStates,
                            onToggle: (value) => filter.toggle('state', value),
                          },
                        ]}
                        shown={filter.filtered.length}
                        total={filter.total}
                        active={filter.active}
                        onClear={filter.clear}
                      />

                      <Card as="section">
                        {filter.filtered.length === 0 ? (
                          <FilterEmpty onClear={filter.clear} what="documents" />
                        ) : (
                          <DataTable
                            columns={COLUMNS}
                            rows={filter.filtered}
                            getKey={(row) => row.id}
                            caption="Documents in this source and whether each one could be indexed."
                          />
                        )}
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
