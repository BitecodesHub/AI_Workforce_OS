// @find: try search, search documents, knowledge search, test search, passages found, RAG, search sources, Knowledge page
// @what: Box to try a search on documents without an agent, showing passages found.
// @flow: Used by the Knowledge page.
import { useId, useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Card, Eyebrow, Input, Notice, Select } from '../ui'
import { describeApiError } from '../../lib/api'
import { plural } from '../../lib/format'
import { KEYWORD_ONLY_NOW, useKnowledgeSearch } from '../../lib/knowledgeQueries'
import type { FoundPassage } from '../../lib/knowledgeQueries'
import type { Source } from '../../lib/queries'

/*
 * Trying a search on the documents, without an agent. A person who has just uploaded files can see
 * at once whether a question finds them, and which passage and page it found - which Chat cannot
 * show, because a question there may start a paid run and the passages stay behind the answer.
 */

// @find: PassageList, passage list, try search, search documents, knowledge search, test search
/** The passages a search found, each with the document, page and section it came from. */
export function PassageList({
  passages,
  sourceNames,
}: {
  passages: readonly FoundPassage[]
  /** Source names by id. When given, each passage links to the source it lives in. */
  sourceNames?: ReadonlyMap<string, string> | undefined
}) {
  return (
    <ol className="chat-passages">
      {passages.map((passage, index) => {
        const sourceName = passage.sourceId ? sourceNames?.get(passage.sourceId) : undefined
        return (
          <li key={passage.chunkId} className="chat-passage">
            <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap', marginBottom: 'var(--space-2)' }}>
              <span className="section-heading" style={{ fontSize: 'var(--text-caption)' }}>
                [{index + 1}] {passage.documentTitle}
              </span>
              {passage.pageNumber != null && <span className="caption">About page {passage.pageNumber}</span>}
              {passage.heading && <span className="caption">{passage.heading}</span>}
              {sourceName && passage.sourceId && (
                <a className="link caption" href={`/knowledge/${passage.sourceId}`}>
                  {sourceName}
                </a>
              )}
              {passage.uri?.startsWith('http') && (
                <a className="link caption" href={passage.uri} target="_blank" rel="noopener noreferrer">
                  Open source
                </a>
              )}
            </div>
            <blockquote style={{ margin: 0 }}>
              <p>{passage.content}</p>
            </blockquote>
          </li>
        )
      })}
    </ol>
  )
}

const ALL_SOURCES = ''

// @find: KnowledgeSearch, knowledge search, try search, search documents, knowledge search, test search
/**
 * A box to try a search in. Searches every source the person may read, or the one chosen, or
 * - with `onlySourceId` - just the source whose page this is.
 */
export function KnowledgeSearch({
  sources,
  onlySourceId,
  description,
}: {
  /** The sources that can be searched, for the choice of where to look and for naming results. */
  sources: readonly Source[]
  /** Fixes the search to one source, and hides the choice of where to look. */
  onlySourceId?: string
  description: string
}) {
  const headingId = useId()
  const [query, setQuery] = useState('')
  const [scope, setScope] = useState(ALL_SOURCES)
  const [searched, setSearched] = useState<string | null>(null)
  const search = useKnowledgeSearch()
  const choosable = onlySourceId === undefined && sources.length > 1
  const sourceNames = new Map(sources.map((source) => [source.id, source.name]))

  function submit(event: FormEvent) {
    event.preventDefault()
    const trimmed = query.trim()
    if (!trimmed || search.isPending) return
    const sourceIds = onlySourceId !== undefined ? [onlySourceId] : scope === ALL_SOURCES ? undefined : [scope]
    setSearched(trimmed)
    search.mutate(sourceIds ? { query: trimmed, sourceIds } : { query: trimmed })
  }

  const outcome = search.data
  const documents = outcome ? new Set(outcome.passages.map((passage) => passage.documentId)).size : 0

  return (
    <section style={{ marginTop: 'var(--space-7)' }} aria-labelledby={headingId}>
      <Card as="section">
        <Eyebrow as="h2" id={headingId}>
          Try a search
        </Eyebrow>
        <p className="caption" style={{ marginTop: 'var(--space-2)', marginBottom: 'var(--space-4)' }}>
          {description}
        </p>
        <form role="search" aria-labelledby={headingId} onSubmit={submit}>
          <div className="row" style={{ gap: 'var(--space-3)', alignItems: 'flex-end', flexWrap: 'wrap' }}>
            <div style={{ flex: '1 1 18rem' }}>
              <Input
                label="What do you want to find?"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Annual leave entitlement"
                maxLength={1000}
                autoComplete="off"
              />
            </div>
            {choosable && (
              <div style={{ flex: '0 1 14rem' }}>
                <Select
                  label="Where to look"
                  value={scope}
                  onChange={(event) => {
                    setScope(event.target.value)
                    search.reset()
                  }}
                >
                  <option value={ALL_SOURCES}>All sources</option>
                  {sources.map((source) => (
                    <option key={source.id} value={source.id}>
                      {source.name}
                    </option>
                  ))}
                </Select>
              </div>
            )}
            <Button type="submit" disabled={!query.trim()} loading={search.isPending}>
              Search
            </Button>
          </div>
        </form>

        <div style={{ marginTop: 'var(--space-4)' }}>
          {search.isError && <Notice tone="warning">{describeApiError(search.error)}</Notice>}
          {outcome && (
            <div className="stack" style={{ gap: 'var(--space-3)' }}>
              {outcome.degraded && (
                <Notice tone="warning">
                  <div className="stack" style={{ gap: 'var(--space-1)', flex: 1, minWidth: 0 }}>
                    <p>{KEYWORD_ONLY_NOW}</p>
                    <p className="caption">Search by meaning is not available at the moment, so some matches may be missing.</p>
                  </div>
                </Notice>
              )}
              {/* Only this line is announced: reading every passage aloud on each search would bury it. */}
              <p className="caption" role="status" aria-live="polite">
                {outcome.passages.length === 0
                  ? `Nothing matched ${searched ? `"${searched}"` : 'that search'}. Try other words, or check that the document you expect has been indexed.`
                  : `${plural(outcome.passages.length, 'passage', 'passages')} from ${plural(documents, 'document', 'documents')}, best match first.`}
              </p>
              {outcome.passages.length > 0 && (
                <PassageList passages={outcome.passages} sourceNames={onlySourceId === undefined ? sourceNames : undefined} />
              )}
            </div>
          )}
        </div>
      </Card>
    </section>
  )
}
