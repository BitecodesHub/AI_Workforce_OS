import { formatHotkey, modLabel, useHotkeys } from '../../lib/hotkeys'
import type { Hotkey } from '../../lib/hotkeys'

/*
 * Chat's own keyboard shortcuts (B1.11), built on WP3's useHotkeys. The Esc order (B1.11) is
 * handled by the caller, since it depends on which overlay, menu or field currently has focus -
 * state this hook has no view of - so it is passed in as `onEscape` and simply wired to the key.
 */

export type ChatShortcutHandlers = {
  onFocusSearch: () => void
  onNewConversation: () => void
  onToggleSidebar: () => void
  onPreviousConversation: () => void
  onNextConversation: () => void
  onShowShortcuts: () => void
  onEscape: () => void
}

export function useChatShortcuts(handlers: ChatShortcutHandlers, enabled = true) {
  const bindings: Hotkey[] = [
    { key: 'k', mod: true, allowInInput: true, handler: handlers.onFocusSearch },
    { key: 'o', mod: true, shift: true, allowInInput: true, handler: handlers.onNewConversation },
    { key: 's', mod: true, shift: true, allowInInput: true, handler: handlers.onToggleSidebar },
    { key: 'ArrowUp', alt: true, handler: handlers.onPreviousConversation },
    { key: 'ArrowDown', alt: true, handler: handlers.onNextConversation },
    { key: '/', mod: true, allowInInput: true, handler: handlers.onShowShortcuts },
    { key: 'Escape', allowInInput: true, handler: handlers.onEscape },
  ]
  useHotkeys(bindings, enabled)
}

const GLOBAL_GROUP = {
  title: 'Chat',
  items: [
    { keys: formatHotkey({ key: 'k', mod: true, handler: () => {} }), description: 'Focus conversation search' },
    { keys: formatHotkey({ key: 'o', mod: true, shift: true, handler: () => {} }), description: 'New conversation' },
    { keys: formatHotkey({ key: 's', mod: true, shift: true, handler: () => {} }), description: 'Show or hide the sidebar' },
    { keys: [modLabel() === 'Cmd' ? 'Option' : 'Alt', '↑'], description: 'Previous conversation' },
    { keys: [modLabel() === 'Cmd' ? 'Option' : 'Alt', '↓'], description: 'Next conversation' },
    { keys: formatHotkey({ key: '/', mod: true, handler: () => {} }), description: 'Keyboard shortcuts' },
    { keys: ['Esc'], description: 'Close a menu, dialog or reply, or return to the message box' },
    { keys: [modLabel(), 'Enter'], description: 'Send the answer, inside a question card' },
  ],
}

/** The groups shown in the shortcuts dialog (WP3's ShortcutsDialog). */
export function chatShortcutGroups() {
  return [GLOBAL_GROUP]
}
