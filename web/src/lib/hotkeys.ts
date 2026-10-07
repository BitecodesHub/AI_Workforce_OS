import { useEffect } from 'react'

/*
 * Keyboard shortcuts shared by Chat and the Orchestrator (B1.11, B2.12). One listener per screen,
 * bound in an effect and torn down with it - never read or written during render (0.2).
 */

export type Hotkey = {
  key: string
  mod?: boolean
  shift?: boolean
  alt?: boolean
  /** Fires even while an input, textarea, select or contenteditable has focus. Off by default. */
  allowInInput?: boolean
  handler: (e: KeyboardEvent) => void
}

export function isMac(): boolean {
  if (typeof navigator === 'undefined') return false
  const platform = navigator.platform || navigator.userAgent || ''
  return /Mac|iPhone|iPad|iPod/.test(platform)
}

/** 'Cmd' on macOS and iOS, 'Ctrl' everywhere else. */
export function modLabel(): 'Cmd' | 'Ctrl' {
  return isMac() ? 'Cmd' : 'Ctrl'
}

function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable) return true
  const tag = target.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT'
}

function matches(hotkey: Hotkey, e: KeyboardEvent): boolean {
  if (e.key.toLowerCase() !== hotkey.key.toLowerCase()) return false
  if ((e.metaKey || e.ctrlKey) !== Boolean(hotkey.mod)) return false
  if (e.shiftKey !== Boolean(hotkey.shift)) return false
  if (e.altKey !== Boolean(hotkey.alt)) return false
  return true
}

/** Binds every hotkey to `keydown` while `enabled`, skipping typing targets unless allowed. */
export function useHotkeys(bindings: Hotkey[], enabled = true): void {
  useEffect(() => {
    if (!enabled) return
    const onKeyDown = (e: KeyboardEvent) => {
      for (const hotkey of bindings) {
        if (!matches(hotkey, e)) continue
        if (isTypingTarget(e.target) && !hotkey.allowInInput) continue
        // Esc inside an open dialog belongs to the dialog: preventing its default here would stop
        // the browser from closing a modal <dialog> (the delete confirmation, say).
        if (e.key === 'Escape' && e.target instanceof Element && e.target.closest('dialog[open]')) return
        e.preventDefault()
        hotkey.handler(e)
        return
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [bindings, enabled])
}

/** The keys a hotkey is shown with, in reading order: for example ['Cmd', 'Shift', 'K']. */
export function formatHotkey(hotkey: Hotkey): string[] {
  const keys: string[] = []
  if (hotkey.mod) keys.push(modLabel())
  if (hotkey.shift) keys.push('Shift')
  if (hotkey.alt) keys.push(isMac() ? 'Option' : 'Alt')
  keys.push(hotkey.key.length === 1 ? hotkey.key.toUpperCase() : hotkey.key)
  return keys
}
