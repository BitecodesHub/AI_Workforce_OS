import { Button, LoadingState, Notice } from '../ui'
import { Sheet } from '../ui/Sheet'
import { describeApiError } from '../../lib/api'
import { formatCount } from '../../lib/format'
import { useDocumentPassages } from '../../lib/knowledgeQueries'
import type { SourceDocument } from '../../lib/queries'

/*
 * What a document was cut into, in reading order. This is what search actually looks through, so
 * it answers "why does my question not find the policy": a scan with no text, a file cut short at
 * the size limit, or words the document simply does not use are all visible here.
 */

function Passages({ sourceId, document }: { sourceId: string; document: SourceDocument }) {
  const passages = useDocumentPassages(sourceId, document.id)

  if (passages.isPending) return <LoadingState rows={4} label="Loading the passages" />
  if (passages.isError) {
    return (
      <div className="stack" style={{ gap: 'var(--space-3)', alignItems: 'flex-start' }}>
        <Notice tone="warning" live>
          {describeApiError(passages.error)}
        </Notice>
        <Button variant="outline" onClick={() => void passages.refetch()}>
          Try again
        </Button>
      </div>
    )
  }

  const pages = passages.data.pages
  const shown = pages.flatMap((page) => page.passages)
  const total = pages[0]?.total ?? 0
  if (total === 0) {
    return (
      <p className="muted">
        This document has no passages, so search cannot find anything in it.
        {document.skipReason ? ` ${document.skipReason}` : ''}
      </p>
    )
  }

  return (
    <div className="stack" style={{ gap: 'var(--space-4)' }}>
      <p className="caption" role="status" aria-live="polite">
        Showing {formatCount(shown.length)} of {formatCount(total)} {total === 1 ? 'passage' : 'passages'}, in the order
        they appear in the document.
      </p>
      <ol className="chat-passages">
        {shown.map((passage) => (
          <li key={passage.id} className="chat-passage">
            <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap', marginBottom: 'var(--space-2)' }}>
              <span className="section-heading" style={{ fontSize: 'var(--text-caption)' }}>
                Passage {passage.position + 1}
              </span>
              {passage.pageNumber != null && <span className="caption">About page {passage.pageNumber}</span>}
              {passage.heading && <span className="caption">{passage.heading}</span>}
            </div>
            <blockquote style={{ margin: 0 }}>
              <p>{passage.content}</p>
            </blockquote>
          </li>
        ))}
      </ol>
      {passages.hasNextPage && (
        <div>
          <Button variant="outline" onClick={() => void passages.fetchNextPage()} loading={passages.isFetchingNextPage}>
            Show more passages
          </Button>
        </div>
      )}
    </div>
  )
}

/** A panel beside the document list, open for one document at a time. */
export function DocumentPassagesSheet({
  sourceId,
  document,
  onClose,
}: {
  sourceId: string
  /** The document whose passages to show, or null for closed. */
  document: SourceDocument | null
  onClose: () => void
}) {
  return (
    <Sheet open={document !== null} onClose={onClose} eyebrow="Passages" title={document?.title ?? 'Document'} width="md">
      {document && <Passages key={document.id} sourceId={sourceId} document={document} />}
    </Sheet>
  )
}
