// @find: message box, composer, write message, send message, mention agent, @mention, draft, attach files, paste image, answer question, Send button, chat input, POST message
// @what: Where a chat message is written: text box, @mention popover, attachments, saved draft and send.
// @flow: Used by the Chat page; uses useAttachments, useDropAndPaste, AttachButton.
import { useEffect, useId, useRef, useState } from 'react'
import type { DragEvent as ReactDragEvent, KeyboardEvent, RefObject } from 'react'
import { createPortal } from 'react-dom'
import { IconButton, Spinner, Tag } from '../ui'
import { AgentAvatar } from '../ui/AgentAvatar'
import { MenuButton } from '../ui/Menu'
import { nameList } from '../../lib/format'
import { CATEGORY_LABEL } from '../../lib/labels'
import { extractMentions, findMentionQuery, matchAgents } from '../../lib/mentions'
import { readStored, writeStored } from '../../lib/persist'
import type { Agent } from '../../lib/queries'
import { useVoiceInput } from '../../lib/voice'
import { draftKey as scopedDraftKey, removeLegacyDrafts, sendsAsNewRequest } from './chatModel'
import { AttachButton } from './AttachButton'
import { AttachmentChips, AttachmentDropOverlay } from './AttachmentChips'
import { useAttachments } from './useAttachments'
import { filesFromPaste, useFileDrop } from './useDropAndPaste'

/*
 * Where a message is written (B1.7): the textarea itself, its @mention popover, the chips for
 * agents chosen that way, and the @, mic and round send buttons beneath it, all in one rounded box. It also answers the
 * conversation's open question when one is targeted (D-14): Chat.tsx decides which question that
 * is and hands it down as `replyTo`, and this file only changes its placeholder, its send label
 * and what Esc does.
 *
 * A mention chosen from the popover is lifted out of the text entirely (into a chip, sent as
 * `agentIds`) rather than left as an inline "@key"; a mention typed by hand and never chosen from
 * the popover is still found at send time with lib/mentions' own `extractMentions`, so either way
 * of addressing an agent reaches the coordinator the same way.
 *
 * While a question is answered automatically, mentioning some other agent turns the message back
 * into a new request (chatModel's sendsAsNewRequest, which Chat.tsx applies on send), and the
 * caption and the send button say so before it goes.
 *
 * The unsent draft is kept per person and per conversation (`chat.draft.<user>.<conversation>`),
 * so somebody signing in after someone else on the same browser never sees their text.
 *
 * Files attach three ways - the paperclip, a drop anywhere on `dropZoneRef` (the chat panel), or a
 * paste into the box - and upload at once, shown as chips above the text (useAttachments). A
 * message may be files alone. Sending waits for uploads to finish; files that failed are never sent.
 * Answering a question takes text only, so attaching is off while the box answers one.
 */

const COUNT_WARNING_AT = 8_000
const COUNT_MAX = 10_000

function autosize(el: HTMLTextAreaElement) {
  el.style.height = 'auto'
  const maxHeight = parseFloat(window.getComputedStyle(el).maxHeight) || 240
  const next = Math.min(el.scrollHeight, maxHeight)
  el.style.height = `${next}px`
  el.style.overflowY = el.scrollHeight > maxHeight ? 'auto' : 'hidden'
}

function sortGeneralFirst<T extends { fallback?: boolean }>(list: readonly T[]): T[] {
  const index = list.findIndex((item) => item.fallback)
  if (index <= 0) return [...list]
  const copy = [...list]
  const [general] = copy.splice(index, 1)
  copy.unshift(general!)
  return copy
}

/** Drafts kept before they were per person, removed once per page load. */
let legacyDraftsCleared = false

function clearLegacyDrafts() {
  if (legacyDraftsCleared) return
  legacyDraftsCleared = true
  try {
    removeLegacyDrafts(localStorage)
  } catch {
    // Storage blocked or unavailable: there is nothing to clear.
  }
}

type Popover = { query: string; start: number; caret: number; activeIndex: number }
/** The question the composer answers: `agentId` is the agent that asked it. */
type ReplyTarget = { questionId: string; agentName: string; agentId: string | null; auto: boolean }

