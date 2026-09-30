import { useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ChatSidebar } from '../components/chat/ChatSidebar'
import { Composer } from '../components/chat/Composer'
import { JumpToLatest } from '../components/chat/JumpToLatest'
import { MessageList } from '../components/chat/MessageList'
import { ThreadHeader } from '../components/chat/ThreadHeader'
import { WelcomeScreen } from '../components/chat/WelcomeScreen'
import { WorkPanel } from '../components/chat/WorkPanel'
import { WorkStrip } from '../components/chat/WorkStrip'
import {
  activeGoals,
  autoAnswerTarget,
  composerAnswer,
  conversationText,
  newMessageIds,
  readyAnswers,
  routingMessageForGoal,
} from '../components/chat/chatModel'
import { DetailsContext } from '../components/chat/detailsContext'
import type { DetailsMode } from '../components/chat/detailsContext'
import { useStickToBottom } from '../components/chat/useStickToBottom'
import { useChatShortcuts, chatShortcutGroups } from '../components/chat/useChatShortcuts'
import { ConfirmDialog } from '../components/ui'
import { Sheet } from '../components/ui/Sheet'
import { ShortcutsDialog } from '../components/ui/ShortcutsDialog'
import { ApiError, api, describeApiError } from '../lib/api'
import { useReducedMotion } from '../hooks/useReducedMotion'
import {
  isGoalActive,
  useAgentNames,
  useAgents,
  useAnswerFromDocuments,
  useAnswerQuestion,
  useConversation,
  useConversationList,
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
import type { Agent, BoardGoal, ChatMessage, RunQuestion } from '../lib/queries'
import { usePersistentState } from '../lib/persist'
import { useDocumentTitle, useRouter } from '../lib/router'
import { can, profile } from '../lib/session'
import { useToast } from '../lib/toast'
import { useNow } from '../lib/useNow'
import { useSpeaker } from '../lib/voice'

/*
 * Talk to the whole workforce (B1). The shell holds selection, the sidebar and work-panel modes,
 * the reply target (chosen or automatic, B1.7), the details mode, dismissed auto-answer ids,
 * announcements and shortcuts; every card and list below it is a small, mostly stateless component
 * in components/chat/.
 */

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

const isSidebarMode = (value: unknown): value is 'open' | 'rail' => value === 'open' || value === 'rail'
const isPanelMode = (value: unknown): value is 'open' | 'closed' => value === 'open' || value === 'closed'
const isDetailsMode = (value: unknown): value is DetailsMode => value === 'auto' || value === 'expanded' || value === 'collapsed'

export function Chat() {
  const { search, navigate } = useRouter()
  const toast = useToast()
  const client = useQueryClient()
  const reduceMotion = useReducedMotion()
  const speaker = useSpeaker()
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
  const [focusSection, setFocusSection] = useState<'search' | 'needs-you' | null>(null)

  const [sending, setSending] = useState(false)
  const [pending, setPending] = useState<{ text: string; mentioned: string[] } | null>(null)
  const [reroutingId, setReroutingId] = useState<string | null>(null)
  const [answeringMessageId, setAnsweringMessageId] = useState<string | null>(null)
  const [announcement, setAnnouncement] = useState('')
  const [replyChoice, setReplyChoice] = useState<{ questionId: string; agentName: string } | null>(null)
  const [dismissedIds, setDismissedIds] = useState<ReadonlySet<string>>(new Set())
  const [prefill, setPrefill] = useState<{ token: number; text: string } | null>(null)
  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  const inputRef = useRef<HTMLTextAreaElement | null>(null)
  const scrollRef = useRef<HTMLDivElement | null>(null)
  const shellRef = useRef<HTMLDivElement | null>(null)
  const searchRef = useRef<HTMLInputElement | null>(null)
  const now = useNow(5_000)

  const conversationQuery = useConversation(selectedId)
  const titleListQuery = useConversationList({})
  const agentsQuery = useAgents()
  const agentNames = useAgentNames()
  const memberNames = useMemberNames()
  const activeAgents = useMemo(() => (agentsQuery.data ?? []).filter((agent) => agent.status === 'active'), [agentsQuery.data])

  const createConversation = useCreateConversation()
  const sendMessage = useSendMessage(selectedId ?? '')
  const reroute = useReroute(selectedId ?? '')
  const rename = useRenameConversation()
  const pinConversation = usePinConversation()
  const deleteConversation = useDeleteConversation()
  const answerQuestion = useAnswerQuestion()
  const stopGoal = useStopChatGoal(selectedId ?? '')
  const retryGoal = useRetryChatGoal(selectedId ?? '')
  const answerFromDocuments = useAnswerFromDocuments(selectedId ?? '')
  const markRead = useMarkConversationRead(selectedId ?? '')

  const detail = conversationQuery.data
  const conversation = detail?.conversation ?? null
  const messages = useMemo(() => detail?.messages ?? [], [detail])
  const goals = useMemo(() => detail?.goals ?? [], [detail])

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
    ? { questionId: replyChoice.questionId, agentName: replyChoice.agentName, auto: false }
    : autoTarget
      ? { questionId: autoTarget.id, agentName: nameOf(autoTarget.agentId), auto: true }
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
  const stick = useStickToBottom(scrollRef, { itemCount: messages.length, resetKey: selectedId, reducedMotion: reduceMotion })

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

  /* ---- Deep link: #m-, #question- or ?goal= on first load ------------------------------------ */
  const { hash } = useRouter()
  useEffect(() => {
    if (!detail) return
    let id: string | null = null
    if (hash.startsWith('#m-') || hash.startsWith('#question-')) id = hash.slice(1)
    else if (goalDeepLink) id = routingMessageForGoal(goalDeepLink, messages)?.id ? `m-${routingMessageForGoal(goalDeepLink, messages)!.id}` : null
    if (!id) return
    const el = document.getElementById(id)
    if (el) {
      el.scrollIntoView({ block: 'center' })
      const article = el.matches('article') ? el : el.querySelector('article')
      article?.focus()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detail !== undefined])

  function selectConversation(id: string | null) {
    navigate(id ? `/chat?c=${id}` : '/chat')
    setOverlayOpen(false)
    setMobileSidebarOpen(false)
  }

  async function handleSend(text: string, agentIds: string[]): Promise<boolean> {
    if (replyTo) {
      const question = questions.find((q) => q.id === replyTo.questionId)
      if (!question) {
        toast.error('This question is no longer open.')
        return false
      }
      const payload = composerAnswer(question, text)
      try {
        await answerQuestion.mutateAsync({ id: question.id, answers: payload.answers, note: payload.note ?? null, via: 'chat' })
        setReplyChoice(null)
        setAnnouncement(`Answer sent. ${nameOf(question.agentId)} is continuing.`)
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
    setPending({ text, mentioned: agentIds.map((id) => agentNames[id]?.name).filter((n): n is string => Boolean(n)) })
    try {
      const agentIdsField = agentIds.length > 0 ? { agentIds } : {}
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
      toast.error(describeApiError(error))
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
    setReplyChoice({ questionId, agentName: nameOf(question.agentId) })
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
    el.scrollIntoView({ block: 'center' })
    const article = el.matches('article') ? el : el.querySelector('article')
    ;(article as HTMLElement | null)?.focus?.()
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

  function handleEscape() {
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

  const conversationOrder = useMemo(() => {
    const pages = titleListQuery.data?.pages ?? []
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
  }, [titleListQuery.data])

  function stepConversation(step: 1 | -1) {
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

  const needsYouCount = titleListQuery.data?.pages[titleListQuery.data.pages.length - 1]?.needsYou.length ?? 0
  const unreadCount = useMemo(() => {
    const seen = new Set<string>()
    let count = 0
    for (const page of titleListQuery.data?.pages ?? []) {
      for (const row of page.conversations) {
        if (row.unread && !seen.has(row.id)) {
          seen.add(row.id)
          count += 1
        }
      }
    }
    return count
  }, [titleListQuery.data])
  const titleCount = needsYouCount + unreadCount
  useDocumentTitle(titleCount > 0 ? `(${titleCount}) Chat` : 'Chat')

  const workGoals = activeGoals(goals)
  const showWorkPanel = isWide && panelMode === 'open'

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
      onSelect={selectConversation}
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
        messages={messages}
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

      <DetailsContext.Provider value={{ mode: detailsMode, version: detailsVersion }}>
        <main className="chat-main">
          <ThreadHeader
            eyebrow="Chat"
            title={conversation?.title || 'New conversation'}
            conversation={conversation}
            participants={participants}
            readOnly={!canCreateWork}
            onOpenSidebar={() => setMobileSidebarOpen(true)}
            onNewConversation={() => selectConversation(null)}
            onRename={handleRename}
            onTogglePin={() => void handleTogglePin()}
            onCopyLink={() => void navigator.clipboard?.writeText(`${window.location.origin}/chat?c=${selectedId ?? ''}`)}
            onCopyConversation={() => void navigator.clipboard?.writeText(conversationText(messages, agentNames))}
            onDelete={() => {
              setDeleteError(null)
              setDeleteConfirmOpen(true)
            }}
            speaker={speaker}
            detailsMode={detailsMode}
            onDetailsMode={(mode) => {
              setDetailsMode(mode)
              setDetailsVersion((v) => v + 1)
            }}
            onShowShortcuts={() => setShortcutsOpen(true)}
            {...(isWide ? { workPanelOpen: showWorkPanel, onToggleWorkPanel: () => setPanelMode(showWorkPanel ? 'closed' : 'open') } : {})}
          />

          <div className="chat-scroll" role="region" aria-label="Messages" tabIndex={0} ref={scrollRef} data-jump-visible={stick.farFromBottom || stick.newCount > 0 || undefined}>
            <div className="chat-column">
              {!selectedId && pending ? (
                <PendingMessage text={pending.text} mentioned={pending.mentioned} />
              ) : !selectedId ? (
                <WelcomeScreen agents={agentsQuery.data} can={can} onPick={handleEditAndResend} />
              ) : conversationQuery.isLoading ? (
                <p className="caption muted" role="status" aria-busy="true">
                  Loading this conversation…
                </p>
              ) : messages.length === 0 && !pending ? (
                <WelcomeScreen agents={agentsQuery.data} can={can} onPick={handleEditAndResend} />
              ) : (
                <>
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
                    onReroute={(messageId, agentId) => void handleReroute(messageId, agentId)}
                    onAskAgain={handleAskAgain}
                    onEditAndResend={handleEditAndResend}
                    onReplyInOwnWords={handleReplyInOwnWords}
                    onStop={(goalId) => void handleStop(goalId)}
                    onRetry={(goalId) => void handleRetry(goalId)}
                    onAnswerFromDocuments={(messageId) => void handleAnswerFromDocuments(messageId)}
                    conversationId={selectedId}
                    agentsForReroute={activeAgents}
                    answeringMessageId={answeringMessageId}
                    composerTargetQuestionId={replyTo?.questionId ?? null}
                    now={new Date(now)}
                  />
                  {pending && <PendingMessage text={pending.text} mentioned={pending.mentioned} />}
                </>
              )}
            </div>
          </div>

          <div className="chat-dock">
            <JumpToLatest visible={stick.farFromBottom || stick.newCount > 0} newCount={stick.newCount} onJump={stick.jump} />
            {!showWorkPanel && (
              <WorkStrip
                goals={workGoals}
                questions={questions}
                agentNames={agentNames}
                me={me}
                compact={!isTablet}
                onStop={(goalId) => void handleStop(goalId)}
                onGoTo={handleGoTo}
              />
            )}
            <Composer
              conversationId={selectedId}
              agents={activeAgents}
              onSend={handleSend}
              sending={sending}
              replyTo={replyTo}
              onClearReply={handleClearReply}
              lastUserText={lastUserText}
              inputRef={inputRef}
              {...(prefill ? { prefill } : {})}
              {...(!canCreateWork
                ? { readOnlyNote: 'Your role can ask document questions here, but cannot start agents on new work.' }
                : {})}
            />
          </div>
        </main>

        {showWorkPanel && (
          <WorkPanel
            goals={workGoals}
            questions={questions}
            participants={participants}
            agentNames={agentNames}
            me={me}
            onStop={(goalId) => void handleStop(goalId)}
            onGoTo={handleGoTo}
            onClose={() => setPanelMode('closed')}
          />
        )}
      </DetailsContext.Provider>

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
        <span className="chat-pulse-soft" aria-hidden="true" />
        <span>{mentioned.length > 0 ? `Handing this to ${mentioned.join(' and ')}.` : 'Finding the right agent for this.'}</span>
      </div>
    </>
  )
}
