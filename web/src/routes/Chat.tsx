import { useState } from 'react'
import { useRef, useEffect } from 'react'
import { Button, Card, Eyebrow, Notice, PageHeader, Tag, Textarea, Spinner } from '../components/ui'
import { PermissionState } from '../components/ui/QueryState'
import { can } from '../lib/session'
import { useSearch } from '../lib/queries'
import { timeAgo } from '../lib/api'

type Passage = {
  chunkId: string
  documentId: string
  documentTitle: string
  uri: string | null
  pageNumber: number | null
  heading: string | null
  content: string
  score: number
}

type Message = {
  id: string
  role: 'you' | 'agent'
  text: string
  timestamp: string
  passages?: Passage[]
  grounded?: boolean
}

export function Chat() {
  const [question, setQuestion] = useState('')
  const [history, setHistory] = useState<Message[]>([])
  const [isSearching, setIsSearching] = useState(false)
  const search = useSearch()
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const canUseChat = can('chat:use')

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [history])

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (!question.trim() || isSearching) return

    const userMessage: Message = {
      id: `msg-${Date.now()}`,
      role: 'you',
      text: question.trim(),
      timestamp: new Date().toISOString(),
    }

    setHistory((prev) => [...prev, userMessage])
    const currentQuestion = question.trim()
    setQuestion('')
    setIsSearching(true)

    try {
      const result = await search.mutateAsync(currentQuestion)
      const agentMessage: Message = {
        id: `msg-${Date.now()}`,
        role: 'agent',
        text: result.grounded
          ? 'Here is what I found in your documents.'
          : 'No document in this workspace supports an answer to that question.',
        timestamp: new Date().toISOString(),
        passages: result.passages,
        grounded: result.grounded,
      }
      setHistory((prev) => [...prev, agentMessage])
    } catch {
      const errorMessage: Message = {
        id: `msg-${Date.now()}`,
        role: 'agent',
        text: 'Something went wrong while searching. Please try again.',
        timestamp: new Date().toISOString(),
        grounded: false,
      }
      setHistory((prev) => [...prev, errorMessage])
    } finally {
      setIsSearching(false)
    }
  }

  if (!canUseChat) {
    return (
      <div className="page">
        <PageHeader
          eyebrow="Ask the workforce"
          title="Chat"
          description="Answers come from your own documents, and every one shows the passages it relies on."
        />
        <PermissionState permission="chat:use" what="chat" />
      </div>
    )
  }

  return (
    <div className="page">
      <PageHeader
        eyebrow="Ask the workforce"
        title="Chat"
        description="Answers come from your own documents, and every one shows the passages it relies on."
      />

      <Notice tone="info">
        An agent that cannot find supporting text says so rather than guessing. Treat an answer
        without citations as unverified.
      </Notice>

      <div className="stack" style={{ gap: 'var(--space-5)', marginTop: 'var(--space-6)', flex: 1 }}>
        {history.length === 0 && (
          <Card as="section" className="empty-chat">
            <div className="empty-state" style={{ textAlign: 'center', padding: 'var(--space-10) var(--space-6)' }}>
              <svg width="48" height="48" viewBox="0 0 24 24" fill="none" aria-hidden="true" style={{ marginBottom: 'var(--space-4)', color: 'var(--muted)' }}>
                <circle cx="11" cy="11" r="6" stroke="currentColor" strokeWidth="1.6" />
                <path d="M15.5 15.5l4 4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
              </svg>
              <h2 className="empty-title">Ask a question about your knowledge base</h2>
              <p className="empty-body">Answers are grounded in your connected sources. Every answer cites the passages it relies on.</p>
            </div>
          </Card>
        )}

        {history.map((message) => (
          <Card key={message.id} as="article">
            <Eyebrow>{message.role === 'you' ? 'You asked' : 'Agent'}</Eyebrow>

            <p style={{ marginBottom: message.role === 'agent' ? 'var(--space-5)' : 0 }}>{message.text}</p>

            {message.role === 'agent' && message.grounded === false && (
              <Tag tone="warning">No supporting document</Tag>
            )}

            {message.passages && message.passages.length > 0 && (
              <div className="stack" style={{ gap: 'var(--space-3)' }}>
                <span className="caption">Supported by</span>
                {message.passages.map((passage) => (
                  <div
                    key={passage.chunkId}
                    style={{
                      border: '1px solid var(--line)',
                      borderRadius: 'var(--radius-control-lg)',
                      padding: 'var(--space-4)',
                    }}
                  >
                    <div className="row" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-2)' }}>
                      <span className="section-heading" style={{ fontSize: '12px' }}>
                        {passage.documentTitle}
                      </span>
                      {passage.pageNumber && <span className="caption">page {passage.pageNumber}</span>}
                      {passage.heading && <span className="caption">{passage.heading}</span>}
                    </div>
                    <p className="muted" style={{ fontSize: '12px' }}>
                      {passage.content}
                    </p>
                  </div>
                ))}
              </div>
            )}

            <p className="caption" style={{ marginTop: 'var(--space-3)', textAlign: 'right' }}>
              {timeAgo(message.timestamp)}
            </p>
          </Card>
        ))}

        <div ref={messagesEndRef} />
      </div>

      <form onSubmit={handleSubmit} className="row" style={{ gap: 'var(--space-3)', marginTop: 'var(--space-6)' }}>
        <Textarea
          label="Your question"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder="Ask about a policy, a client or a process"
          aria-label="Your question"
          rows={3}
          disabled={isSearching}
        />
        <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'flex-end' }}>
          <Button
            type="submit"
            loading={isSearching}
            disabled={question.trim().length === 0 || isSearching}
            style={{ height: 'fit-content' }}
          >
            <span style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-2)' }}>
              {isSearching && <Spinner size={14} />}
              Ask
            </span>
          </Button>
        </div>
      </form>
    </div>
  )
}