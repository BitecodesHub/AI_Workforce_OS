import { formatBytes } from '../../lib/attachments'
import { AttachmentIcon } from './AttachmentIcon'
import type { DraftAttachment } from './useAttachments'
import './attachments.css'

/*
 * The files attached to the message being written, shown above the text box: a thumbnail or type
 * glyph, the name, the size, a bar while it uploads, and the reason if it could not be attached.
 * The live line beneath is read out by a screen reader as each file uploads, attaches or fails.
 */

export function AttachmentChips({
  items,
  onRemove,
  statusMessage,
  notice,
}: {
  items: DraftAttachment[]
  onRemove: (localId: string) => void
  /** From useAttachments: the latest thing that happened, announced politely. */
  statusMessage: string
  /** From useAttachments: why some files were not added, shown as a caption. */
  notice?: string | null
}) {
  return (
    <>
      {items.length > 0 && (
        <ul className="chat-attach-chips" role="list" aria-label="Attached files">
          {items.map((item) => (
            <li key={item.localId} className="chat-attach-chip" data-status={item.status}>
              {item.previewUrl ? (
                <img className="chat-attach-thumb" src={item.previewUrl} alt="" />
              ) : (
                <AttachmentIcon kind={item.kind} />
              )}
              <span className="chat-attach-chip-text">
                <span className="chat-attach-name" title={item.name}>
                  {item.name}
                </span>
                {item.status === 'error' ? (
                  <span className="chat-attach-error">{item.error ?? 'This file could not be attached.'}</span>
                ) : (
                  <span className="chat-attach-meta">
                    {item.status === 'uploading' ? `Uploading, ${formatBytes(item.size)}` : formatBytes(item.size)}
                  </span>
                )}
                {item.status === 'ready' && item.notice && <span className="chat-attach-meta">{item.notice}</span>}
                {item.status === 'uploading' && (
                  <span
                    className="chat-attach-progress"
                    role="progressbar"
                    aria-label={`Uploading ${item.name}`}
                    aria-valuemin={0}
                    aria-valuemax={100}
                    aria-valuenow={Math.round(item.progress * 100)}
                  >
                    <span className="chat-attach-progress-fill" style={{ transform: `scaleX(${item.progress})` }} />
                  </span>
                )}
              </span>
              <button
                type="button"
                className="chat-attach-remove"
                aria-label={`Remove ${item.name}`}
                title={`Remove ${item.name}`}
                onClick={() => onRemove(item.localId)}
              >
                <svg width="9" height="9" viewBox="0 0 9 9" fill="none" aria-hidden="true">
                  <path d="M1 1l7 7M8 1L1 8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
                </svg>
              </button>
            </li>
          ))}
        </ul>
      )}
      {notice && <p className="caption chat-attach-notice">{notice}</p>}
      <p className="visually-hidden" aria-live="polite" aria-atomic="true">
        {statusMessage}
      </p>
    </>
  )
}

/** Laid over the chat panel while files are dragged across it. */
export function AttachmentDropOverlay({ visible }: { visible: boolean }) {
  if (!visible) return null
  return (
    <div className="chat-attach-drop" aria-hidden="true">
      <div className="chat-attach-drop-inner">
        <svg width="22" height="22" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path d="M8 10.5V2.5M4.5 6 8 2.5 11.5 6M2.5 10.5v2a1 1 0 0 0 1 1h9a1 1 0 0 0 1-1v-2" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        <span>Drop files to attach them</span>
      </div>
    </div>
  )
}