// @find: Composer, composer, message box, composer, write message, send message
export function Composer({
  userId,
  conversationId,
  agents,
  onSend,
  sending,
  replyTo,
  onClearReply,
  lastUserText,
  inputRef,
  readOnlyNote,
  disabled = false,
  prefill,
  mention,
  dropZoneRef,
}: {
  /** The person writing, whose drafts these are. */
  userId: string | null
  conversationId: string | null
  agents: Agent[]
  onSend: (text: string, agentIds: string[], attachmentIds: string[]) => Promise<boolean>
  sending: boolean
  replyTo: ReplyTarget | null
  onClearReply: () => void
  lastUserText: string | null
  inputRef: RefObject<HTMLTextAreaElement | null>
  readOnlyNote?: string
  /** Nothing can be sent here (a conversation that could not be opened); `readOnlyNote` says why. */
  disabled?: boolean
  /** "Edit and send again" (B1.5): a new token puts `text` into the box, replacing the draft. */
  prefill?: { token: number; text: string } | null
  /** An agent chosen outside the composer (the welcome screen): a new token adds it as a mention. */
  mention?: { token: number; agent: Agent } | null
  /** Where dropped files are caught (the chat panel); the composer itself when not given. */
  dropZoneRef?: RefObject<HTMLElement | null>
}) {
  const draftKey = scopedDraftKey(userId ?? 'anonymous', conversationId)
  const [text, setText] = useState(() => readStored(draftKey, '', (v): v is string => typeof v === 'string'))
  const [mentioned, setMentioned] = useState<Agent[]>([])
  const [popover, setPopover] = useState<Popover | null>(null)
  const [seenDraftKey, setSeenDraftKey] = useState(draftKey)
  const [seenPrefillToken, setSeenPrefillToken] = useState(prefill?.token ?? null)
  const [seenMentionToken, setSeenMentionToken] = useState(mention?.token ?? null)
  // A fixed id: the page's "Skip to message box" link targets it, and there is one composer per page.
  const fieldId = 'chat-composer-input'
  const listboxId = useId()
  const rootRef = useRef<HTMLDivElement | null>(null)
  const attachments = useAttachments(conversationId)

  // The draft restores when the conversation (or the person) changes, adjusted during render (the
  // "previous value" pattern, 0.2) rather than in an effect, so switching threads never shows the
  // previous one's half-typed text even for a single frame.
  if (seenDraftKey !== draftKey) {
    setSeenDraftKey(draftKey)
    setText(readStored(draftKey, '', (v): v is string => typeof v === 'string'))
    setMentioned([])
    setPopover(null)
  }

  // "Edit and send again" (B1.5): the same render-time pattern puts the text into the box.
  if (prefill && prefill.token !== seenPrefillToken) {
    setSeenPrefillToken(prefill.token)
    setText(prefill.text)
    setMentioned([])
    setPopover(null)
  }

  // A mention chosen on the welcome screen, by the same render-time pattern.
  if (mention && mention.token !== seenMentionToken) {
    setSeenMentionToken(mention.token)
    setMentioned((current) => (current.some((existing) => existing.id === mention.agent.id) ? current : [...current, mention.agent]))
  }

  useEffect(() => {
    clearLegacyDrafts()
  }, [])

  useEffect(() => {
    if (disabled) return
    const timer = window.setTimeout(() => writeStored(draftKey, text), 300)
    return () => window.clearTimeout(timer)
  }, [text, draftKey, disabled])

  useEffect(() => {
    if (inputRef.current) autosize(inputRef.current)
  }, [inputRef, text])

  const voice = useVoiceInput({
    onText: (heard) => {
      setText((current) => (current.trim() ? `${current.trim()} ${heard}` : heard))
    },
  })

  const orderedAgents = sortGeneralFirst(agents)
  const matches = popover ? sortGeneralFirst(matchAgents(popover.query, agents)).slice(0, 6) : []
  // Clamped at render time rather than in an effect: the query can narrow the match list on the
  // same keystroke that moved the caret, and the index must never point past its new end.
  const activeIndex = popover ? Math.min(popover.activeIndex, Math.max(0, matches.length - 1)) : 0
  const activeOptionId = popover && matches.length > 0 ? `${listboxId}-option-${matches[activeIndex]!.id}` : undefined

  const trimmedLength = text.length
  const overLimit = trimmedLength > COUNT_MAX
  const hasFiles = attachments.readyIds.length > 0
  const canSend =
    (text.trim().length > 0 || hasFiles) && !attachments.uploading && !sending && !overLimit && !disabled

  // Who this message is addressed to so far: chips, and mentions typed by hand.
  const addressedIds = Array.from(new Set([...mentioned.map((agent) => agent.id), ...extractMentions(text, agents)]))
  const asNewRequest = replyTo ? sendsAsNewRequest(replyTo, addressedIds) : false
  const newRequestNames = asNewRequest
    ? addressedIds
        .filter((id) => id !== replyTo?.agentId)
        .map((id) => agents.find((agent) => agent.id === id)?.name)
        .filter((name): name is string => Boolean(name))
    : []
  const answering = replyTo !== null && !asNewRequest
  const canAttach = !disabled && !answering
  const drop = useFileDrop({ onFiles: attachments.add, disabled: !canAttach })
  const { onDragEnter, onDragOver, onDragLeave, onDrop } = drop.bind
  const [dropZone, setDropZone] = useState<HTMLElement | null>(null)

  // Files dropped anywhere on the chat panel, not only on the box: native listeners on the zone,
  // which is marked so the overlay can cover it.
  useEffect(() => {
    const zone = dropZoneRef?.current ?? rootRef.current
    if (!zone) return
    setDropZone(zone)
    zone.classList.add('chat-attach-dropzone')
    const handlers = {
      dragenter: (event: DragEvent) => onDragEnter(event as unknown as ReactDragEvent),
      dragover: (event: DragEvent) => onDragOver(event as unknown as ReactDragEvent),
      dragleave: (event: DragEvent) => onDragLeave(event as unknown as ReactDragEvent),
      drop: (event: DragEvent) => onDrop(event as unknown as ReactDragEvent),
    }
    for (const [name, handler] of Object.entries(handlers)) zone.addEventListener(name, handler as EventListener)
    return () => {
      for (const [name, handler] of Object.entries(handlers)) zone.removeEventListener(name, handler as EventListener)
      zone.classList.remove('chat-attach-dropzone')
    }
  }, [dropZoneRef, onDragEnter, onDragOver, onDragLeave, onDrop])

  function chooseMention(agent: Agent, at: Popover) {
    const before = text.slice(0, at.start)
    const after = text.slice(at.caret)
    setText(before + after)
    setMentioned((current) => (current.some((existing) => existing.id === agent.id) ? current : [...current, agent]))
    setPopover(null)
    requestAnimationFrame(() => {
      const el = inputRef.current
      if (!el) return
      el.focus()
      el.selectionStart = el.selectionEnd = before.length
      autosize(el)
    })
  }

  function addMentionFromPicker(agent: Agent) {
    setMentioned((current) => (current.some((existing) => existing.id === agent.id) ? current : [...current, agent]))
  }

  function handleChange(value: string, caret: number) {
    setText(value)
    const found = findMentionQuery(value, caret)
    setPopover(found ? { query: found.query, start: found.start, caret, activeIndex: 0 } : null)
  }

  async function submit() {
    const trimmed = text.trim()
    const attachmentIds = answering ? [] : attachments.readyIds
    if ((!trimmed && attachmentIds.length === 0) || attachments.uploading || sending || overLimit || disabled) return
    const extra = extractMentions(trimmed, agents)
    const ids = Array.from(new Set([...mentioned.map((agent) => agent.id), ...extra]))
    // Snapshot, then clear straight away (B1.7): the field must never sit full while the request
    // is in flight, whether it succeeds in a blink or takes a while.
    setText('')
    setMentioned([])
    setPopover(null)
    writeStored(draftKey, '')
    requestAnimationFrame(() => {
      if (inputRef.current) {
        inputRef.current.style.height = 'auto'
        inputRef.current.style.overflowY = 'hidden'
      }
    })
    // Files leave the box with the text. A send that fails is offered again with them by Chat.tsx's
    // "Try again", which keeps their ids; they stay stored for a day either way.
    if (attachmentIds.length > 0) attachments.clear()
    const ok = await onSend(trimmed, ids, attachmentIds)
    if (!ok) {
      // Only restore into an empty box: a person who kept typing after sending must never have
      // their new text overwritten by the failed one.
      setText((current) => (current === '' ? trimmed : current))
      setMentioned((current) => (current.length === 0 ? mentioned : current))
    }
    inputRef.current?.focus()
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (popover) {
      if (event.key === 'ArrowDown' && matches.length > 0) {
        event.preventDefault()
        setPopover({ ...popover, activeIndex: (activeIndex + 1) % matches.length })
        return
      }
      if (event.key === 'ArrowUp' && matches.length > 0) {
        event.preventDefault()
        setPopover({ ...popover, activeIndex: (activeIndex - 1 + matches.length) % matches.length })
        return
      }
      if (event.key === 'Escape') {
        event.preventDefault()
        setPopover(null)
        return
      }
      if ((event.key === 'Enter' || event.key === 'Tab') && matches.length > 0) {
        event.preventDefault()
        chooseMention(matches[activeIndex] ?? matches[0]!, popover)
        return
      }
    }

    if (event.key === 'Escape' && !popover && replyTo && !asNewRequest) {
      event.preventDefault()
      onClearReply()
      return
    }

    if (event.key === 'ArrowUp' && !popover && text === '' && lastUserText) {
      const el = event.currentTarget
      if (el.selectionStart === 0 && el.selectionEnd === 0) {
        event.preventDefault()
        setText(lastUserText)
        requestAnimationFrame(() => el.setSelectionRange(0, lastUserText.length))
      }
      return
    }

    // Enter sends, Shift+Enter starts a new line. An Enter that confirms an IME composition
    // belongs to the composition, not to the form.
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault()
      void submit()
    }
  }

  const micLabel = voice.listening ? 'Stop listening' : 'Speak your message'
  const micTitle = voice.listening
    ? voice.provider === 'elevenlabs'
      ? 'Listening, using ElevenLabs'
      : "Listening, using your browser's speech recognition"
    : micLabel
  const placeholder = answering ? 'Type your answer, or choose an option above' : 'Ask for anything, or type @ to choose an agent'

  return (
    <div className="chat-composer" ref={rootRef}>
      {readOnlyNote && <p className="caption muted">{readOnlyNote}</p>}
      {dropZone && createPortal(<AttachmentDropOverlay visible={drop.dragging} />, dropZone)}

      {replyTo &&
        (asNewRequest ? (
          <p className="caption chat-composer-reply" role="status">
            Will send as a new request to {nameList(newRequestNames.length > 0 ? newRequestNames : ['another agent'])}
          </p>
        ) : (
          <p className="caption chat-composer-reply">
            {replyTo.auto ? `Answering ${replyTo.agentName}'s question` : `Replying to ${replyTo.agentName}'s question`}
            {' · '}
            <button type="button" className="link" onClick={onClearReply}>
              {replyTo.auto ? 'Send as a new request instead' : 'Stop replying to the question'}
            </button>
          </p>
        ))}

      {voice.error && (
        <p className="caption chat-composer-voice-error" role="alert">
          {voice.error}
        </p>
      )}

      {voice.listening && (
        <p className="caption chat-composer-voice-status" role="status">
          Listening{voice.interim ? `: ${voice.interim}` : ''}
        </p>
      )}

      {mentioned.length > 0 && (
        <ul className="chat-mention-chips">
          {mentioned.map((agent) => (
            <li key={agent.id}>
              <span className="chat-mention-chip">
                {agent.name}
                <button
                  type="button"
                  aria-label={`Remove ${agent.name}`}
                  onClick={() => setMentioned((current) => current.filter((existing) => existing.id !== agent.id))}
                >
                  <svg width="9" height="9" viewBox="0 0 9 9" fill="none" aria-hidden="true">
                    <path d="M1 1l7 7M8 1L1 8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
                  </svg>
                </button>
              </span>
            </li>
          ))}
        </ul>
      )}

      <AttachmentChips
        items={attachments.items}
        onRemove={attachments.remove}
        statusMessage={attachments.statusMessage}
        notice={attachments.notice}
      />

      <div className="chat-composer-field">
        <label htmlFor={fieldId} className="visually-hidden">
          Message the workforce
        </label>
        {popover && (
          <ul className="chat-mention-popover" role="listbox" id={listboxId} aria-label="Mention an agent">
            {matches.length === 0 ? (
              <li className="chat-mention-empty caption muted" role="option" aria-disabled="true" aria-selected="false">
                No agent matches "{popover.query}"
              </li>
            ) : (
              matches.map((agent, index) => (
                <li key={agent.id} role="presentation">
                  <button
                    type="button"
                    id={`${listboxId}-option-${agent.id}`}
                    role="option"
                    aria-selected={index === activeIndex}
                    className="chat-mention-option"
                    data-active={index === activeIndex || undefined}
                    tabIndex={-1}
                    onMouseDown={(event) => {
                      event.preventDefault()
                      chooseMention(agent, popover)
                    }}
                  >
                    <span>{agent.name}</span>
                    <Tag tone="neutral">{agent.fallback ? 'Default' : (CATEGORY_LABEL[agent.category] ?? agent.category)}</Tag>
                  </button>
                </li>
              ))
            )}
          </ul>
        )}
        <textarea
          id={fieldId}
          ref={inputRef}
          className="chat-composer-input"
          value={text}
          onChange={(event) => handleChange(event.target.value, event.target.selectionStart ?? event.target.value.length)}
          onKeyDown={handleKeyDown}
          onPaste={(event) => {
            if (!canAttach) return
            const files = filesFromPaste(event)
            if (files.length === 0) return
            event.preventDefault()
            attachments.add(files)
          }}
          placeholder={placeholder}
          disabled={disabled}
          rows={1}
          aria-autocomplete="list"
          aria-controls={popover ? listboxId : undefined}
          aria-activedescendant={activeOptionId}
        />
        <div className="chat-composer-footer row">
          <div className="chat-composer-tools">
            {!disabled && <AttachButton onFiles={attachments.add} disabled={!canAttach} />}
            {!disabled && (
              <MenuButton
                className="chat-agent-picker"
                label="Choose an agent"
                trigger="icon"
                icon={<span aria-hidden="true">@</span>}
                items={orderedAgents.map((agent) => ({
                  id: agent.id,
                  label: agent.name,
                  icon: <AgentAvatar name={agent.name} category={agent.category} fallback={agent.fallback ?? false} size="sm" />,
                  note: agent.fallback ? 'Default' : (CATEGORY_LABEL[agent.category] ?? agent.category),
                  onSelect: () => addMentionFromPicker(agent),
                }))}
              />
            )}
            {voice.supported && !disabled && (
              <IconButton
                label={micLabel}
                title={micTitle}
                aria-pressed={voice.listening}
                data-active={voice.listening || undefined}
                onClick={() => (voice.listening ? voice.stop() : void voice.start())}
              >
                <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                  <rect x="5.5" y="1.5" width="5" height="8" rx="2.5" stroke="currentColor" strokeWidth="1.3" />
                  <path d="M3 7.5a5 5 0 0 0 10 0M8 12.5v2" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
                </svg>
              </IconButton>
            )}
          </div>
          <div className="chat-composer-count">
            {trimmedLength >= COUNT_WARNING_AT && (
              <span className={`caption tabular${overLimit ? ' chat-composer-count-warning' : ''}`}>
                {overLimit
                  ? 'Shorten this to 10,000 characters to send it'
                  : `${trimmedLength.toLocaleString('en-AU')} of ${COUNT_MAX.toLocaleString('en-AU')}`}
              </span>
            )}
            <button
              type="button"
              className="chat-send"
              aria-label={answering ? 'Send answer' : 'Send'}
              title={answering ? 'Send answer' : 'Send'}
              aria-busy={sending || undefined}
              disabled={!canSend}
              onClick={() => void submit()}
            >
              {sending ? (
                <Spinner size={15} />
              ) : (
                <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                  <path d="M8 13V3M3.5 7.5 8 3l4.5 4.5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              )}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
