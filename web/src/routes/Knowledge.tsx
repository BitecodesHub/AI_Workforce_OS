import { useId, useState } from 'react'
import type { FormEvent } from 'react'
import {
  Button,
  Card,
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
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
import { KnowledgeSearch } from '../components/knowledge/KnowledgeSearch'
import { ApiError, describeApiError } from '../lib/api'
import { formatCount, nameList, truncateWords } from '../lib/format'
import {
  KEYWORD_SEARCH_NOTE,
  isRestricted,
  searchModeOf,
  sourceAccessNotice,
  useCreateKnowledgeSource,
} from '../lib/knowledgeQueries'
import { embeddingProviderLabel, sourceKindLabel } from '../lib/labels'
import { useSources } from '../lib/queries'
import type { Source } from '../lib/queries'
import { useRouter } from '../lib/router'
import { can } from '../lib/session'
import { useToast } from '../lib/toast'

/**
 * A source somebody should look at. A source can read "Ready" and still carry an error: when the
 * vector store is down its documents are indexed for keyword search only, and lastError says so.
 * The notice and the summary tile use this one test, so they cannot disagree.
 */
function needsAttention(source: Source): boolean {
  return source.status === 'failed' || source.status === 'reconnect_required' || Boolean(source.lastError)
}

/**
 * How the service begins the one source error it records today: documents indexed for keyword
 * search whose vectors could not be written. The rest is the underlying exception.
 */
const VECTOR_ERROR_PREFIX = 'Vector indexing is unavailable'

/** A short, plain reading of a source's error for the table; the full message goes in the title. */
function errorSummary(lastError: string): string {
  return lastError.startsWith(VECTOR_ERROR_PREFIX)
    ? 'Keyword search only. Meaning-based search is unavailable.'
    : truncateWords(lastError, 80)
}

function sourcesNote(sources: Source[]): string {
  const attention = sources.filter(needsAttention).length
  if (attention > 0) return `${formatCount(attention)} ${attention === 1 ? 'needs' : 'need'} attention`
  const idle = sources.filter((source) => source.status === 'idle').length
  if (idle > 0) return `${formatCount(idle)} not indexed yet`
  const indexing = sources.filter((source) => source.status === 'ingesting').length
  if (indexing > 0) return `${formatCount(indexing)} indexing now`
  return 'All ready'
}

/**
 * The embedding model when every source uses the same one; sources can differ, and then it says so.
 * A source on the offline sandbox has no model that carries meaning, and the tile says that rather
 * than naming a model and a size as though search by meaning were working.
 */
function embeddingTile(sources: Source[]): { value: string; note: string } {
  const [first] = sources
  if (!first) return { value: 'Mixed', note: 'Differs by source' }
  const modes = new Set(sources.map(searchModeOf))
  if (modes.size === 1 && modes.has('keyword')) return { value: 'None', note: KEYWORD_SEARCH_NOTE }
  const models = new Set(sources.map((source) => source.embeddingModel))
  if (models.size !== 1 || modes.has('keyword')) return { value: 'Mixed', note: 'Differs by source' }
  return {
    value: first.embeddingModel || 'Not configured',
    note: `${embeddingProviderLabel(first.embeddingProvider)}, ${formatCount(first.embeddingDimension)} dimensions`,
  }
}

const COLUMNS: Column<Source>[] = [
  {
    key: 'name',
    header: 'Source',
    sortValue: (row) => row.name,
    render: (row) =>
      isRestricted(row) ? (
        <div className="row" style={{ gap: 'var(--space-2)', flexWrap: 'wrap' }}>
          <span>{row.name}</span>
          <Tag tone="warning" title={sourceAccessNotice(true)}>
            Restricted
          </Tag>
        </div>
      ) : (
        row.name
      ),
  },
  { key: 'kind', header: 'Type', render: (row) => <span className="muted">{sourceKindLabel(row.kind)}</span> },
  {
    key: 'status',
    header: 'Status',
    render: (row) => (
      <div className="stack" style={{ gap: 'var(--space-1)', alignItems: 'flex-start' }}>
        <StatusTag kind="source" status={row.status} />
        {row.lastError && (
          <span className="caption" title={row.lastError}>
            {errorSummary(row.lastError)}
          </span>
        )}
      </div>
    ),
  },
  {
    key: 'documentCount',
    header: 'Indexed',
    numeric: true,
    sortValue: (row) => row.documentCount,
    render: (row) => formatCount(row.documentCount),
  },
  {
    key: 'chunkCount',
    header: 'Passages',
    numeric: true,
    sortValue: (row) => row.chunkCount,
    render: (row) => formatCount(row.chunkCount),
  },
  {
    key: 'lastIngestedAt',
    header: 'Last indexed',
    sortValue: (row) => (row.lastIngestedAt ? Date.parse(row.lastIngestedAt) : null),
    render: (row) =>
      row.lastIngestedAt ? <Time iso={row.lastIngestedAt} className="muted" /> : <span className="muted">Never</span>,
  },
]

export function Knowledge() {
  const [dialogOpen, setDialogOpen] = useState(false)
  const [name, setName] = useState('')
  const [restricted, setRestricted] = useState(false)
  const [nameError, setNameError] = useState<string | null>(null)
  const [dialogError, setDialogError] = useState<string | null>(null)
  const formId = useId()
  const { success } = useToast()
  const { navigate } = useRouter()
  const sourcesQuery = useSources()
  const createSource = useCreateKnowledgeSource()
  const canManage = can('knowledge:source_manage')
  // Asking in Chat needs chat:use as well as knowledge:query. Trying a search here does not: it
  // reads the documents directly, with no agent, so knowledge:query is all it takes.
  const canQuery = can('knowledge:query')
  const canAskInChat = can('chat:use') && canQuery
  const totalDocuments = sourcesQuery.data?.reduce((sum, source) => sum + source.documentCount, 0) ?? 0

  function openDialog() {
    setName('')
    setRestricted(false)
    setNameError(null)
    setDialogError(null)
    createSource.reset()
    setDialogOpen(true)
  }

  function closeDialog() {
    if (createSource.isPending) return
    setDialogOpen(false)
  }

  async function handleCreate(event: FormEvent) {
    event.preventDefault()
    const trimmed = name.trim()
    if (!trimmed || createSource.isPending) return
    setNameError(null)
    setDialogError(null)
    try {
      // Uploading is the only way documents reach a source in this version, so every new source
      // is an upload source. There is no connector to pick.
      const created = await createSource.mutateAsync({ name: trimmed, kind: 'upload', restricted })
      setDialogOpen(false)
      navigate(`/knowledge/${created.id}`)
      success(`Source created. Upload a document to make it searchable. ${sourceAccessNotice(restricted)}.`)
    } catch (err) {
      // A duplicate name is about the field; anything else is about the request.
      if (err instanceof ApiError && err.status === 409) setNameError(err.message)
      else setDialogError(describeApiError(err, { name: 'Name' }))
    }
  }

  return (
    <div className="page">
      <PageHeader
        eyebrow="Searchable documents"
        title="Knowledge"
        description="Documents you upload here can be searched in Chat. Each result shows the passage it came from and the document it belongs to."
        action={
          <>
            {canAskInChat && totalDocuments > 0 && (
              <a className="button button-outline" href="/chat">
                Ask in Chat
              </a>
            )}
            {canManage && <Button onClick={openDialog}>Add a source</Button>}
          </>
        }
      />

      <QueryState
        query={sourcesQuery}
        permission="knowledge:read"
        what="the source list"
        isEmpty={(sources) => sources.length === 0}
        empty={
          <Card as="section">
            <EmptyState
              icon={<EmptyIcon kind="document" />}
              title="No sources yet"
              body={
                canManage
                  ? 'A source is a folder of documents you upload. Add one, then upload documents to it. Agents and Chat can search these documents.'
                  : 'Nobody has added documents yet. A manager or admin can add a source and upload documents to it.'
              }
              action={canManage && <Button onClick={openDialog}>Add a source</Button>}
            />
          </Card>
        }
        rows={4}
      >
        {(sources) => {
          const attention = sources.filter(needsAttention)
          const embedding = embeddingTile(sources)
          return (
            <>
              {attention.length > 0 && (
                <Notice tone="warning">
                  {attention.length === 1
                    ? `${attention[0]!.name} needs attention. Open it to see why.`
                    : `${formatCount(attention.length)} sources need attention: ${nameList(attention.map((source) => source.name))}. Open a source to see why.`}
                </Notice>
              )}

              <div style={{ marginTop: 'var(--space-6)' }}>
                <StatRow>
                  <StatTile label="Sources" value={formatCount(sources.length)} note={sourcesNote(sources)} />
                  <StatTile label="Documents" value={formatCount(totalDocuments)} unit="indexed" />
                  <StatTile
                    label="Passages"
                    value={formatCount(sources.reduce((sum, source) => sum + source.chunkCount, 0))}
                    note="Searchable in Chat"
                  />
                  <StatTile label="Embedding model" value={embedding.value} note={embedding.note} />
                </StatRow>
                <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
                  The Documents figure counts indexed documents only. A document that could not be indexed,
                  such as a scanned image with no readable text, is listed in its source with the reason.
                </p>
              </div>

              <section style={{ marginTop: 'var(--space-7)' }}>
                <Card as="section">
                  <Eyebrow as="h2">Sources</Eyebrow>
                  <DataTable
                    columns={COLUMNS}
                    rows={sources}
                    getKey={(row) => row.id}
                    getRowHref={(row) => `/knowledge/${row.id}`}
                    caption="Knowledge sources and the state of their most recent indexing."
                  />
                </Card>
              </section>

              {canQuery && totalDocuments > 0 && (
                <KnowledgeSearch
                  sources={sources}
                  description="Search the documents directly to see what a question finds. This does not start an agent run."
                />
              )}
            </>
          )
        }}
      </QueryState>

      <Dialog
        open={dialogOpen}
        onClose={closeDialog}
        eyebrow="Knowledge"
        title="Add a source"
        description="A source is a folder of documents you upload. Chat searches everything you add, unless you restrict it."
        dismissible={!createSource.isPending}
        error={dialogError}
        footer={
          <>
            <Button variant="outline" onClick={closeDialog} disabled={createSource.isPending}>
              Cancel
            </Button>
            <Button type="submit" form={formId} disabled={!name.trim()} loading={createSource.isPending}>
              Create
            </Button>
          </>
        }
      >
        <form id={formId} onSubmit={handleCreate} className="stack" style={{ gap: 'var(--space-4)' }}>
          <Input
            label="Name"
            value={name}
            onChange={(event) => {
              setName(event.target.value)
              setNameError(null)
            }}
            placeholder="Policies and agreements"
            maxLength={120}
            required
            error={nameError}
            data-autofocus
          />
          <label className="question-option">
            <input type="checkbox" checked={restricted} onChange={(event) => setRestricted(event.target.checked)} />
            <span className="question-option-label">Only people who manage knowledge can search this source</span>
          </label>
          <Notice>{sourceAccessNotice(restricted)}</Notice>
        </form>
      </Dialog>
    </div>
  )
}
