import { useId, useRef, useState } from 'react'
import type { DragEvent, KeyboardEvent } from 'react'
import {
  Button,
  Card,
  ConfirmDialog,
  DataTable,
  Dialog,
  EmptyState,
  Eyebrow,
  Input,
  Notice,
  PageHeader,
  StatRow,
  StatTile,
  StatusTag,
  Tag,
  Time,
} from '../components/ui'
import type { Column } from '../components/ui'
import { BackLink, EmptyIcon, QueryState } from '../components/ui/QueryState'
import { DocumentPassagesSheet } from '../components/knowledge/DocumentPassagesSheet'
import { KnowledgeSearch } from '../components/knowledge/KnowledgeSearch'
// Imported from its own module, not the ../components/ui barrel: this screen is lazy-loaded, and
// the barrel is also part of the main bundle, so going through it created a circular chunk
// dependency (Rollup warned of a "broken execution order").
import { FilterBar, FilterEmpty } from '../components/ui/FilterBar'
import { ApiError, describeApiError } from '../lib/api'
import { formatCount, formatDate, formatRelative, nameList } from '../lib/format'
import {
  documentNotice,
  findClashes,
  isRestricted,
  nextFreeName,
  planUploads,
  searchModeOf,
  sourceAccessNotice,
  useDeleteDocument,
  useDeleteSource,
  useUpdateSource,
  useUploadKnowledgeDocument,
} from '../lib/knowledgeQueries'
import type { ClashChoice, PlannedUpload, UploadOutcome } from '../lib/knowledgeQueries'
import { embeddingProviderLabel, mediaTypeLabel, sourceKindLabel } from '../lib/labels'
import { useSource, useSourceDocuments, useReindexSource } from '../lib/queries'
import type { Source, SourceDocument } from '../lib/queries'
import { useDocumentTitle, useRouter } from '../lib/router'
import { can } from '../lib/session'
import { useToast } from '../lib/toast'
import { useListFilter } from '../lib/useListFilter'
import { useNow } from '../lib/useNow'

/** The service refuses anything larger, so a file over it is caught here before it is sent. */
const MAX_UPLOAD_BYTES = 25 * 1024 * 1024

/**
 * A document somebody should look at: one that could not be indexed. Decided by status alone.
 * skipReason is omitted from the JSON when it is null, so comparing it with null once counted
 * every indexed document as a problem.
 */
