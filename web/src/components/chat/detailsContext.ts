// @find: details mode, automatic expanded collapsed, reveal goal, thread details choice, context
// @what: React context for the thread-wide Details mode and goal reveal.
// @flow: Provided by the Chat page; read by RoutingCard and ProgressCard.
import { createContext } from 'react'

/*
 * The thread-wide "Details" choice (B1.4): Automatic, Expanded or Collapsed, applied to every
 * collapsible card at once. `version` increments on each change of `mode`, so a card that has been
 * toggled by hand (see useCollapsed in components/ui/Collapsible.tsx) can tell a fresh switch of
 * mode from the one it already followed, and keep its own state until the next one.
 *
 * `revealGoal` is a one-off request to open one goal's progress card and bring its decision into
 * view, from the work strip's "Review" link. The `nonce` changes on every request, so asking for
 * the same goal twice still reveals it twice, even after the person folded it again in between.
 */

export type DetailsMode = 'auto' | 'expanded' | 'collapsed'

export type RevealGoal = { goalId: string; nonce: number }

// @find: DetailsContext, details context, details mode, automatic expanded collapsed, reveal goal, thread details choice
export const DetailsContext = createContext<{ mode: DetailsMode; version: number; revealGoal?: RevealGoal | null }>({
  mode: 'auto',
  version: 0,
  revealGoal: null,
})
