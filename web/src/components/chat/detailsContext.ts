import { createContext } from 'react'

/*
 * The thread-wide "Details" choice (B1.4): Automatic, Expanded or Collapsed, applied to every
 * collapsible card at once. `version` increments on each change of `mode`, so a card that has been
 * toggled by hand (see useCollapsed in components/ui/Collapsible.tsx) can tell a fresh switch of
 * mode from the one it already followed, and keep its own state until the next one.
 */

export type DetailsMode = 'auto' | 'expanded' | 'collapsed'

export const DetailsContext = createContext<{ mode: DetailsMode; version: number }>({ mode: 'auto', version: 0 })
