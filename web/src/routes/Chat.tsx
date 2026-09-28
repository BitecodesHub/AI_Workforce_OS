import { useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { AnswerBubble } from '../components/chat/AnswerBubble'
import { Composer } from '../components/chat/Composer'
import { ConversationRail } from '../components/chat/ConversationRail'
import { DocumentsCard } from '../components/chat/DocumentsCard'
import { ErrorCard } from '../components/chat/ErrorCard'
import { ProgressCard } from '../components/chat/ProgressCard'
import { RoutingCard } from '../components/chat/RoutingCard'
import { ScheduleCard } from '../components/chat/ScheduleCard'
import { UserBubble } from '../components/chat/UserBubble'
import { WelcomeScreen } from '../components/chat/WelcomeScreen'
import { groupMessages, newMessageIds } from '../components/chat/chatModel'
import { IconButton, Notice, PageHeader, Spinner } from '../components/ui'
import { QueryState } from '../components/ui/QueryState'
import { useReducedMotion } from '../hooks/useReducedMotion'
import { api, describeApiError } from '../lib/api'
import {
  useAgentNames,
  useAgents,
  useConversation,
  useConversations,
  useCreateConversation,
  useReroute,
  useSendMessage,
} from '../lib/queries'
import type { ChatMessage, Goal } from '../lib/queries'
import { useRouter } from '../lib/router'
import { can } from '../lib/session'
import { useToast } from '../lib/toast'
import { useNow } from '../lib/useNow'
import { useSpeaker } from '../lib/voice'

/*
 * Talk to the whole workforce. A message goes to the coordinator, which either mentions the
 * agent already named in it, asks a live model which agent (or agents, in a chain) should take
 * it, or falls back to matching it by keywords - and says which of those it did, on every reply.
 *
 * The thread is a list of ChatMessage kinds (see queries.ts's own note on the shape), each
 * rendered by its own small card in components/chat/. This file is the thin layer that wires the
 * conversation, the composer and the live-region announcements together; it holds no rendering
 * rules of its own beyond dispatching a message to its card.
 */

const NEAR_BOTTOM_PX = 140

function MessageItem({
  message,
  grouped,
  goals,
  agentNames,
  speaker,
  now,
  onReroute,
  reroutingId,
}: {
  message: ChatMessage
  grouped: boolean
  goals: Goal[]
  agentNames: ReturnType<typeof useAgentNames>
  speaker: ReturnType<typeof useSpeaker>
  now: number
  onReroute: (messageId: string, agentId: string) => void
  reroutingId: string | null
}) {
  switch (message.kind) {
    case 'text':
      return message.authorKind === 'user' ? (
        <UserBubble message={message} grouped={grouped} />
      ) : (
        <p className="caption muted">{message.content}</p>
      )
    case 'routing':
      return (
        <RoutingCard
          message={message}
          agentNames={agentNames}
          onReroute={(agentId) => onReroute(message.id, agentId)}
          rerouting={reroutingId === message.id}
        />
      )
    case 'documents':
      return <DocumentsCard message={message} />
    case 'progress': {
      const goal = message.detail.goalId ? goals.find((candidate) => candidate.id === message.detail.goalId) : undefined
      return goal ? <ProgressCard goal={goal} agentNames={agentNames} now={now} /> : null
    }
    case 'answer':
      return (
        <AnswerBubble
          message={message}
          agent={message.agentId ? agentNames[message.agentId] : undefined}
          speaker={speaker}
          grouped={grouped}
        />
      )
    case 'schedule_suggestion':
      return <ScheduleCard message={message} />
    case 'error':
      return <ErrorCard message={message} />
    default:
      return null
  }
}

export function Chat() {
  const { search, navigate } = useRouter()
  const toast = useToast()
  const client = useQueryClient()
  const reduceMotion = useReducedMotion()
  const speaker = useSpeaker()

  const selectedId = search.get('c')

  const conversationsQuery = useConversations()
  const conversationQuery = useConversation(selectedId)
  const agentsQuery = useAgents()
  const agentNames = useAgentNames()
  const activeAgents = useMemo(() => (agentsQuery.data ?? []).filter((agent) => agent.status === 'active'), [agentsQuery.data])

  const createConversation = useCreateConversation()
  const sendMessage = useSendMessage(selectedId ?? '')
  const reroute = useReroute(selectedId ?? '')

  const [sending, setSending] = useState(false)
  // What the person just sent, shown straight away while the coordinator decides who takes it,
  // so a model-routed reply that takes several seconds never looks like nothing happened.
  const [pending, setPending] = useState<{ text: string; mentioned: string[] } | null>(null)
  const [reroutingId, setReroutingId] = useState<string | null>(null)
  const [announcement, setAnnouncement] = useState('')

  const now = useNow(5_000)
  const detail = conversationQuery.data
  // Stable identities: an effect below depends on `messages`, and `?? []` would otherwise hand it
  // a fresh empty array every render there is no conversation loaded yet.
  const messages = useMemo(() => detail?.messages ?? [], [detail])
  const goals = useMemo(() => detail?.goals ?? [], [detail])

  const canCreateWork = can('task:create')

  /* ---- Scroll: stick to the bottom only when the reader was already near it ----------------- */
  const threadRef = useRef<HTMLDivElement | null>(null)
  const nearBottomRef = useRef(true)
  useEffect(() => {
    const el = threadRef.current
    if (!el) return
    const onScroll = () => {
      nearBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < NEAR_BOTTOM_PX
    }
    el.addEventListener('scroll', onScroll)
    return () => el.removeEventListener('scroll', onScroll)
  }, [])
  useEffect(() => {
    const el = threadRef.current
    if (!el || !nearBottomRef.current) return
    el.scrollTo({ top: el.scrollHeight, behavior: reduceMotion ? 'auto' : 'smooth' })
  }, [messages, reduceMotion])

  /* ---- New agent replies: announced once, and read aloud once when auto-read is on ---------- */
  const previousMessages = useRef<ChatMessage[] | undefined>(undefined)
  useEffect(() => {
    const seenBefore = previousMessages.current !== undefined
    const newAnswerIds = newMessageIds(previousMessages.current, messages, 'answer')
    if (seenBefore && newAnswerIds.length > 0) {
      const fresh = messages.filter((message) => newAnswerIds.includes(message.id))
      setAnnouncement(
        fresh
          .map((message) => `New reply from ${(message.agentId && agentNames[message.agentId]?.name) || 'an agent'}.`)
          .join(' '),
      )
      // Several agents can answer at once; each speak() call stops the one before it, so only
      // the most recent new answer is actually heard. That is the one a person is most likely to
      // still be looking at.
      const last = fresh[fresh.length - 1]
      if (last) void speaker.speak(last.content, last.agentId)
    }
    previousMessages.current = messages
  }, [messages, agentNames, speaker])

  function selectConversation(id: string | null) {
    navigate(id ? `/chat?c=${id}` : '/chat', { replace: true })
  }

  async function handleSend(text: string, agentIds: string[]) {
    setSending(true)
    setPending({
      text,
      mentioned: agentIds.map((id) => agentNames[id]?.name).filter((name): name is string => Boolean(name)),
    })
    try {
      const agentIdsField = agentIds.length > 0 ? { agentIds } : {}
      let conversationId = selectedId
      if (conversationId) {
        await sendMessage.mutateAsync({ text, ...agentIdsField })
      } else {
        // A conversation is created the moment the first message needs one, and opened before
        // the message is sent, so the thread (and the pending message in it) shows at once.
        // useSendMessage is bound to the conversation id it was given when this component last
        // rendered, which cannot yet be this new one, so this call goes straight to the platform.
        const created = await createConversation.mutateAsync({})
        conversationId = created.id
        selectConversation(created.id)
        await api<{ messages: ChatMessage[] }>(`/api/conversations/${created.id}/messages`, {
          method: 'POST',
          body: { text, ...agentIdsField },
        })
        void client.invalidateQueries({ queryKey: ['conversations'] })
        void client.invalidateQueries({ queryKey: ['board'] })
      }
      // Waits for the thread to refetch, so the real messages replace the pending one without
      // a blank moment in between.
      await client.invalidateQueries({ queryKey: ['conversations', conversationId] })
    } catch (error) {
      toast.error(describeApiError(error))
    } finally {
      setPending(null)
      setSending(false)
    }
  }

  async function handleReroute(messageId: string, agentId: string) {
    setReroutingId(messageId)
    try {
      await reroute.mutateAsync({ messageId, agentId })
    } catch (error) {
      toast.error(describeApiError(error))
    } finally {
      setReroutingId(null)
    }
  }

  return (
    <div className="page chat-page">
      <PageHeader
        eyebrow="Talk to the workforce"
        title="Chat"
        description="Ask for work in plain words. The right agent picks it up, hands over to others when the job needs it, and asks before anything leaves the workspace."
        action={
          speaker.provider !== 'none' ? (
            <IconButton
              label={speaker.muted ? 'Turn on reading replies aloud' : 'Turn off reading replies aloud'}
              title={speaker.muted ? 'Replies are not read aloud' : 'New replies are read aloud'}
              aria-pressed={!speaker.muted}
              data-active={!speaker.muted || undefined}
              onClick={() => speaker.setMuted(!speaker.muted)}
            >
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M3 6h2.3L9 3.2v9.6L5.3 10H3z" fill="currentColor" />
                {speaker.muted ? (
                  <path d="M11 6.5l3 3M14 6.5l-3 3" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
                ) : (
                  <path d="M11.2 5.3a4 4 0 0 1 0 5.4" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
                )}
              </svg>
            </IconButton>
          ) : undefined
        }
      />

      {!canCreateWork && (
        <Notice tone="info">
          Your role can ask document questions here, but cannot start agents on new work. Someone whose role can
          create tasks can do that.
        </Notice>
      )}

      <p className="visually-hidden" role="status">
        {announcement}
      </p>

      <div className="chat-layout">
        <ConversationRail
          conversations={conversationsQuery.data}
          loading={conversationsQuery.isLoading}
          selectedId={selectedId}
          onSelect={selectConversation}
          onNew={() => selectConversation(null)}
        />

        <div className="chat-main">
          <div className="chat-thread" ref={threadRef}>
            {!selectedId && pending ? (
              <ol className="chat-messages">
                <li>
                  <PendingMessage text={pending.text} mentioned={pending.mentioned} />
                </li>
              </ol>
            ) : !selectedId ? (
              <WelcomeScreen agents={agentsQuery.data} onPick={(text) => void handleSend(text, [])} />
            ) : (
              <QueryState query={conversationQuery} permission="chat:use" what="this conversation" rows={4}>
                {() =>
                  messages.length === 0 && !pending ? (
                    <WelcomeScreen agents={agentsQuery.data} onPick={(text) => void handleSend(text, [])} />
                  ) : (
                    <ol className="chat-messages">
                      {groupMessages(messages).map(({ message, grouped }) => (
                        <li key={message.id}>
                          <MessageItem
                            message={message}
                            grouped={grouped}
                            goals={goals}
                            agentNames={agentNames}
                            speaker={speaker}
                            now={now}
                            onReroute={(messageId, agentId) => void handleReroute(messageId, agentId)}
                            reroutingId={reroutingId}
                          />
                        </li>
                      ))}
                      {pending && (
                        <li>
                          <PendingMessage text={pending.text} mentioned={pending.mentioned} />
                        </li>
                      )}
                    </ol>
                  )
                }
              </QueryState>
            )}
          </div>

          <Composer agents={activeAgents} onSend={(text, agentIds) => void handleSend(text, agentIds)} sending={sending} />
        </div>
      </div>
    </div>
  )
}

/** The message just sent, and what the coordinator is doing with it, until the reply arrives. */
function PendingMessage({ text, mentioned }: { text: string; mentioned: string[] }) {
  return (
    <>
      <div className="chat-bubble-row chat-bubble-row-user">
        <div className="chat-bubble chat-bubble-user">
          <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', margin: 0 }}>{text}</p>
        </div>
      </div>
      <div className="chat-pending" role="status">
        <Spinner />
        <span>
          {mentioned.length > 0
            ? `Handing this to ${mentioned.join(' and ')}.`
            : 'Finding the right agent for this.'}
        </span>
      </div>
    </>
  )
}
