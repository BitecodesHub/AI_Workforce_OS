import { useEffect, useRef, useState } from 'react'
import { IconButton } from './index'

/*
 * Copy some text to the clipboard, with a plain word for what happened.
 *
 * The Clipboard API is refused or missing often enough - an insecure context, a locked-down
 * browser policy, an old WebView - that a fallback is not optional. The fallback is the pattern
 * already proven at Members.tsx:113-150: a hidden, selected textarea and the document's own
 * copy command.
 */

async function copyText(value: string): Promise<void> {
  try {
    if (!navigator.clipboard) throw new Error('Clipboard unavailable')
    await navigator.clipboard.writeText(value)
    return
  } catch {
    const area = document.createElement('textarea')
    area.value = value
    area.setAttribute('readonly', '')
    // Off-screen, not merely hidden: a hidden or zero-size field cannot receive a selection in
    // every browser this falls back for.
    area.style.position = 'fixed'
    area.style.top = '0'
    area.style.left = '-9999px'
    document.body.appendChild(area)
    area.focus()
    area.select()
    try {
      document.execCommand('copy')
    } finally {
      document.body.removeChild(area)
    }
  }
}

const COPY_ICON = (
  <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
    <rect x="5.5" y="5.5" width="8" height="8" rx="1.5" stroke="currentColor" strokeWidth="1.3" />
    <path d="M3.5 10.5V3.5a1 1 0 0 1 1-1h7" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
  </svg>
)

export function CopyButton({
  text,
  label = 'Copy',
  copiedLabel = 'Copied',
  variant = 'icon',
}: {
  /** The text to copy, or a function returning it - useful when it is expensive or changes often. */
  text: string | (() => string)
  label?: string
  copiedLabel?: string
  variant?: 'icon' | 'text'
}) {
  const [copied, setCopied] = useState(false)
  const timeoutRef = useRef<number | null>(null)

  useEffect(
    () => () => {
      if (timeoutRef.current !== null) window.clearTimeout(timeoutRef.current)
    },
    [],
  )

  const handleCopy = () => {
    const value = typeof text === 'function' ? text() : text
    void copyText(value).then(() => {
      setCopied(true)
      if (timeoutRef.current !== null) window.clearTimeout(timeoutRef.current)
      timeoutRef.current = window.setTimeout(() => setCopied(false), 2000)
    })
  }

  return (
    <span className="copy-button">
      {variant === 'text' ? (
        <button type="button" className="button button-quiet button-sm" onClick={handleCopy}>
          {copied ? copiedLabel : label}
        </button>
      ) : (
        <IconButton label={copied ? copiedLabel : label} onClick={handleCopy}>
          {COPY_ICON}
        </IconButton>
      )}
      {/* Announced once, politely, on top of whatever the visible label already shows. */}
      <span className="visually-hidden" role="status">
        {copied ? copiedLabel : ''}
      </span>
    </span>
  )
}
