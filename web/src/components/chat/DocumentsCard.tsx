// @find: documents card, sources in chat, documents an agent used, knowledge documents in thread, cited documents
// @what: Card in the thread showing the workspace documents an agent used or found.
// @flow: Used by MessageItem.
import { Button, Card, Eyebrow } from '../ui'
import type { ChatMessage, ChatMessageDetail } from '../../lib/queries'
import { can } from '../../lib/session'
import { PassageList } from './PassageList'

// @find: DocumentsCard, documents card, documents card, sources in chat, documents an agent used, knowledge documents in thread
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
  // Set by the coordinator when only the keyword half of the search could run.
  const detail: ChatMessageDetail & { degraded?: boolean } = message.detail
  const degraded = detail.degraded === true

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
          <PassageList passages={passages} idPrefix={`documents-${message.id}`} limit={2} />
          {degraded && (
            <p className="caption muted" style={{ marginTop: 'var(--space-2)' }}>
              Keyword search only right now, so some passages may be missing.
            </p>
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
