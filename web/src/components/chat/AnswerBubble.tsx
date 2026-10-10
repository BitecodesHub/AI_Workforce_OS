// @find: agent answer, reply bubble, answer message, copy answer, read aloud, thumbs up, thumbs down, feedback, sources, citations, sandbox model, markdown answer, chat thread
// @what: An agent's reply in the thread: name, markdown answer, action bar (copy, read aloud, feedback) and document sources.
// @flow: Used by MessageItem; uses PassageList.
import { useEffect, useMemo, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { Collapsible } from '../ui/Collapsible'
import { Markdown } from '../ui/Markdown'
import { MenuButton } from '../ui/Menu'
import { IconButton, Tag } from '../ui'
import { useCopyText } from '../../lib/clipboard'
import type { Agent, BoardGoal, ChatMessage, ConversationDetail } from '../../lib/queries'
import type { useSpeaker } from '../../lib/voice'
import { AgentAvatar } from '../ui/AgentAvatar'
import { RatingButtons, RatingReason, useAnswerRating } from '../analytics/AnswerRating'
import { PassageList, passageItemId } from './PassageList'
import { PRACTICE_DATA_NOTE, sourcesForAnswer } from './chatModel'

/**
 * The thread the answer sits in, as the conversation query last stored it, for a caller that does
 * not hand it over. Read once per render, never subscribed to: the routing message that says which
 * passages the agent was given is written before its answer arrives and does not change.
 */
function storedThread(client: QueryClient, conversationId: string | null): readonly ChatMessage[] {
  if (!conversationId) return []
  return client.getQueryData<ConversationDetail>(['conversations', conversationId])?.messages ?? []
}

// @find: AnswerBubble, answer bubble, agent answer, reply bubble, answer message, copy answer
/**
 * An agent's reply: its name, whether a sandbox model stood in, the answer itself (as safe
 * Markdown) and its action bar - copy, read aloud, a thumbs up or down (with an optional reason, for
 * the managers who read how agents are doing), the full trace, and a menu to send the same request
 * elsewhere or ask again.
 *
 * When the agent was given passages from the workspace's documents, they sit under the answer as a
 * folded "Sources" list, and a [2] in the answer opens source 2. Only the goal's first step reads
 * the passages, so only its answers show them.
 */
export function AnswerBubble({
  message,
  agent,
  speaker,
  grouped,
  latest,
  goal,
  messages,
  agentsForReroute,
  conversationId,
  onSendTo,
  onAskAgain,
}: {
  message: ChatMessage
  agent: Agent | undefined
  speaker: ReturnType<typeof useSpeaker>
  grouped: boolean
  latest: boolean
  goal?: BoardGoal
  /** The thread, to find the routing message of this answer's goal; read from the query cache when left out. */
  messages?: readonly ChatMessage[] | undefined
  agentsForReroute: Agent[]
  conversationId: string | null
  onSendTo: (agentId: string) => void
  onAskAgain: () => void
}) {
  const copy = useCopyText()
  const client = useQueryClient()
  const thread = messages ?? storedThread(client, conversationId)
  const sources = useMemo(() => sourcesForAnswer(message, thread, goal), [message, thread, goal])
  const [sourcesOpen, setSourcesOpen] = useState(false)
  // Which source a click on [n] asked for. The count changes on every click, so asking for the
  // same one twice moves focus there twice even after the person has tabbed away.
  const [cited, setCited] = useState<{ index: number; count: number } | null>(null)
  // After the list has opened (the folded body shows in the commit that sets it), move focus to the
  // passage: a person who followed a citation lands on what it cites, and a screen reader says so.
  useEffect(() => {
    if (cited) document.getElementById(passageItemId(`answer-${message.id}`, cited.index))?.focus()
  }, [cited, message.id])
  const openSource = (index: number) => {
    setSourcesOpen(true)
    setCited((previous) => ({ index, count: (previous?.count ?? 0) + 1 }))
  }
  const rating = useAnswerRating(conversationId, message.id)
  const runId = message.detail.runId
  const name = agent?.name ?? 'An agent'
  const speaking = speaker.speakingKey === message.id
  const others = agentsForReroute.filter((candidate) => candidate.id !== message.agentId).slice(0, 6)

  return (
    <div className="chat-bubble-row chat-bubble-row-agent">
      {!grouped && <AgentAvatar name={name} category={agent?.category} fallback={agent?.fallback ?? false} quietInitials />}
      <div className={`chat-answer${grouped ? ' chat-bubble-grouped' : ''}`} data-latest={latest || undefined}>
        {!grouped && (
          <div className="row chat-answer-head" style={{ gap: 'var(--space-2)', flexWrap: 'wrap', alignItems: 'center' }}>
            <span className="chat-bubble-agent-name">{name}</span>
            <span className="chat-answer-label caption">AI agent</span>
            {message.detail.sandbox === true && <Tag tone="neutral">Offline sandbox model</Tag>}
          </div>
        )}
        <Markdown
          text={message.content}
          citations={sources.length > 0 ? { count: sources.length, onOpen: openSource } : undefined}
        />
        {message.detail.practiceData === true && (
          <p className="caption muted chat-answer-practice">{PRACTICE_DATA_NOTE}</p>
        )}
        {sources.length > 0 && (
          <div className="chat-sources" style={{ marginTop: 'var(--space-3)' }}>
            <Collapsible title={`Sources (${sources.length})`} open={sourcesOpen} onToggle={setSourcesOpen} headingLevel="p">
              <p className="caption muted" style={{ margin: 'var(--space-1) 0 var(--space-3)' }}>
                Sources given to the agent
              </p>
              <PassageList passages={sources} idPrefix={`answer-${message.id}`} activeIndex={cited?.index ?? null} compact />
            </Collapsible>
          </div>
        )}
        <div className="row chat-actions chat-answer-actions" style={{ gap: 'var(--space-3)' }}>
          <IconButton label="Copy answer" onClick={() => void copy(message.content, 'Answer copied')}>
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <rect x="5.5" y="5.5" width="8" height="8" rx="1.5" stroke="currentColor" strokeWidth="1.3" />
              <path d="M3.5 10.5V3.5a1 1 0 0 1 1-1h7" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
            </svg>
          </IconButton>
          {speaker.provider !== 'none' && (
            <IconButton
              label={speaking ? 'Stop reading aloud' : 'Read aloud'}
              aria-pressed={speaking}
              onClick={() => (speaking ? speaker.stop() : speaker.speak(message.content, message.agentId, { force: true, key: message.id }))}
            >
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M3 6h2.3L9 3.2v9.6L5.3 10H3z" fill="currentColor" />
                <path d="M11.2 5.3a4 4 0 0 1 0 5.4" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
              </svg>
            </IconButton>
          )}
          <RatingButtons state={rating} />
          {runId && (
            <a className="link caption" href={`/runs/${runId}`}>
              Open full trace
            </a>
          )}
          <MenuButton
            label="More actions"
            trigger="icon"
            align="end"
            icon={
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <circle cx="3.5" cy="8" r="1.4" fill="currentColor" />
                <circle cx="8" cy="8" r="1.4" fill="currentColor" />
                <circle cx="12.5" cy="8" r="1.4" fill="currentColor" />
              </svg>
            }
            items={[
              ...others.map((candidate) => ({
                id: `send-${candidate.id}`,
                label: `Send to ${candidate.name}`,
                onSelect: () => onSendTo(candidate.id),
              })),
              { id: 'ask-again', label: 'Ask again', onSelect: onAskAgain },
              {
                id: 'copy-link',
                label: 'Copy link to this message',
                onSelect: () =>
                  void copy(`${window.location.origin}/chat?c=${conversationId ?? ''}#m-${message.id}`, 'Link copied'),
              },
            ]}
          />
        </div>
        <RatingReason state={rating} />
      </div>
    </div>
  )
}
