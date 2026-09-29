/*
 * The window event an in-page link dispatches just before it scrolls, carrying the target's id,
 * so a component that keeps that target hidden (the demo stage's tabs) can show it first.
 */
export const REVEAL_EVENT = 'lp:reveal'

export type RevealDetail = { id: string }
