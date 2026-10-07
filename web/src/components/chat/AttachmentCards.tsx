import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { ApiError } from '../../lib/api'
import {
  downloadAttachment,
  fetchAttachmentBlob,
  formatBytes,
  formatPages,
  isViewableImage,
  openAttachment,
  saveAttachmentToKnowledge,
  type SentAttachment,
} from '../../lib/attachments'
import { can } from '../../lib/session'
import { Button, Spinner } from '../ui'
import { AttachmentIcon } from './AttachmentIcon'
import './attachments.css'

/*
 * The files a sent message carried, shown with it. A picture shows as a thumbnail that opens
 * larger in a lightbox; any other file opens in a new tab (PDF, text) or downloads (Office files).
 * Someone who may manage knowledge can also keep a file there, for agents to find in later chats.
 */

function reasonOf(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Try again.'
}

/** An image's bytes as an object URL, freed on unmount; 'failed' when they could not be fetched. */
function useImageUrl(id: string, enabled: boolean): string | 'failed' | null {
  const [state, setState] = useState<{ id: string; url: string | 'failed' } | null>(null)
  useEffect(() => {
    if (!enabled) return
    const controller = new AbortController()
    let url: string | null = null
    fetchAttachmentBlob(id, controller.signal).then(
      (blob) => {
        if (controller.signal.aborted) return
        url = URL.createObjectURL(blob)
        setState({ id, url })
      },
      () => {
        if (!controller.signal.aborted) setState({ id, url: 'failed' })
      },
    )
    return () => {
      controller.abort()
      if (url) URL.revokeObjectURL(url)
    }
  }, [id, enabled])
  return state && state.id === id ? state.url : null
}

const FOCUSABLE = 'button:not([disabled]), [href], [tabindex]:not([tabindex="-1"])'

function Lightbox({ attachment, url, onClose }: { attachment: SentAttachment; url: string; onClose: () => void }) {
  const panelRef = useRef<HTMLDivElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  const [error, setError] = useState<string | null>(null)
  const [downloading, setDownloading] = useState(false)
  const onCloseRef = useRef(onClose)
  // Taken at first render, before the close button takes focus (StrictMode runs effects twice).
  const [returnTo] = useState(() => (document.activeElement instanceof HTMLElement ? document.activeElement : null))

  useEffect(() => {
    onCloseRef.current = onClose
  }, [onClose])

  useEffect(() => {
    closeRef.current?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        onCloseRef.current()
        return
      }
      if (event.key !== 'Tab' || !panelRef.current) return
      const focusable = Array.from(panelRef.current.querySelectorAll<HTMLElement>(FOCUSABLE))
      if (focusable.length === 0) return
      const first = focusable[0]!
      const last = focusable[focusable.length - 1]!
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      returnTo?.focus()
    }
  }, [returnTo])

  async function download() {
    setDownloading(true)
    setError(null)
    try {
      await downloadAttachment(attachment)
    } catch (err) {
      setError(reasonOf(err))
    } finally {
      setDownloading(false)
    }
  }

  return createPortal(
    <div
      className="chat-lightbox"
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <div ref={panelRef} className="chat-lightbox-panel" role="dialog" aria-modal="true" aria-label={attachment.name}>
        <div className="chat-lightbox-bar">
          <span className="chat-lightbox-name" title={attachment.name}>
            {attachment.name}
          </span>
          <Button variant="outline" className="button-sm" loading={downloading} onClick={() => void download()}>
            Download
          </Button>
          <button ref={closeRef} type="button" className="icon-button" aria-label="Close" title="Close" onClick={onClose}>
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
              <path d="M2 2l10 10M12 2L2 12" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            </svg>
          </button>
        </div>
        {error && (
          <p className="caption chat-attach-error" role="alert">
            {error}
          </p>
        )}
        <img className="chat-lightbox-image" src={url} alt={attachment.name} />
      </div>
    </div>,
    document.body,
  )
}

