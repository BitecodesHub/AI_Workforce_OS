// @find: reveal event, lp:reveal, in-page link, show hidden demo tab, REVEAL_EVENT
// @what: Defines the window event that opens a hidden target before scrolling.
// @flow: Dispatched by useInPageLink; heard by DemoStage
/*
 * The window event an in-page link dispatches just before it scrolls, carrying the target's id,
 * so a component that keeps that target hidden (the demo stage's tabs) can show it first.
 */
export const REVEAL_EVENT = 'lp:reveal'

export type RevealDetail = { id: string }
