import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient, type InfiniteData, type QueryClient } from '@tanstack/react-query'
import { ChatSidebar } from '../components/chat/ChatSidebar'
import { Composer } from '../components/chat/Composer'
import { ThreadSkeleton } from '../components/chat/ChatSkeletons'
import { JumpToLatest } from '../components/chat/JumpToLatest'
import { MessageList } from '../components/chat/MessageList'
import { AddPeopleDialog } from '../components/chat/AddPeopleDialog'
import { ThreadHeader } from '../components/chat/ThreadHeader'
import { WelcomeScreen } from '../components/chat/WelcomeScreen'
import { WorkPanel } from '../components/chat/WorkPanel'
import { WorkStrip } from '../components/chat/WorkStrip'
import {
  activeGoals,
  autoAnswerTarget,
  composerAnswer,
  conversationText,
  draftKey,
  goalTarget,
  newestPosition,
  newMessageIds,
  pendingEchoed,
  readyAnswers,
  routingMessageForGoal,
  sendsAsNewRequest,
} from '../components/chat/chatModel'
import { DetailsContext } from '../components/chat/detailsContext'
import type { DetailsMode, RevealGoal } from '../components/chat/detailsContext'
import { useStickToBottom } from '../components/chat/useStickToBottom'
import { useChatShortcuts, chatShortcutGroups } from '../components/chat/useChatShortcuts'
import { Button, ConfirmDialog, EmptyState, ErrorState } from '../components/ui'
import { Sheet } from '../components/ui/Sheet'
import { ShortcutsDialog } from '../components/ui/ShortcutsDialog'
import { ApiError, api, describeApiError } from '../lib/api'
import { useEarlierPages, useSetConversationVisibility } from '../lib/chatQueries'
import { useCopyText } from '../lib/clipboard'
import { useReducedMotion } from '../hooks/useReducedMotion'
import {
  isGoalActive,
  useAgentNames,
  useAgents,
  useAnswerFromDocuments,
  useAnswerQuestion,
  useConversation,
  useCreateConversation,
  useDeleteConversation,
  useMarkConversationRead,
  useMemberNames,
  usePinConversation,
  useRenameConversation,
  useReroute,
  useRetryChatGoal,
  useSendMessage,
  useStopChatGoal,
} from '../lib/queries'
import type { Agent, BoardGoal, ChatMessage, ConversationPage, MessagesPage, RunQuestion } from '../lib/queries'
import { usePersistentState } from '../lib/persist'
import { useRouter } from '../lib/router'
import { can, profile } from '../lib/session'
import { useToast } from '../lib/toast'
import { useNow } from '../lib/useNow'
import { useSpeaker } from '../lib/voice'

/*
 * Talk to the whole workforce (B1). The shell holds selection, the sidebar and work-panel modes,
 * the reply target (chosen or automatic, B1.7), the details mode, dismissed auto-answer ids,
 * announcements and shortcuts; every card and list below it is a small, mostly stateless component
 * in components/chat/.
 *
 * The thread shown is the conversation's live window plus anything older already loaded
 * (lib/chatQueries' useEarlierPages): "Show earlier messages" and a deep link to an older message
 * page back through it. A conversation that cannot be opened (deleted, someone else's, or a
 * failed load) says so in place of the thread, and nothing can be sent into it.
 */

const NO_MESSAGES: ChatMessage[] = []

/** What the person is told about a conversation that is gone, in the thread and when it vanishes mid-visit. */
const NOT_AVAILABLE = 'This conversation was deleted or you do not have access to it.'

function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => (typeof window !== 'undefined' ? window.matchMedia(query).matches : false))
  useEffect(() => {
    const list = window.matchMedia(query)
    const onChange = () => setMatches(list.matches)
    onChange()
    list.addEventListener('change', onChange)
    return () => list.removeEventListener('change', onChange)
  }, [query])
  return matches
}

/**
 * A function with one identity for as long as its component lives, which always runs the latest
 * `fn`. The thread is memoised, and its handlers close over state that changes on every poll, so
 * handing it the handlers themselves would redraw every message each time anything moved.
 */
function useStableCallback<Args extends unknown[], Result>(fn: (...args: Args) => Result): (...args: Args) => Result {
  const latest = useRef(fn)
  useEffect(() => {
    latest.current = fn
  })
  return useCallback((...args: Args) => latest.current(...args), [])
}

/**
 * The ids of the conversations in the sidebar's list, in the order it shows them (pinned, needing
 * you, then the rest), read from the cache. The sidebar owns that list and its filters, so this
 * reads what it fetched rather than running a second list query of its own. It is asked when a
 * shortcut is pressed, so nothing here needs to re-render when the list changes.
 */
function listedConversationIds(client: QueryClient): string[] {
  const cache = client.getQueryCache()
  const active = cache.findAll({ queryKey: ['conversations', 'list'], type: 'active' })
  const [list] = active.length > 0 ? active : cache.findAll({ queryKey: ['conversations', 'list'] })
  const pages = (list?.state.data as InfiniteData<ConversationPage, number> | undefined)?.pages ?? []
  const seen = new Set<string>()
  const ids: string[] = []
  for (const page of pages) {
    for (const row of [...page.pinned, ...page.needsYou, ...page.conversations]) {
      if (!seen.has(row.id)) {
        seen.add(row.id)
        ids.push(row.id)
      }
    }
  }
  return ids
}