type SaveState = { phase: 'idle' } | { phase: 'saving' } | { phase: 'saved' } | { phase: 'error'; message: string }

function SaveToKnowledge({ id }: { id: string }) {
  const [state, setState] = useState<SaveState>({ phase: 'idle' })
  if (state.phase === 'saved') return <span className="caption chat-attach-saved">Saved to knowledge</span>
  return (
    <span className="chat-attach-save">
      <button
        type="button"
        className="link caption"
        disabled={state.phase === 'saving'}
        aria-busy={state.phase === 'saving' || undefined}
        onClick={() => {
          setState({ phase: 'saving' })
          saveAttachmentToKnowledge(id).then(
            () => setState({ phase: 'saved' }),
            (error: unknown) =>
              setState({
                phase: 'error',
                message:
                  error instanceof ApiError && error.isPermissionDenied
                    ? 'You do not have permission to add files to knowledge.'
                    : reasonOf(error),
              }),
          )
        }}
      >
        {state.phase === 'saving' ? 'Saving' : 'Save to knowledge'}
      </button>
      {state.phase === 'error' && (
        <span className="caption chat-attach-error" role="alert">
          {state.message}
        </span>
      )}
    </span>
  )
}

function AttachmentCard({ attachment, canSave }: { attachment: SentAttachment; canSave: boolean }) {
  const image = attachment.kind === 'image' && isViewableImage(attachment)
  const thumb = useImageUrl(attachment.id, image)
  const [lightbox, setLightbox] = useState(false)
  const [opening, setOpening] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const meta = [formatBytes(attachment.size), formatPages(attachment.pageCount)].filter(Boolean).join(' · ')
  const thumbUrl = thumb && thumb !== 'failed' ? thumb : null

  async function open() {
    if (thumbUrl) {
      setLightbox(true)
      return
    }
    setOpening(true)
    setError(null)
    try {
      await openAttachment(attachment)
    } catch (err) {
      setError(reasonOf(err))
    } finally {
      setOpening(false)
    }
  }

  return (
    <li className="chat-attach-card" data-kind={attachment.kind}>
      <button
        type="button"
        className="chat-attach-card-main"
        aria-label={`Open ${attachment.name}`}
        aria-busy={opening || undefined}
        onClick={() => void open()}
      >
        {image ? (
          thumbUrl ? (
            <img className="chat-attach-card-thumb" src={thumbUrl} alt="" />
          ) : (
            <span className="chat-attach-card-thumb chat-attach-card-placeholder" data-failed={thumb === 'failed' || undefined}>
              {thumb === null ? <Spinner size={14} /> : <AttachmentIcon kind="image" />}
            </span>
          )
        ) : (
          <AttachmentIcon kind={attachment.kind} />
        )}
        <span className="chat-attach-chip-text">
          <span className="chat-attach-name" title={attachment.name}>
            {attachment.name}
          </span>
          {meta && <span className="chat-attach-meta">{meta}</span>}
        </span>
        {opening && <Spinner size={13} />}
      </button>
      {error && (
        <span className="caption chat-attach-error" role="alert">
          {error}
        </span>
      )}
      {canSave && <SaveToKnowledge id={attachment.id} />}
      {lightbox && thumbUrl && <Lightbox attachment={attachment} url={thumbUrl} onClose={() => setLightbox(false)} />}
    </li>
  )
}

export function AttachmentCards({ attachments, align = 'end' }: { attachments: SentAttachment[]; align?: 'end' | 'start' }) {
  if (attachments.length === 0) return null
  const canSave = can('knowledge:source_manage')
  return (
    <ul className="chat-attach-cards" data-align={align} role="list" aria-label="Attached files">
      {attachments.map((attachment) => (
        <AttachmentCard key={attachment.id} attachment={attachment} canSave={canSave} />
      ))}
    </ul>
  )
}
