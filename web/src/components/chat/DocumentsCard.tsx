import { Card, Eyebrow } from '../ui'
import type { ChatMessage } from '../../lib/queries'

/** A document question's answer: the passages that matched it, quoted, each with its source. */
export function DocumentsCard({ message }: { message: ChatMessage }) {
  const passages = message.detail.passages ?? []
  const grounded = message.detail.grounded === true

  return (
    <Card as="article" className="chat-documents-card">
      <Eyebrow as="h2">Documents</Eyebrow>
      {!grounded || passages.length === 0 ? (
        <>
          <p>No document supports an answer.</p>
          <p className="caption muted" style={{ marginTop: 'var(--space-2)' }}>
            Try other words, or check in Knowledge that the document you expect has been indexed.
          </p>
        </>
      ) : (
        <ol className="chat-passages">
          {passages.map((passage) => (
            <li key={passage.chunkId} className="chat-passage">
              <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap', marginBottom: 'var(--space-2)' }}>
                <span className="section-heading" style={{ fontSize: '12px' }}>
                  {passage.documentTitle}
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
      )}
    </Card>
  )
}
