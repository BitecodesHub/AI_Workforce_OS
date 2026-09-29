import { useState } from 'react'
import { Button, Card, Eyebrow } from '../ui'
import type { ChatMessage } from '../../lib/queries'
import { can } from '../../lib/session'

/** A document question's answer: the passages that matched it, quoted, each with its source. */
export function DocumentsCard({
  message,
  onAnswerFromDocuments,
  answering,
  answerStartedMessageId,
}: {
  message: ChatMessage
  onAnswerFromDocuments: () => void
  answering: boolean
  /** Set once a later routing message carries fromDocumentsMessageId equal to this card's id (A3.9). */
  answerStartedMessageId?: string | null
}) {
  const passages = message.detail.passages ?? []
  const grounded = message.detail.grounded === true
  const [showAll, setShowAll] = useState(false)
  const shown = showAll ? passages : passages.slice(0, 2)
  const rest = passages.length - shown.length

  return (
    <Card as="article" className="chat-documents-card">
      <Eyebrow as="p">
        {passages.length} {passages.length === 1 ? 'passage' : 'passages'} from{' '}
        {new Set(passages.map((p) => p.documentTitle)).size} {new Set(passages.map((p) => p.documentTitle)).size === 1 ? 'document' : 'documents'}
      </Eyebrow>
      {!grounded || passages.length === 0 ? (
        <>
          <p>No document supports an answer.</p>
          <p className="caption muted" style={{ marginTop: 'var(--space-2)' }}>
            Try other words, or check in Knowledge that the document you expect has been indexed.
          </p>
        </>
      ) : (
        <>
          <ol className="chat-passages">
            {shown.map((passage, index) => (
              <li key={passage.chunkId} className="chat-passage">
                <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap', marginBottom: 'var(--space-2)' }}>
                  <span className="section-heading" style={{ fontSize: 'var(--text-caption)' }}>
                    [{index + 1}] {passage.documentTitle}
                  </span>
                  {passage.pageNumber != null && <span className="caption">About page {passage.pageNumber}</span>}
                  {passage.heading && <span className="caption">{passage.heading}</span>}
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
            ))}
          </ol>
          {rest > 0 && (
            <button type="button" className="link" onClick={() => setShowAll(true)}>
              Show {rest} more
            </button>
          )}
          <div className="row" style={{ marginTop: 'var(--space-4)' }}>
            {answerStartedMessageId ? (
              <p className="caption">
                Answer started.{' '}
                <a className="link" href={`#m-${answerStartedMessageId}`}>
                  Go to it
                </a>
              </p>
            ) : can('task:create') ? (
              <Button variant="outline" onClick={onAnswerFromDocuments} loading={answering}>
                Write an answer from these passages
              </Button>
            ) : null}
          </div>
        </>
      )}
    </Card>
  )
}
