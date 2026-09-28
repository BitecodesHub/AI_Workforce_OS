import { useId, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { Button, IconButton, Tag } from '../ui'
import { CATEGORY_LABEL } from '../../lib/labels'
import { extractMentions, findMentionQuery, matchAgents } from '../../lib/mentions'
import type { Agent } from '../../lib/queries'
import { useVoiceInput } from '../../lib/voice'

/*
 * Where a message is written: the textarea itself, its @mention popover, the chips for agents
 * chosen that way, and the mic and send buttons either side of it.
 *
 * A mention chosen from the popover is lifted out of the text entirely (into a chip, sent as
 * `agentIds`) rather than left as an inline "@key"; a mention typed by hand and never chosen from
 * the popover is still found at send time with lib/mentions' own `extractMentions`, so either way
 * of addressing an agent reaches the coordinator the same way.
 */

const MAX_ROWS = 6

function autosize(el: HTMLTextAreaElement) {
  el.style.height = 'auto'
  const style = window.getComputedStyle(el)
  const lineHeight = parseFloat(style.lineHeight) || 20
  const padding = (parseFloat(style.paddingTop) || 0) + (parseFloat(style.paddingBottom) || 0)
  const maxHeight = lineHeight * MAX_ROWS + padding
  const next = Math.min(el.scrollHeight, maxHeight)
  el.style.height = `${next}px`
  el.style.overflowY = el.scrollHeight > maxHeight ? 'auto' : 'hidden'
}

type Popover = { query: string; start: number; caret: number; activeIndex: number }

export function Composer({
  agents,
  onSend,
  sending,
}: {
  agents: Agent[]
  onSend: (text: string, agentIds: string[]) => void
  sending: boolean
}) {
  const [text, setText] = useState('')
  const [mentioned, setMentioned] = useState<Agent[]>([])
  const [popover, setPopover] = useState<Popover | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)
  const fieldId = useId()
  const listboxId = useId()

  const voice = useVoiceInput({
    onText: (heard) => {
      setText((current) => {
        const next = current.trim() ? `${current.trim()} ${heard}` : heard
        requestAnimationFrame(() => {
          if (textareaRef.current) autosize(textareaRef.current)
        })
        return next
      })
    },
  })

  const matches = popover ? matchAgents(popover.query, agents).slice(0, 6) : []
  // Clamped at render time rather than in an effect: the query can narrow the match list on the
  // same keystroke that moved the caret, and the index must never point past its new end.
  const activeIndex = popover ? Math.min(popover.activeIndex, Math.max(0, matches.length - 1)) : 0

  function chooseMention(agent: Agent, at: Popover) {
    const before = text.slice(0, at.start)
    const after = text.slice(at.caret)
    setText(before + after)
    setMentioned((current) => (current.some((existing) => existing.id === agent.id) ? current : [...current, agent]))
    setPopover(null)
    requestAnimationFrame(() => {
      const el = textareaRef.current
      if (!el) return
      el.focus()
      el.selectionStart = el.selectionEnd = before.length
      autosize(el)
    })
  }

  function handleChange(value: string, caret: number) {
    setText(value)
    if (textareaRef.current) autosize(textareaRef.current)
    const found = findMentionQuery(value, caret)
    setPopover(found ? { query: found.query, start: found.start, caret, activeIndex: 0 } : null)
  }

  function submit() {
    const trimmed = text.trim()
    if (!trimmed || sending) return
    const extra = extractMentions(trimmed, agents)
    const ids = Array.from(new Set([...mentioned.map((agent) => agent.id), ...extra]))
    onSend(trimmed, ids)
    setText('')
    setMentioned([])
    setPopover(null)
    requestAnimationFrame(() => {
      if (textareaRef.current) {
        textareaRef.current.style.height = 'auto'
        textareaRef.current.style.overflowY = 'hidden'
      }
    })
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
    // Enter sends, Shift+Enter starts a new line. An Enter that confirms an IME composition
    // belongs to the composition, not to the form.
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault()
      submit()
    }
  }

  const micLabel = voice.listening ? 'Stop listening' : 'Speak your message'
  const micTitle = voice.listening
    ? voice.provider === 'elevenlabs'
      ? 'Listening, using ElevenLabs'
      : "Listening, using your browser's speech recognition"
    : micLabel

  return (
    <div className="chat-composer">
      {voice.error && (
        <p className="caption chat-composer-voice-error" role="alert">
          {voice.error}
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

      <div className="chat-composer-field">
        <label htmlFor={fieldId} className="visually-hidden">
          Message the workforce
        </label>
        {popover && (
          <ul className="chat-mention-popover" role="listbox" id={listboxId} aria-label="Mention an agent">
            {matches.length === 0 ? (
              <li className="chat-mention-empty caption muted">No agent matches "{popover.query}"</li>
            ) : (
              matches.map((agent, index) => (
                <li key={agent.id}>
                  <button
                    type="button"
                    role="option"
                    aria-selected={index === activeIndex}
                    className="chat-mention-option"
                    data-active={index === activeIndex || undefined}
                    onMouseDown={(event) => {
                      // mousedown, not click: it must fire before the textarea's blur.
                      event.preventDefault()
                      chooseMention(agent, popover)
                    }}
                  >
                    <span>{agent.name}</span>
                    <Tag tone="neutral">{CATEGORY_LABEL[agent.category] ?? agent.category}</Tag>
                  </button>
                </li>
              ))
            )}
          </ul>
        )}
        <textarea
          id={fieldId}
          ref={textareaRef}
          className="input textarea chat-composer-input"
          value={text}
          onChange={(event) => handleChange(event.target.value, event.target.selectionStart ?? event.target.value.length)}
          onKeyDown={handleKeyDown}
          placeholder="Ask for work, or type @ to address one agent directly"
          rows={1}
          role="combobox"
          aria-expanded={popover !== null}
          aria-controls={popover ? listboxId : undefined}
          aria-haspopup="listbox"
          disabled={sending}
        />
        <div className="chat-composer-actions">
          {voice.supported && (
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
          <Button onClick={submit} loading={sending} disabled={text.trim().length === 0}>
            Send
          </Button>
        </div>
      </div>
      <p className="caption chat-composer-hint">Enter to send, Shift+Enter for a new line. Type @ to address an agent.</p>
    </div>
  )
}