function needsAttention(document: SourceDocument): boolean {
  return document.status === 'skipped' || document.status === 'failed'
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

/**
 * The table's columns. Anyone who can read the source may open a document's passages; deleting is
 * for someone who may delete.
 */
function documentColumns(
  onPassages: (document: SourceDocument) => void,
  onDelete: ((document: SourceDocument) => void) | null,
  busy: boolean,
): Column<SourceDocument>[] {
  const columns: Column<SourceDocument>[] = [
    {
      key: 'title',
      header: 'Document',
      sortValue: (row) => row.title,
      render: (row) => {
        const notice = documentNotice(row)
        return (
          <div className="stack" style={{ gap: 'var(--space-1)' }}>
            <span>{row.title}</span>
            {row.skipReason && <p className="caption">{row.skipReason}</p>}
            {notice && <p className="caption">{notice}</p>}
          </div>
        )
      },
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
      render: (row) => <StatusTag kind="document" status={row.status} />,
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
  columns.push({
    key: 'actions',
    header: 'Actions',
    render: (row) => (
      <div className="row" style={{ gap: 'var(--space-1)', flexWrap: 'wrap' }}>
        {row.chunkCount > 0 && (
          <Button
            variant="quiet"
            className="button-sm"
            aria-label={`View passages in ${row.title}`}
            onClick={() => onPassages(row)}
          >
            Passages
          </Button>
        )}
        {onDelete && (
          <Button
            variant="quiet"
            className="button-sm"
            aria-label={`Delete ${row.title}`}
            onClick={() => onDelete(row)}
            disabled={busy}
          >
            Delete
          </Button>
        )}
      </div>
    ),
  })
  return columns
}

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
type Ingested = {
  name: string
  status: UploadOutcome['status']
  chunkCount: number
  detail: string | null
  /** The name it is stored under: the file's own, unless both were kept and it was numbered. */
  title: string
  notice: string | null
  vectorWarning: string | null
  replacedIndexedAt: string | null
}

/** A file that never reached the index: refused before sending, or by the service. */
type Rejected = { name: string; status: 'rejected'; message: string }

type FileOutcome = Ingested | Rejected

type Feedback = { tone: 'success' | 'info' | 'error'; text: string }

function ingested(name: string, result: UploadOutcome): Ingested {
  return {
    name,
    status: result.status,
    chunkCount: result.chunkCount,
    detail: result.detail ?? null,
    title: result.title || name,
    notice: result.notice ?? null,
    vectorWarning: result.vectorWarning ?? null,
    replacedIndexedAt: result.replacedIndexedAt ?? null,
  }
}

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

/** What is worth adding to an indexed file's message: a cut at the limit, and missing meaning-based search. */
function extras(outcome: Ingested): string[] {
  return [outcome.notice, outcome.vectorWarning].filter((text): text is string => Boolean(text)).map(withStop)
}

function singleFeedback(outcome: FileOutcome): Feedback {
  switch (outcome.status) {
    case 'rejected':
      return { tone: 'error', text: outcome.message }
    case 'indexed': {
      const passages = countOf(outcome.chunkCount, 'passage')
      const lead =
        outcome.title === outcome.name
          ? `${outcome.name} was indexed into ${passages}.`
          : `${outcome.name} was kept beside the existing file as ${outcome.title}, and indexed into ${passages}.`
      const more = extras(outcome)
      return { tone: more.length > 0 ? 'info' : 'success', text: [lead, ...more].join(' ') }
    }
    case 'replaced': {
      const earlier = outcome.replacedIndexedAt
        ? `the version indexed on ${formatDate(outcome.replacedIndexedAt)}`
        : 'the earlier version'
      const lead = `Replaced ${outcome.name}: the new version was indexed into ${countOf(outcome.chunkCount, 'passage')}, and ${earlier} is no longer searchable.`
      const more = extras(outcome)
      return { tone: more.length > 0 ? 'info' : 'success', text: [lead, ...more].join(' ') }
    }
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

/**
 * One message for several files, so four uploads do not stack four toasts.
 *
 * @param skipped files left out because their names were taken and the person chose to skip them
 */
function summaryFeedback(outcomes: FileOutcome[], skipped: string[]): Feedback {
  const total = outcomes.length + skipped.length
  const taken = outcomes.filter((outcome): outcome is Ingested => outcome.status !== 'rejected')
  const rejected = outcomes.filter((outcome): outcome is Rejected => outcome.status === 'rejected').map((o) => o.message)
  const named = (status: Ingested['status']) => taken.filter((outcome) => outcome.status === status)
  const indexed = named('indexed')
  const replaced = named('replaced')
  const unchanged = named('unchanged')
  const notIndexable = named('skipped')
  const failed = named('failed')
  const uploaded = taken.length

  const parts: string[] = []
  if (indexed.length > 0) parts.push(`${formatCount(indexed.length)} indexed`)
  if (replaced.length > 0) {
    parts.push(`${formatCount(replaced.length)} replaced (${nameList(replaced.map((outcome) => outcome.name))})`)
  }
  if (unchanged.length > 0) parts.push(`${formatCount(unchanged.length)} unchanged`)
  if (notIndexable.length > 0) {
    parts.push(`${formatCount(notIndexable.length)} not indexable (${nameList(notIndexable.map((outcome) => outcome.name))})`)
  }
  if (failed.length > 0) {
    parts.push(`${formatCount(failed.length)} failed (${nameList(failed.map((outcome) => outcome.name))})`)
  }
  if (skipped.length > 0) parts.push(`${formatCount(skipped.length)} skipped (${nameList(skipped)})`)

  const sentences: string[] = []
  if (uploaded > 0) {
    const lead = uploaded === total ? `Uploaded ${formatCount(total)} files` : `Uploaded ${formatCount(uploaded)} of ${formatCount(total)} files`
    sentences.push(`${lead}: ${parts.join(', ')}.`)
  } else {
    sentences.push(`None of the ${formatCount(total)} files was uploaded.`)
  }
  sentences.push(...rejected.slice(0, 3))
  if (rejected.length > 3) sentences.push(`${countOf(rejected.length - 3, 'more file')} could not be uploaded.`)

  const renamed = indexed.filter((outcome) => outcome.title !== outcome.name)
  if (renamed.length > 0) {
    sentences.push(`Kept beside the existing ${renamed.length === 1 ? 'file' : 'files'} as ${nameList(renamed.map((outcome) => outcome.title))}.`)
  }
  const cut = taken.filter((outcome) => outcome.notice)
  if (cut.length > 0) {
    sentences.push(`Only the first part of ${nameList(cut.map((outcome) => outcome.title))} was indexed; the document list says how much.`)
  }
  // Every file carries the same vector-store warning when the store is down, so it is said once.
  const vectorWarning = taken.find((outcome) => outcome.vectorWarning)?.vectorWarning
  if (vectorWarning) sentences.push(withStop(vectorWarning))

  const tone =
    failed.length > 0 || rejected.length > 0
      ? 'error'
      : notIndexable.length > 0 || cut.length > 0 || vectorWarning
        ? 'info'
        : 'success'
  return { tone, text: sentences.join(' ') }
}

/* ---- Screen ------------------------------------------------------------------------------------- */

/** Files waiting on the person's choice, because some of their names are already in use. */
type ClashPrompt = { files: File[]; clashes: string[] }

export function SourceDetail({ id }: { id: string }) {
  const sourceQuery = useSource(id)
  const documentsQuery = useSourceDocuments(id)
  const reindexSource = useReindexSource(id)
  const uploadDocument = useUploadKnowledgeDocument(id)
  const deleteDocument = useDeleteDocument(id)
  const deleteSource = useDeleteSource()
  const updateSource = useUpdateSource(id)
  const canManage = can('knowledge:source_manage')
  // Asking in Chat needs chat:use as well as knowledge:query. Trying a search here does not: it
  // reads the documents directly, with no agent, so knowledge:query is all it takes.
  const canQuery = can('knowledge:query')
  const canAskInChat = can('chat:use') && canQuery
  const toast = useToast()
  const now = useNow()
  const { navigate } = useRouter()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const dropzoneHintId = useId()
  const uploadButtonId = useId()
  const deleteSourceFormId = useId()
  const renameFormId = useId()
  const busyRef = useRef(false)
  const [dragActive, setDragActive] = useState(false)
  const [progress, setProgress] = useState<{ index: number; total: number; name: string } | null>(null)
  const [clashPrompt, setClashPrompt] = useState<ClashPrompt | null>(null)
  const [deletingDocument, setDeletingDocument] = useState<SourceDocument | null>(null)
  const [deleteDocumentError, setDeleteDocumentError] = useState<string | null>(null)
  const [deletingSource, setDeletingSource] = useState(false)
  const [typedName, setTypedName] = useState('')
  const [deleteSourceError, setDeleteSourceError] = useState<string | null>(null)
  const [viewingPassages, setViewingPassages] = useState<SourceDocument | null>(null)
  const [renaming, setRenaming] = useState(false)
  const [newName, setNewName] = useState('')
  const [renameError, setRenameError] = useState<string | null>(null)
  const [renameDialogError, setRenameDialogError] = useState<string | null>(null)
  // Widening who can search is asked about first; narrowing it is not, since it only takes access away.
  const [confirmingOpen, setConfirmingOpen] = useState(false)
  const [openError, setOpenError] = useState<string | null>(null)
  const uploading = progress !== null

  useDocumentTitle(sourceQuery.data?.name)

  const filter = useListFilter({ rows: documentsQuery.data, text: documentText, facets: DOCUMENT_FACETS })

  const existingTitles = () => (documentsQuery.data ?? []).map((document) => document.title)

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

  /**
   * Starts an upload, first asking what to do when a file's name is already taken - by a document
   * in the source, or by another file in the same batch. The service would otherwise treat a
   * same-named file as a new version, which is right for an edited policy and wrong for an
   * unrelated contract that happens to share its name.
   */
  function startUpload(files: File[]) {
    if (files.length === 0 || busyRef.current) return
    const titles = existingTitles()
    const clashes = findClashes(files, titles)
    if (clashes.length > 0) {
      setClashPrompt({ files, clashes })
      return
    }
    void uploadFiles(planUploads(files, titles, 'replace').uploads, [])
  }

  function resolveClash(choice: ClashChoice) {
    const prompt = clashPrompt
    setClashPrompt(null)
    if (!prompt) return
    const { uploads, skipped } = planUploads(prompt.files, existingTitles(), choice)
    void uploadFiles(
      uploads,
      skipped.map((file) => file.name),
    )
  }

  /** Uploads the files one after another, then reports once. */
  async function uploadFiles(plan: PlannedUpload<File>[], skipped: string[]) {
    // Every file skipped by the person's own choice: there is nothing to report.
    if (plan.length === 0 || busyRef.current) return
    busyRef.current = true
    const outcomes: FileOutcome[] = []
    try {
      for (const [index, { file, mode }] of plan.entries()) {
        setProgress({ index: index + 1, total: plan.length, name: file.name })
        const problem = checkFile(file)
        if (problem) {
          outcomes.push({ name: file.name, status: 'rejected', message: problem })
          continue
        }
        try {
          outcomes.push(ingested(file.name, await uploadDocument.mutateAsync({ file, mode })))
        } catch (err) {
          outcomes.push({ name: file.name, status: 'rejected', message: uploadFailure(file.name, err) })
        }
      }
    } finally {
      busyRef.current = false
      setProgress(null)
    }
    const [only] = outcomes
    if (outcomes.length === 1 && only && skipped.length === 0) show(singleFeedback(only))
    else if (outcomes.length > 0) show(summaryFeedback(outcomes, skipped))
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
    startUpload(Array.from(event.dataTransfer.files))
  }

  function handleDropzoneKey(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key !== 'Enter' && event.key !== ' ') return
    event.preventDefault()
    openFileDialog()
  }

  function requestDocumentDelete(document: SourceDocument) {
    setDeleteDocumentError(null)
    setDeletingDocument(document)
  }

  function closeDocumentDelete() {
    if (deleteDocument.isPending) return
    setDeletingDocument(null)
    setDeleteDocumentError(null)
  }

  async function confirmDocumentDelete() {
    const target = deletingDocument
    if (!target) return
    setDeleteDocumentError(null)
    try {
      await deleteDocument.mutateAsync(target.id)
      setDeletingDocument(null)
      toast.success(`Deleted ${target.title}. It can no longer be found in search.`)
      // The row and its Delete button are going, so focus would fall to the page. It lands on the
      // upload control instead, beside the list, once the dialog has handed focus back.
      requestAnimationFrame(() => document.getElementById(uploadButtonId)?.focus())
    } catch (err) {
      setDeleteDocumentError(describeApiError(err))
    }
  }

  async function changeRestricted(restricted: boolean) {
    try {
      await updateSource.mutateAsync({ restricted })
      toast.success(
        restricted
          ? 'Restricted. Only people who manage knowledge can search this source now.'
          : 'Opened. Everyone with Chat access in this workspace can search this source now.',
      )
    } catch (err) {
      toast.error(describeApiError(err))
    }
  }

  function askToOpen() {
    setOpenError(null)
    updateSource.reset()
    setConfirmingOpen(true)
  }

  function closeOpenQuestion() {
    if (updateSource.isPending) return
    setConfirmingOpen(false)
  }

  async function confirmOpen() {
    setOpenError(null)
    try {
      await updateSource.mutateAsync({ restricted: false })
      setConfirmingOpen(false)
      toast.success('Opened. Everyone with Chat access in this workspace can search this source now.')
    } catch (err) {
      setOpenError(describeApiError(err))
    }
  }

  function openRename(source: Source) {
    setNewName(source.name)
    setRenameError(null)
    setRenameDialogError(null)
    updateSource.reset()
    setRenaming(true)
  }

  function closeRename() {
    if (updateSource.isPending) return
    setRenaming(false)
  }

  async function confirmRename(source: Source) {
    const trimmed = newName.trim()
    if (!trimmed || updateSource.isPending) return
    if (trimmed === source.name) {
      setRenaming(false)
      return
    }
    setRenameError(null)
    setRenameDialogError(null)
    try {
      await updateSource.mutateAsync({ name: trimmed })
      setRenaming(false)
      toast.success(`Renamed to ${trimmed}.`)
    } catch (err) {
      // A name already in use is about the field; anything else is about the request.
      if (err instanceof ApiError && err.status === 409) setRenameError(err.message)
      else setRenameDialogError(describeApiError(err, { name: 'Name' }))
    }
  }

  function openSourceDelete() {
    setTypedName('')
    setDeleteSourceError(null)
    setDeletingSource(true)
  }

  function closeSourceDelete() {
    if (deleteSource.isPending) return
    setDeletingSource(false)
  }

  async function confirmSourceDelete(source: Source) {
    if (typedName.trim() !== source.name.trim()) return
    setDeleteSourceError(null)
    try {
      await deleteSource.mutateAsync(source.id)
      setDeletingSource(false)
      toast.success(`Deleted ${source.name}. Its documents can no longer be found in search.`)
      navigate('/knowledge')
    } catch (err) {
      setDeleteSourceError(describeApiError(err))
    }
  }

  const progressText = progress
    ? progress.total > 1
      ? `Uploading ${progress.index} of ${progress.total}: ${progress.name}`
      : `Uploading ${progress.name}`
    : ''

  const [firstClash] = clashPrompt?.clashes ?? []

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
              meta={
                <>
                  <StatusTag kind="source" status={source.status} />
                  {isRestricted(source) && (
                    <Tag tone="warning" title={sourceAccessNotice(true)}>
                      Restricted
                    </Tag>
                  )}
                </>
              }
              action={
                <>
                  {canAskInChat && source.documentCount > 0 && (
                    <a className="button button-outline button-sm" href="/chat">
                      Ask in Chat
                    </a>
                  )}
                  {canManage && (
                    <Button variant="outline" className="button-sm" onClick={() => openRename(source)} disabled={uploading}>
                      Rename
                    </Button>
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
                  {canManage && (
                    <Button variant="outline" className="button-sm" onClick={openSourceDelete} disabled={uploading}>
                      Delete source
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
                {searchModeOf(source) === 'keyword' ? (
                  <StatTile label="Search" value="Keyword" note="Add an embedding model for search by meaning." />
                ) : (
                  <StatTile
                    label="Embedding"
                    value={source.embeddingModel || 'Not configured'}
                    note={`${embeddingProviderLabel(source.embeddingProvider)}, ${formatCount(source.embeddingDimension)} dimensions`}
                  />
                )}
                <StatTile
                  label="Last indexed"
                  value={source.lastIngestedAt ? formatRelative(source.lastIngestedAt, now) : 'Never'}
                />
              </StatRow>
              <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
                Counts cover indexed documents only, as of the most recent indexing of this source.
              </p>
            </div>

            {canQuery && source.documentCount > 0 && (
              <KnowledgeSearch
                sources={[source]}
                onlySourceId={source.id}
                description="Search this source directly to see what a question finds in it. This does not start an agent run."
              />
            )}

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
                    id={uploadButtonId}
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
                  <p className="caption" style={{ marginBottom: 'var(--space-3)' }}>
                    {sourceAccessNotice(isRestricted(source))}.
                  </p>
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
                      startUpload(files)
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
                      Up to 25 MB each. PDF, Word, plain text, Markdown and more. A file with the same name
                      as one already here can replace it or be kept beside it.
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
                  const unindexable = documents.filter(needsAttention)
                  const selectedStates = filter.selected.state ?? []
                  const showingAttention = selectedStates.length === 1 && selectedStates[0] === 'attention'
                  const stateCounts = filter.counts.state ?? {}
                  return (
                    <>
                      {unindexable.length > 0 && (
                        <div style={{ marginBottom: 'var(--space-4)' }}>
                          <Notice tone="warning">
                            <div className="stack" style={{ gap: 'var(--space-1)', flex: 1, minWidth: 0 }}>
                              <p>
                                {countOf(unindexable.length, 'document')} could not be indexed:{' '}
                                {nameList(unindexable.map((document) => document.title))}.
                              </p>
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
                            columns={documentColumns(
                              setViewingPassages,
                              canManage ? requestDocumentDelete : null,
                              uploading,
                            )}
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

            {canManage && (
              <section style={{ marginTop: 'var(--space-7)' }} aria-labelledby="source-access-heading">
                <Card as="section">
                  <Eyebrow as="h2" id="source-access-heading">
                    Who can search
                  </Eyebrow>
                  <label className="question-option" style={{ marginTop: 'var(--space-3)' }}>
                    <input
                      type="checkbox"
                      checked={isRestricted(source)}
                      disabled={updateSource.isPending}
                      onChange={(event) => {
                        // Ticking the box takes access away, and is done at once. Clearing it gives
                        // the whole workspace access to these documents, so it asks first.
                        if (event.target.checked) void changeRestricted(true)
                        else askToOpen()
                      }}
                    />
                    <span className="question-option-label">Only people who manage knowledge can search this source</span>
                  </label>
                  <p className="caption" style={{ marginTop: 'var(--space-2)' }}>
                    {sourceAccessNotice(isRestricted(source))}. A change applies to the next search. It cannot take back
                    passages someone has already been shown, such as in a Chat conversation.
                  </p>
                </Card>
              </section>
            )}

            {canManage && (
              <ConfirmDialog
                open={confirmingOpen}
                onClose={closeOpenQuestion}
                onConfirm={confirmOpen}
                eyebrow="Who can search"
                title={`Let everyone search ${source.name}?`}
                description="Everyone in this workspace with Chat access will be able to search these documents, and so will the agents working for them. Restricting the source again does not take back passages someone has already been shown."
                confirmLabel="Let everyone search"
                cancelLabel="Keep it restricted"
                tone="primary"
                loading={updateSource.isPending}
                error={openError}
              />
            )}

            {canManage && (
              <Dialog
                open={deletingSource}
                onClose={closeSourceDelete}
                eyebrow="Delete source"
                title={`Delete ${source.name}?`}
                description="Every document in it is erased, with every passage indexed from them. Chat and agents stop finding them at once, and this cannot be undone."
                dismissible={!deleteSource.isPending}
                error={deleteSourceError}
                footer={
                  <>
                    <Button variant="outline" onClick={closeSourceDelete} disabled={deleteSource.isPending}>
                      Keep it
                    </Button>
                    <Button
                      variant="danger"
                      type="submit"
                      form={deleteSourceFormId}
                      loading={deleteSource.isPending}
                      disabled={typedName.trim() !== source.name.trim()}
                    >
                      Delete source
                    </Button>
                  </>
                }
              >
                <form
                  id={deleteSourceFormId}
                  onSubmit={(event) => {
                    event.preventDefault()
                    void confirmSourceDelete(source)
                  }}
                >
                  <Input
                    label={`Type the source name, ${source.name}, to confirm`}
                    value={typedName}
                    onChange={(event) => setTypedName(event.target.value)}
                    autoComplete="off"
                    spellCheck={false}
                  />
                </form>
              </Dialog>
            )}

            {canManage && (
              <Dialog
                open={renaming}
                onClose={closeRename}
                eyebrow="Rename source"
                title={`Rename ${source.name}`}
                description="Only the name changes. Its documents and who can search it stay as they are."
                dismissible={!updateSource.isPending}
                error={renameDialogError}
                footer={
                  <>
                    <Button variant="outline" onClick={closeRename} disabled={updateSource.isPending}>
                      Cancel
                    </Button>
                    <Button
                      type="submit"
                      form={renameFormId}
                      loading={updateSource.isPending}
                      disabled={!newName.trim()}
                    >
                      Rename
                    </Button>
                  </>
                }
              >
                <form
                  id={renameFormId}
                  onSubmit={(event) => {
                    event.preventDefault()
                    void confirmRename(source)
                  }}
                >
                  <Input
                    label="Name"
                    value={newName}
                    onChange={(event) => {
                      setNewName(event.target.value)
                      setRenameError(null)
                    }}
                    maxLength={120}
                    required
                    error={renameError}
                    data-autofocus
                  />
                </form>
              </Dialog>
            )}
          </>
        )}
      </QueryState>

      <DocumentPassagesSheet sourceId={id} document={viewingPassages} onClose={() => setViewingPassages(null)} />

      <ConfirmDialog
        open={deletingDocument !== null}
        onClose={closeDocumentDelete}
        onConfirm={confirmDocumentDelete}
        eyebrow="Delete document"
        title={`Delete ${deletingDocument?.title ?? 'this document'}?`}
        description="It is erased with every passage indexed from it, and Chat and agents stop finding it at once. Upload it again to bring it back."
        confirmLabel="Delete document"
        cancelLabel="Keep it"
        tone="danger"
        loading={deleteDocument.isPending}
        error={deleteDocumentError}
      />

      <Dialog
        open={clashPrompt !== null}
        onClose={() => setClashPrompt(null)}
        eyebrow="Names already in use"
        title={
          clashPrompt && clashPrompt.clashes.length > 1
            ? `${formatCount(clashPrompt.clashes.length)} file names are already in use`
            : `${firstClash ?? 'This file name'} is already in use`
        }
        description={
          clashPrompt && clashPrompt.clashes.length > 1
            ? `A document here, or another file you chose, already has each of these names: ${nameList(clashPrompt.clashes, 5)}.`
            : 'A document here, or another file you chose, already has this name.'
        }
        footer={
          <>
            <Button variant="outline" onClick={() => resolveClash('skip')} data-autofocus>
              Skip
            </Button>
            <Button variant="outline" onClick={() => resolveClash('keep_both')}>
              Keep both
            </Button>
            <Button onClick={() => resolveClash('replace')}>Replace</Button>
          </>
        }
      >
        <ul className="stack" style={{ gap: 'var(--space-2)', paddingLeft: 'var(--space-5)' }}>
          <li>Replace makes the upload the new version. The old one stops being found in search.</li>
          <li>
            Keep both saves the upload beside the old one, as {nextFreeName(firstClash ?? 'Report.pdf', existingTitles())}.
          </li>
          <li>Skip leaves {clashPrompt && clashPrompt.clashes.length > 1 ? 'those files' : 'that file'} out and uploads the rest.</li>
        </ul>
      </Dialog>
    </div>
  )
}