const isSidebarMode = (value: unknown): value is 'open' | 'rail' => value === 'open' || value === 'rail'
const isPanelMode = (value: unknown): value is 'open' | 'closed' => value === 'open' || value === 'closed'
const isDetailsMode = (value: unknown): value is DetailsMode => value === 'auto' || value === 'expanded' || value === 'collapsed'

export function Chat() {
  const { search, hash, navigate } = useRouter()
  const toast = useToast()
  const copy = useCopyText()
  const client = useQueryClient()
  const reduceMotion = useReducedMotion()
  // useSpeaker returns a new object on every render; the thread is memoised, so it is handed one
  // that changes only when something in it does.
  const { speak, stop, speaking, speakingKey, provider, muted, setMuted } = useSpeaker()
  const speaker = useMemo(
    () => ({ speak, stop, speaking, speakingKey, provider, muted, setMuted }),
    [speak, stop, speaking, speakingKey, provider, muted, setMuted],
  )
  const me = profile()?.userId ?? null
  const canCreateWork = can('task:create')

  const selectedId = search.get('c')
  const goalDeepLink = search.get('goal')

  const isDesktop = useMediaQuery('(min-width: 1024px)')
  const isTablet = useMediaQuery('(min-width: 768px)')
  const isWide = useMediaQuery('(min-width: 1440px)')

  const [sidebarMode, setSidebarMode] = usePersistentState<'open' | 'rail'>('chat.sidebar', 'open', isSidebarMode)
  const [panelMode, setPanelMode] = usePersistentState<'open' | 'closed'>('chat.panel.v2', 'closed', isPanelMode)
  const [detailsMode, setDetailsMode] = usePersistentState<DetailsMode>('chat.details', 'auto', isDetailsMode)
  const [detailsVersion, setDetailsVersion] = useState(0)
  const [overlayOpen, setOverlayOpen] = useState(false)
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false)
  const [shortcutsOpen, setShortcutsOpen] = useState(false)
  const [addPeopleOpen, setAddPeopleOpen] = useState(false)
  const [focusSection, setFocusSection] = useState<'search' | 'needs-you' | null>(null)

  const [sending, setSending] = useState(false)
  // `afterPosition`: the newest position in the thread when the send started, so the stored copy
  // of the message can be told apart from an earlier one with the same words.
  const [pending, setPending] = useState<{ text: string; mentioned: string[]; afterPosition: number } | null>(null)
  // A message that could not be sent stays on screen with the reason and a way to send it again,
  // rather than vanishing into a toast.
  const [failedSend, setFailedSend] = useState<{
    text: string
    agentIds: string[]
    attachmentIds?: string[]
    reason: string
  } | null>(null)
  const [reroutingId, setReroutingId] = useState<string | null>(null)
  const [answeringMessageId, setAnsweringMessageId] = useState<string | null>(null)
  const [announcement, setAnnouncement] = useState('')
  const [replyChoice, setReplyChoice] = useState<{ questionId: string; agentName: string; agentId: string } | null>(null)
  const [dismissedIds, setDismissedIds] = useState<ReadonlySet<string>>(new Set())
  const [prefill, setPrefill] = useState<{ token: number; text: string } | null>(null)
  const [mentionRequest, setMentionRequest] = useState<{ token: number; agent: Agent } | null>(null)
  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [revealGoal, setRevealGoal] = useState<RevealGoal | null>(null)

  const inputRef = useRef<HTMLTextAreaElement | null>(null)
  /** The chat panel, where files dropped anywhere on it are attached (Composer). */
  const mainRef = useRef<HTMLElement | null>(null)
  const scrollRef = useRef<HTMLDivElement | null>(null)
  const shellRef = useRef<HTMLDivElement | null>(null)
  const searchRef = useRef<HTMLInputElement | null>(null)
  // Only the day dividers read the clock, and they change at midnight, so a minute is plenty.
  const now = useNow(60_000)

  const conversationQuery = useConversation(selectedId)
  const agentsQuery = useAgents()
  const agentNames = useAgentNames()
  const memberNames = useMemberNames()
  const activeAgents = useMemo(() => (agentsQuery.data ?? []).filter((agent) => agent.status === 'active'), [agentsQuery.data])

  const createConversation = useCreateConversation()
  const sendMessage = useSendMessage(selectedId ?? '')
  const reroute = useReroute(selectedId ?? '')
  const rename = useRenameConversation()
  const setVisibility = useSetConversationVisibility()
  const pinConversation = usePinConversation()
  const deleteConversation = useDeleteConversation()
  const answerQuestion = useAnswerQuestion()
  const stopGoal = useStopChatGoal(selectedId ?? '')
  const retryGoal = useRetryChatGoal(selectedId ?? '')
  const answerFromDocuments = useAnswerFromDocuments(selectedId ?? '')
  const markRead = useMarkConversationRead(selectedId ?? '')

  const detail = conversationQuery.data
  const conversation = detail?.conversation ?? null
  // The live window (newest messages, polled), and the whole thread as far as it is loaded.
  const liveMessages = useMemo(() => detail?.messages ?? NO_MESSAGES, [detail])
  const earlier = useEarlierPages(selectedId, liveMessages, detail?.hasEarlier ?? false)
  const messages = earlier.messages
  const goals = useMemo(() => detail?.goals ?? [], [detail])

  /* ---- A conversation that cannot be opened ------------------------------------------------- */
  const loadError = conversationQuery.error
  const loadNotFound = loadError instanceof ApiError && loadError.isNotFound
  const hasDetail = detail !== undefined
  // A conversation found gone while it was open stays unavailable until the person moves on, even
  // while its removed copy is asked for again. Adjusted during render (the "previous value"
  // pattern, 0.2), like the rest of this screen's derived state.
  const [goneId, setGoneId] = useState<string | null>(null)
  if (goneId !== null && goneId !== selectedId) setGoneId(null)
  else if (selectedId && loadNotFound && hasDetail && goneId !== selectedId) setGoneId(selectedId)
  const gone = selectedId !== null && goneId === selectedId
  const notFound = loadNotFound || gone
  // Otherwise only when nothing was ever loaded: a failed background refresh keeps the thread readable.
  const unavailable = Boolean(selectedId) && ((conversationQuery.isError && !detail) || gone)
  const handledNotFound = useRef<string | null>(null)
  useEffect(() => {
    if (!selectedId || !loadNotFound || handledNotFound.current === selectedId) return
    handledNotFound.current = selectedId
    try {
      localStorage.removeItem(draftKey(me ?? 'anonymous', selectedId))
    } catch {
      // Storage blocked: there is no draft to clear.
    }
    if (hasDetail) {
      // Deleted (or closed to this person) while open: say so, drop the copy still on screen,
      // and refresh the list so its row goes too.
      toast.info(NOT_AVAILABLE)
      client.removeQueries({ queryKey: ['conversations', selectedId] })
      void client.invalidateQueries({ queryKey: ['conversations', 'list'] })
    }
  }, [selectedId, loadNotFound, hasDetail, me, client, toast])
  // A conversation that was never there leaves the cache once the person moves on. Not before:
  // removing the query it is still showing would only send the same request again.
  useEffect(() => {
    const id = selectedId
    return () => {
      if (id && handledNotFound.current === id) client.removeQueries({ queryKey: ['conversations', id] })
    }
  }, [selectedId, client])

  // The sidebar polls slowly (15 s). When work in the open conversation settles, refresh it at
  // once, so its row does not keep saying "Working" after the answer has already arrived.
  const activeGoalCount = goals.filter(isGoalActive).length
  const previousActiveCount = useRef(activeGoalCount)
  useEffect(() => {
    if (activeGoalCount < previousActiveCount.current) {
      void client.invalidateQueries({ queryKey: ['conversations', 'list'] })
    }
    previousActiveCount.current = activeGoalCount
  }, [activeGoalCount, client])
  const questions = useMemo(() => detail?.questions ?? [], [detail])

  const nameOf = (agentId: string | null | undefined) => (agentId && agentNames[agentId]?.name) || 'The agent'

  /* ---- Which question the composer answers (B1.7, D-14) ------------------------------------- */
  const autoTarget = replyChoice ? null : autoAnswerTarget(questions, messages, me, dismissedIds)
  const replyTo = replyChoice
    ? { questionId: replyChoice.questionId, agentName: replyChoice.agentName, agentId: replyChoice.agentId, auto: false }
    : autoTarget
      ? { questionId: autoTarget.id, agentName: nameOf(autoTarget.agentId), agentId: autoTarget.agentId, auto: true }
      : null

  const lastUserText = useMemo(() => {
    for (let index = messages.length - 1; index >= 0; index -= 1) {
      const message = messages[index]!
      if (message.kind === 'text' && message.authorKind === 'user') return message.content
    }
    return null
  }, [messages])

  const participants = useMemo(() => {
    const seen = new Set<string>()
    const list: Agent[] = []
    for (const goal of goals) {
      for (const task of goal.tasks) {
        const agent = task.agentId ? agentNames[task.agentId] : undefined
        if (task.agentId && agent && !seen.has(task.agentId)) {
          seen.add(task.agentId)
          list.push(agent)
        }
      }
    }
    return list
  }, [goals, agentNames])

  /* ---- Scrolling (B1.8) ---------------------------------------------------------------------- */
  const stick = useStickToBottom(scrollRef, { newestPosition: newestPosition(messages), resetKey: selectedId, reducedMotion: reduceMotion })

  const lastMarkedPosition = useRef(-1)
  useEffect(() => {
    if (!selectedId || !stick.nearBottom) return
    if (typeof document !== 'undefined' && document.visibilityState !== 'visible') return
    const position = messages[messages.length - 1]?.position ?? -1
    if (position < 0 || position === lastMarkedPosition.current) return
    const timer = window.setTimeout(() => {
      lastMarkedPosition.current = position
      markRead.mutate({ position })
    }, 1_000)
    return () => window.clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId, stick.nearBottom, messages])

  /* ---- New agent replies: announced once, and read aloud once when auto-read is on ----------
   * Tracked by the keyed AnswerAnnouncer below rather than here: giving it `key={selectedId}`
   * remounts it whenever the conversation changes, so its own refs start blank again on their
   * own. That avoids an effect whose only job would be resetting state for a prop change, which
   * both the "previous value" render-time pattern and a plain effect handle awkwardly here (the
   * announcer needs two refs and a multi-branch retry, not a single comparison).
   */
  const [freshIds, setFreshIds] = useState<ReadonlySet<string>>(new Set())

  /* ---- Mobile keyboard (D12): a small visualViewport listener, no React state ---------------- */
  useEffect(() => {
    const shell = shellRef.current
    const vv = window.visualViewport
    if (!shell || !vv) return
    // Only an on-screen keyboard should shrink the shell. Anywhere else the full window height
    // (100dvh) is right, and a stored pixel height goes stale: a desktop window resize does not
    // always fire the visual viewport's own resize, and pinch zoom shrinks it without any keyboard.
    // So the property is set only while something covers part of the page, and removed otherwise.
    const onResize = () => {
      const covered = window.innerHeight - vv.height > 80 && Math.abs(vv.scale - 1) < 0.01
      if (covered) shell.style.setProperty('--chat-vvh', `${vv.height}px`)
      else shell.style.removeProperty('--chat-vvh')
    }
    onResize()
    vv.addEventListener('resize', onResize)
    vv.addEventListener('scroll', onResize)
    window.addEventListener('resize', onResize)
    return () => {
      vv.removeEventListener('resize', onResize)
      vv.removeEventListener('scroll', onResize)
      window.removeEventListener('resize', onResize)
      shell.style.removeProperty('--chat-vvh')
    }
  }, [])

  /* ---- Deep link: #m-, #question- or ?goal=, whenever the link or the conversation changes ----
   * A message older than everything loaded is paged back to, one earlier page at a time, until it
   * turns up or the conversation has nothing earlier left.
   */
  const deepLinkReady = Boolean(selectedId) && detail !== undefined
  useEffect(() => {
    if (!deepLinkReady) return
    if (hash.startsWith('#question-')) {
      handleGoTo(hash.slice(1))
      return
    }
    const messageId = hash.startsWith('#m-') ? hash.slice('#m-'.length) : null
    if (!messageId && !goalDeepLink) return
    const find = (list: readonly ChatMessage[]) =>
      messageId ? list.find((message) => message.id === messageId) : routingMessageForGoal(goalDeepLink!, list)

    let cancelled = false
    async function seek() {
      let target = find(messages)
      let more = earlier.hasEarlier
      while (!target && more && !cancelled) {
        let page: MessagesPage | null
        try {
          page = await earlier.loadEarlier(stick.keepPosition)
        } catch {
          break
        }
        if (!page) break
        target = find(page.messages)
        more = page.hasEarlier
      }
      if (cancelled) return
      if (target) handleGoTo(`m-${target.id}`)
      else toast.info('That message could not be found.')
    }
    void seek()
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deepLinkReady, selectedId, hash, goalDeepLink])

  /** Opens a conversation; `messageId` (a search hit) is the message to scroll to in it. */
  function selectConversation(id: string | null, messageId?: string) {
    navigate(id ? `/chat?c=${id}${messageId ? `#m-${messageId}` : ''}` : '/chat')
    setOverlayOpen(false)
    setMobileSidebarOpen(false)
  }

  async function handleShowEarlier() {
    try {
      const page = await earlier.loadEarlier(stick.keepPosition)
      if (!page) return
      if (page.messages.length > 0) {
        setAnnouncement(page.messages.length === 1 ? 'Loaded 1 earlier message.' : `Loaded ${page.messages.length} earlier messages.`)
      }
      // The button goes once there is nothing earlier: keep focus in the thread, on the oldest message.
      const oldest = page.messages[0]
      if (!page.hasEarlier && oldest) document.getElementById(`m-${oldest.id}`)?.querySelector('article')?.focus()
    } catch (error) {
      toast.error(describeApiError(error))
    }
  }

  async function handleSend(text: string, agentIds: string[], attachmentIds: string[] = []): Promise<boolean> {
    if (unavailable) return false
    const question = replyTo ? questions.find((q) => q.id === replyTo.questionId) : undefined
    if (replyTo && !question) {
      toast.error('This question is no longer open.')
      return false
    }
    // An automatic answer gives way to a mention of some other agent: that is a new request.
    if (replyTo && question && !sendsAsNewRequest({ auto: replyTo.auto, agentId: question.agentId }, agentIds)) {
      const payload = composerAnswer(question, text)
      try {
        await answerQuestion.mutateAsync({ id: question.id, answers: payload.answers, note: payload.note ?? null, via: 'chat' })
        setReplyChoice(null)
        setAnnouncement(`Answer sent. ${nameOf(question.agentId)} is continuing.`)
        const others = agentIds.filter((id) => id !== question.agentId).map((id) => nameOf(id))
        if (others.length > 0) {
          // A reply the person chose stays a reply: the mention goes with the answer, not to the agent.
          toast.info(
            `Sent as your answer to ${nameOf(question.agentId)}. To ask ${others.join(' and ')} as well, send a new message.`,
          )
        }
        return true
      } catch (error) {
        if (error instanceof ApiError && error.status === 409) {
          await client.invalidateQueries({ queryKey: ['conversations', selectedId ?? ''] })
          toast.error('Someone already answered this question.')
        } else {
          toast.error(describeApiError(error))
        }
        return false
      }
    }

    setSending(true)
    setFailedSend(null)
    setPending({
      text: text || (attachmentIds.length === 1 ? 'Sending 1 file' : `Sending ${attachmentIds.length} files`),
      mentioned: agentIds.map((id) => agentNames[id]?.name).filter((n): n is string => Boolean(n)),
      afterPosition: selectedId ? newestPosition(messages) : -1,
    })
    try {
      const agentIdsField = {
        ...(agentIds.length > 0 ? { agentIds } : {}),
        ...(attachmentIds.length > 0 ? { attachmentIds } : {}),
      }
      let conversationId = selectedId
      if (conversationId) {
        await sendMessage.mutateAsync({ text, ...agentIdsField })
      } else {
        // A conversation is created the moment the first message needs one, and opened before the
        // message is sent, so the thread (and the pending message in it) shows at once. The
        // conversationId-bound useSendMessage mutation above is still bound to the old (empty) id
        // at this point in the render, so the very first message goes straight to the platform.
        const created = await createConversation.mutateAsync({})
        conversationId = created.id
        navigate(`/chat?c=${created.id}`)
        await api<{ messages: ChatMessage[] }>(`/api/conversations/${created.id}/messages`, {
          method: 'POST',
          body: { text, ...agentIdsField },
        })
        await client.invalidateQueries({ queryKey: ['conversations'] })
        await client.invalidateQueries({ queryKey: ['board'] })
      }
      await client.invalidateQueries({ queryKey: ['conversations', conversationId] })
      return true
    } catch (error) {
      setFailedSend({ text, agentIds, attachmentIds, reason: describeApiError(error) })
      setAnnouncement('Your message could not be sent.')
      return false
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

  function handleAskAgain(goal: BoardGoal) {
    const routingMessage = routingMessageForGoal(goal.id, messages)
    const text = routingMessage?.detail.requestText ?? goal.description
    const lastTask = goal.tasks[goal.tasks.length - 1]
    const agentIds = lastTask?.agentId ? [lastTask.agentId] : []
    void handleSend(text, agentIds)
  }

  function handleWelcomeMention(agent: Agent) {
    setMentionRequest({ token: Date.now(), agent })
    window.setTimeout(() => inputRef.current?.focus(), 0)
  }

  function handleEditAndResend(text: string) {
    setPrefill({ token: Date.now(), text })
    // After the prefill renders, so the caret lands in the filled box.
    window.setTimeout(() => {
      const el = inputRef.current
      if (!el) return
      el.focus()
      el.selectionStart = el.selectionEnd = el.value.length
    }, 0)
  }

  function handleReplyInOwnWords(questionId: string) {
    const question = questions.find((q) => q.id === questionId)
    if (!question) return
    setReplyChoice({ questionId, agentName: nameOf(question.agentId), agentId: question.agentId })
    inputRef.current?.focus()
  }

  function handleClearReply() {
    if (replyChoice) {
      setReplyChoice(null)
      return
    }
    if (autoTarget) setDismissedIds((current) => new Set(current).add(autoTarget.id))
  }

  async function handleStop(goalId: string) {
    try {
      await stopGoal.mutateAsync({ goalId })
      setAnnouncement('Stopped.')
    } catch (error) {
      toast.error(describeApiError(error))
    }
  }

  async function handleRetry(goalId: string) {
    try {
      await retryGoal.mutateAsync({ goalId })
      setAnnouncement('Trying again.')
    } catch (error) {
      toast.error(describeApiError(error))
    }
  }

  async function handleAnswerFromDocuments(messageId: string) {
    setAnsweringMessageId(messageId)
    try {
      await answerFromDocuments.mutateAsync({ messageId })
    } catch (error) {
      toast.error(describeApiError(error))
    } finally {
      setAnsweringMessageId(null)
    }
  }

  function handleGoTo(elementId: string) {
    const el = document.getElementById(elementId)
    if (!el) return
    el.scrollIntoView?.({ block: 'center' })
    const article = el.matches('article') ? el : el.querySelector('article')
    ;(article as HTMLElement | null)?.focus?.()
  }

  /**
   * "Review" on a goal waiting for an approval: its progress card, opened, with Approve focused
   * (ProgressCard reads `revealGoal`). When that card is not loaded, the approvals queue instead.
   */
  function handleReview(goalId: string) {
    const target = goalTarget(goalId, messages, 'approval')
    if ('href' in target) {
      navigate(target.href)
      return
    }
    setRevealGoal((current) => ({ goalId, nonce: (current?.nonce ?? 0) + 1 }))
    handleGoTo(target.elementId)
  }

  async function handleRename(title: string): Promise<boolean> {
    if (!selectedId) return false
    try {
      await rename.mutateAsync({ id: selectedId, title })
      setAnnouncement('Conversation renamed.')
      return true
    } catch (error) {
      toast.error(describeApiError(error))
      return false
    }
  }

  async function handleVisibility(visibility: 'private' | 'workspace') {
    if (!selectedId) return
    try {
      await setVisibility.mutateAsync({ id: selectedId, visibility })
      const words =
        visibility === 'workspace'
          ? 'Shared. Everyone in this workspace can read this conversation now.'
          : 'Made private. Only you and people you add can read this conversation.'
      setAnnouncement(words)
      toast.success(words)
    } catch (error) {
      toast.error(describeApiError(error))
    }
  }

  async function handleTogglePin() {
    if (!selectedId || !conversation) return
    try {
      await pinConversation.mutateAsync({ id: selectedId, pinned: !conversation.pinned })
    } catch (error) {
      toast.error(describeApiError(error))
    }
  }

  async function handleDelete() {
    if (!selectedId) return
    setDeleteError(null)
    try {
      await deleteConversation.mutateAsync(selectedId)
      setDeleteConfirmOpen(false)
      setAnnouncement('Conversation deleted.')
      selectConversation(null)
    } catch (error) {
      setDeleteError(describeApiError(error))
    }
  }

  function handleEscape(event?: KeyboardEvent) {
    // An Esc that closed a menu (which has already handed focus back to its trigger, and may
    // already be gone from the page) stays with the menu: it must not also jump to the composer.
    if (event?.target instanceof Element && event.target.closest('.menu-panel, .more-menu')) return
    const active = document.activeElement
    if (active?.closest('.menu-panel, dialog[open]')) return
    if (isTablet && !isDesktop && overlayOpen) {
      setOverlayOpen(false)
      return
    }
    if (active?.id === 'chat-sidebar-search-input') return
    if (active === inputRef.current) return
    inputRef.current?.focus()
  }

  function stepConversation(step: 1 | -1) {
    const conversationOrder = listedConversationIds(client)
    if (conversationOrder.length === 0) return
    const index = selectedId ? conversationOrder.indexOf(selectedId) : -1
    const next = conversationOrder[(index + step + conversationOrder.length) % conversationOrder.length]
    if (next) selectConversation(next)
  }

  useChatShortcuts({
    onFocusSearch: () => {
      if (!isTablet) setMobileSidebarOpen(true)
      else if (!isDesktop) setOverlayOpen(true)
      else setSidebarMode('open')
      setFocusSection('search')
      window.setTimeout(() => setFocusSection(null), 0)
    },
    onNewConversation: () => {
      selectConversation(null)
      inputRef.current?.focus()
    },
    onToggleSidebar: () => {
      if (!isTablet) setMobileSidebarOpen((current) => !current)
      else if (!isDesktop) setOverlayOpen((current) => !current)
      else setSidebarMode((current) => (current === 'open' ? 'rail' : 'open'))
    },
    onPreviousConversation: () => stepConversation(-1),
    onNextConversation: () => stepConversation(1),
    onShowShortcuts: () => setShortcutsOpen(true),
    onEscape: handleEscape,
  })

  // One identity each for as long as the screen lives, so the memoised thread is not redrawn by a
  // poll that changed nothing in it.
  const onReroute = useStableCallback((messageId: string, agentId: string) => void handleReroute(messageId, agentId))
  const onAskAgain = useStableCallback((goal: BoardGoal) => handleAskAgain(goal))
  const onEditAndResend = useStableCallback((text: string) => handleEditAndResend(text))
  const onReplyInOwnWords = useStableCallback((questionId: string) => handleReplyInOwnWords(questionId))
  const onStop = useStableCallback((goalId: string) => void handleStop(goalId))
  const onRetry = useStableCallback((goalId: string) => void handleRetry(goalId))
  const onAnswerFromDocuments = useStableCallback((messageId: string) => void handleAnswerFromDocuments(messageId))

  const workGoals = activeGoals(goals)
  const showWorkPanel = isWide && panelMode === 'open'

  const detailsValue = useMemo(() => ({ mode: detailsMode, version: detailsVersion, revealGoal }), [detailsMode, detailsVersion, revealGoal])
  const pendingBubbleShown = pending ? !pendingEchoed(messages, pending, me) : false

  const sidebarVariant: 'panel' | 'rail' | 'overlay' = !isTablet ? 'panel' : !isDesktop ? (overlayOpen ? 'overlay' : 'rail') : sidebarMode === 'open' ? 'panel' : 'rail'

  const sidebarElement = (
    <ChatSidebar
      variant={isTablet ? sidebarVariant : 'sheet'}
      onExpand={() => {
        if (!isTablet) setMobileSidebarOpen(true)
        else if (!isDesktop) setOverlayOpen(true)
        else setSidebarMode('open')
      }}
      onCollapse={() => {
        if (!isTablet) setMobileSidebarOpen(false)
        else if (!isDesktop) setOverlayOpen(false)
        else setSidebarMode('rail')
      }}
      selectedId={selectedId}
      onSelect={(id, messageId) => selectConversation(id, messageId)}
      onNew={() => selectConversation(null)}
      searchRef={searchRef}
      focusSection={focusSection}
    />
  )

  return (
    <div className="chat-shell" ref={shellRef} data-sidebar={isTablet ? sidebarVariant : 'sheet'} data-panel={showWorkPanel ? 'open' : 'closed'}>
      <a className="skip-link" href="#chat-composer-input">
        Skip to message box
      </a>

      <p className="visually-hidden" role="status">
        {announcement}
      </p>

      <AnswerAnnouncer
        key={selectedId}
        messages={liveMessages}
        questions={questions}
        me={me}
        dismissedIds={dismissedIds}
        agentNames={agentNames}
        speaker={speaker}
        nameOf={nameOf}
        onAnnounce={setAnnouncement}
        onFreshIds={(ids) => setFreshIds((current) => new Set([...current, ...ids]))}
      />

      {isTablet ? sidebarElement : null}
      {!isTablet && (
        <Sheet open={mobileSidebarOpen} onClose={() => setMobileSidebarOpen(false)} side="left" eyebrow="Chat" title="Conversations" width="sm">
          {sidebarElement}
        </Sheet>
      )}

      <DetailsContext.Provider value={detailsValue}>
        <main className="chat-main" ref={mainRef}>
          <ThreadHeader
            eyebrow="Chat"
            title={unavailable ? 'Conversation unavailable' : conversation?.title || 'New conversation'}
            conversation={conversation}
            unavailable={unavailable}
            participants={participants}
            readOnly={!canCreateWork}
            onOpenSidebar={() => setMobileSidebarOpen(true)}
            onNewConversation={() => selectConversation(null)}
            onRename={handleRename}
            onTogglePin={() => void handleTogglePin()}
            onCopyLink={() => void copy(`${window.location.origin}/chat?c=${selectedId ?? ''}`, 'Link copied')}
            onCopyConversation={() =>
              void copy(
                conversationText(messages, agentNames),
                // Only what is loaded is copied; a long thread says how much that is.
                earlier.hasEarlier ? `Copied the ${messages.length} loaded messages` : 'Conversation copied',
              )
            }
            onDelete={() => {
              setDeleteError(null)
              setDeleteConfirmOpen(true)
            }}
            onSetVisibility={(visibility) => void handleVisibility(visibility)}
            onAddPeople={() => setAddPeopleOpen(true)}
            speaker={speaker}
            detailsMode={detailsMode}
            onDetailsMode={(mode) => {
              setDetailsMode(mode)
              setDetailsVersion((v) => v + 1)
            }}
            onShowShortcuts={() => setShortcutsOpen(true)}
            {...(isWide ? { workPanelOpen: showWorkPanel, onToggleWorkPanel: () => setPanelMode(showWorkPanel ? 'closed' : 'open') } : {})}
          />

          <div className="chat-scroll" role="region" aria-label="Messages" tabIndex={0} ref={scrollRef} data-jump-visible={(!stick.nearBottom && stick.newCount > 0) || undefined}>
            <div className="chat-column">
              {!selectedId && pending ? (
                <PendingMessage text={pending.text} mentioned={pending.mentioned} showBubble={pendingBubbleShown} />
              ) : !selectedId ? (
                <WelcomeScreen agents={agentsQuery.data} {...(canCreateWork ? { onMention: handleWelcomeMention } : {})} />
              ) : unavailable && notFound ? (
                <EmptyState
                  icon={<UnavailableIcon />}
                  title="This conversation cannot be opened"
                  body={NOT_AVAILABLE}
                  action={<Button onClick={() => selectConversation(null)}>Start a new conversation</Button>}
                />
              ) : unavailable ? (
                <ErrorState
                  title="This conversation could not be loaded"
                  message={describeApiError(loadError)}
                  onRetry={() => void conversationQuery.refetch()}
                />
              ) : conversationQuery.isLoading ? (
                <ThreadSkeleton />
              ) : messages.length === 0 && !pending ? (
                <WelcomeScreen agents={agentsQuery.data} {...(canCreateWork ? { onMention: handleWelcomeMention } : {})} />
              ) : (
                <>
                  {earlier.hasEarlier && (
                    <div className="row" style={{ justifyContent: 'center', marginBottom: 'var(--space-5)' }}>
                      <Button variant="outline" loading={earlier.loading} onClick={() => void handleShowEarlier()}>
                        Show earlier messages
                      </Button>
                    </div>
                  )}
                  <MessageList
                    messages={messages}
                    goals={goals}
                    questions={questions}
                    agentNames={agentNames}
                    memberNames={memberNames}
                    me={me}
                    speaker={speaker}
                    freshIds={freshIds}
                    reroutingId={reroutingId}
                    onReroute={onReroute}
                    onAskAgain={onAskAgain}
                    onEditAndResend={onEditAndResend}
                    onReplyInOwnWords={onReplyInOwnWords}
                    onStop={onStop}
                    onRetry={onRetry}
                    onAnswerFromDocuments={onAnswerFromDocuments}
                    conversationId={selectedId}
                    agentsForReroute={activeAgents}
                    answeringMessageId={answeringMessageId}
                    composerTargetQuestionId={replyTo?.questionId ?? null}
                    now={now}
                  />
                  {pending && <PendingMessage text={pending.text} mentioned={pending.mentioned} showBubble={pendingBubbleShown} />}
                </>
              )}
              {failedSend && !pending && (
                <div className="chat-pending" role="alert">
                  <span>
                    Your message was not sent. {failedSend.reason}
                  </span>
                  <Button
                    variant="outline"
                    onClick={() => {
                      const again = failedSend
                      void handleSend(again.text, again.agentIds, again.attachmentIds ?? [])
                    }}
                  >
                    Try again
                  </Button>
                  <Button variant="quiet" onClick={() => setFailedSend(null)}>
                    Dismiss
                  </Button>
                </div>
              )}
            </div>
          </div>

          <div className="chat-dock">
            <JumpToLatest visible={!stick.nearBottom && stick.newCount > 0} newCount={stick.newCount} onJump={stick.jump} />
            {!showWorkPanel && !unavailable && (
              <WorkStrip
                goals={workGoals}
                questions={questions}
                agentNames={agentNames}
                me={me}
                compact={!isTablet}
                onStop={onStop}
                onGoTo={handleGoTo}
                onReview={handleReview}
              />
            )}
            <Composer
              userId={me}
              conversationId={selectedId}
              agents={activeAgents}
              onSend={handleSend}
              sending={sending}
              replyTo={replyTo}
              onClearReply={handleClearReply}
              lastUserText={lastUserText}
              inputRef={inputRef}
              dropZoneRef={mainRef}
              {...(prefill ? { prefill } : {})}
              {...(mentionRequest ? { mention: mentionRequest } : {})}
              {...(unavailable
                ? { disabled: true, readOnlyNote: 'Nothing can be sent here. Start a new conversation instead.' }
                : !canCreateWork
                  ? { readOnlyNote: 'Your role can ask document questions here, but cannot start agents on new work.' }
                  : {})}
            />
          </div>
        </main>

        {showWorkPanel && !unavailable && (
          <WorkPanel
            goals={workGoals}
            questions={questions}
            participants={participants}
            agentNames={agentNames}
            me={me}
            onStop={onStop}
            onGoTo={handleGoTo}
            onReview={handleReview}
            onClose={() => setPanelMode('closed')}
          />
        )}
      </DetailsContext.Provider>

      <AddPeopleDialog open={addPeopleOpen} conversationId={selectedId} onClose={() => setAddPeopleOpen(false)} />
      <ShortcutsDialog open={shortcutsOpen} onClose={() => setShortcutsOpen(false)} groups={chatShortcutGroups()} />

      <ConfirmDialog
        open={deleteConfirmOpen}
        onClose={() => setDeleteConfirmOpen(false)}
        onConfirm={() => void handleDelete()}
        eyebrow="Delete conversation"
        title="Delete this conversation?"
        description={`"${conversation?.title || 'Untitled conversation'}" and its messages are removed for everyone. Work still running from it is stopped. This cannot be undone.`}
        confirmLabel="Delete"
        tone="danger"
        loading={deleteConversation.isPending}
        error={deleteError}
      />
    </div>
  )
}

/**
 * Watches the thread for new answers, questions and errors, and reports them upward: a live
 * announcement, and which ids counts as "arrived during this visit" for the error card's alert
 * role. `key={selectedId}` on its call site remounts this for every conversation, so its refs
 * always start blank for a freshly opened thread rather than replaying its whole history as new.
 */
function AnswerAnnouncer({
  messages,
  questions,
  me,
  dismissedIds,
  agentNames,
  speaker,
  nameOf,
  onAnnounce,
  onFreshIds,
}: {
  messages: ChatMessage[]
  questions: RunQuestion[]
  me: string | null
  dismissedIds: ReadonlySet<string>
  agentNames: Record<string, Agent>
  speaker: ReturnType<typeof useSpeaker>
  nameOf: (agentId: string | null | undefined) => string
  onAnnounce: (text: string) => void
  onFreshIds: (ids: string[]) => void
}) {
  const previousMessages = useRef<ChatMessage[] | undefined>(undefined)
  const unresolvedSince = useRef<Map<string, number>>(new Map())

  useEffect(() => {
    const seenBefore = previousMessages.current !== undefined
    if (!seenBefore) {
      previousMessages.current = messages
      return
    }
    const newAnswerIds = newMessageIds(previousMessages.current, messages, 'answer')
    const newQuestionIds = newMessageIds(previousMessages.current, messages, 'question')
    const newErrorIds = newMessageIds(previousMessages.current, messages, 'error')
    if (newErrorIds.length > 0) onFreshIds(newErrorIds)

    const parts: string[] = []
    if (newQuestionIds.length > 0) {
      const question = messages.find((message) => newQuestionIds.includes(message.id))
      if (question) {
        const willAutoAnswer = autoAnswerTarget(questions, messages, me, dismissedIds)?.id === question.detail.questionId
        parts.push(`New question from ${nameOf(question.agentId)}.${willAutoAnswer ? ' Type your answer, or choose an option.' : ''}`)
      }
    }

    if (newAnswerIds.length === 0) {
      if (parts.length > 0) onAnnounce(parts.join(' '))
      previousMessages.current = messages
      return
    }

    const fresh = messages.filter((message) => newAnswerIds.includes(message.id))
    const { ready, pending: stillPending } = readyAnswers(fresh, (agentId) => Boolean(agentNames[agentId]), unresolvedSince.current, Date.now())
    if (stillPending) {
      if (parts.length > 0) onAnnounce(parts.join(' '))
      // Retried on the next render (agentNames still resolving): previousMessages stays put.
      return
    }
    for (const message of ready) parts.push(`New reply from ${nameOf(message.agentId)}.`)
    const last = ready[ready.length - 1]
    if (last) void speaker.speak(last.content, last.agentId)
    if (parts.length > 0) onAnnounce(parts.join(' '))
    previousMessages.current = messages
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messages, agentNames, speaker])

  return null
}

function UnavailableIcon() {
  return (
    <svg width="26" height="26" viewBox="0 0 24 24" fill="none">
      <path d="M4 6.5h16v10H9l-4 3.5v-3.5H4z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
      <path d="M9.5 9.5l5 4M14.5 9.5l-5 4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  )
}

/**
 * The message just sent, and what the coordinator is doing with it, until the reply arrives. The
 * bubble itself goes as soon as the thread shows the stored copy (`showBubble` false); the status
 * line stays until the send has finished.
 */
function PendingMessage({ text, mentioned, showBubble }: { text: string; mentioned: string[]; showBubble: boolean }) {
  return (
    <>
      {showBubble && (
        <div className="chat-bubble-row chat-bubble-row-user chat-bubble-sending">
          <div className="chat-bubble chat-bubble-user">
            <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', margin: 0 }}>{text}</p>
          </div>
        </div>
      )}
      <div className="chat-pending chat-typing" role="status">
        <span className="chat-typing-dots" aria-hidden="true">
          <span />
          <span />
          <span />
        </span>
        <span>{mentioned.length > 0 ? `Handing this to ${mentioned.join(' and ')}.` : 'Finding the right agent for this.'}</span>
      </div>
    </>
  )
}
