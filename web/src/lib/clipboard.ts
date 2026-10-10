// @find: clipboard, copy text, copy to clipboard, copy button, copy failed toast, useCopyText
// @what: Copies text to the clipboard and always reports success or failure through a toast.
// @flow: Used by every Copy button; shows toasts from toast.tsx
import { useToast } from './toast'

/*
 * Copying to the clipboard, with an answer either way. navigator.clipboard is missing outside a
 * secure context and can refuse permission, and a copy that fails silently looks exactly like one
 * that worked - so every copy says which it was.
 */

/** The toast after a copy the browser refused. */
export const COPY_FAILED = 'This could not be copied. Select the text and copy it yourself instead.'

// @find: copy text, clipboard write, never throws
/** Puts `text` on the clipboard. Resolves to whether it got there; never throws. */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // Refused (no permission, or the page is not focused): try the older way below.
  }
  return copyWithSelection(text)
}

/**
 * The older way to copy, which still works where the Clipboard API is missing or refused - a
 * page opened over plain http on the office network, or inside an embedded browser view.
 */
function copyWithSelection(text: string): boolean {
  if (typeof document === 'undefined' || typeof document.execCommand !== 'function') return false
  const active = document.activeElement instanceof HTMLElement ? document.activeElement : null
  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.opacity = '0'
  area.style.pointerEvents = 'none'
  document.body.appendChild(area)
  try {
    area.select()
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    area.remove()
    active?.focus()
  }
}

// @find: use copy text, copy with toast, copy button
/**
 * A copy action for a menu or a button: copies, then shows `success` ("Link copied") or the
 * failure toast. Resolves to whether the copy worked.
 */
export function useCopyText(): (text: string, success: string) => Promise<boolean> {
  const toast = useToast()
  return async (text: string, success: string) => {
    const copied = await copyText(text)
    if (copied) toast.success(success)
    else toast.error(COPY_FAILED)
    return copied
  }
}
