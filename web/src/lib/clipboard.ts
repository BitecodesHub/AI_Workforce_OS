import { useToast } from './toast'

/*
 * Copying to the clipboard, with an answer either way. navigator.clipboard is missing outside a
 * secure context and can refuse permission, and a copy that fails silently looks exactly like one
 * that worked - so every copy says which it was.
 */

/** The toast after a copy the browser refused. */
export const COPY_FAILED = 'This could not be copied. Select the text and copy it yourself instead.'

/** Puts `text` on the clipboard. Resolves to whether it got there; never throws. */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (typeof navigator === 'undefined' || !navigator.clipboard?.writeText) return false
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}

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
