// @find: agent desk rules, working now tiles from board, agents working now, frame click to page coordinates, take over browser, who has control, step caption, takeover keys, control ended message
// @what: Pure rules for the Agent desk: which agents are working now (from the board), the caption for what each is doing, mapping a click on the live frame to page coordinates, naming who has control and saying why control ended.
// @flow: Used by WorkingNow, AgentDesk and TakeoverControls; data from useBoard, useRunGlance, useRunSteps, useBrowserLive and agentDeskQueries
import type { Board, BrowserLive, RunStep } from './queries'
import type { TakeoverView } from './agentDeskQueries'
import { serverLabel } from './labels'

/** A text field of a step's detail, or null. */
function textOf(detail: Record<string, unknown>, key: string): string | null {
  const value = detail[key]
  return typeof value === 'string' && value.trim() ? value : null
}

/** A list-of-text field of a step's detail, or an empty list. */
function stringsOf(detail: Record<string, unknown>, key: string): string[] {
  const value = detail[key]
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
}

/** What a working agent is doing with its run: working, waiting for an approval, or waiting for an answer. */
export type WorkingState = 'running' | 'waiting' | 'asking'

/** One tile in "Working now": an agent and the run it is busy with. */
export type WorkingTile = {
  agentId: string
  name: string
  category: string
  fallback: boolean
  runId: string
  state: WorkingState
  /** When the run started, when the board's timeline has it; null otherwise. */
  startedAt: string | null
}

/** The most tiles "Working now" shows; the rest are on the Orchestrator. */
export const WORKING_LIMIT = 12

// @find: workingNow, agents working now from the board, one tile per busy agent
/**
 * One tile per agent with a run that is working or waiting, from the board the Orchestrator already
 * reads: the running run first, then one waiting for an approval, then one waiting for an answer.
 * Ordered with working agents first, then by name; at most WORKING_LIMIT. Pure.
 */
export function workingNow(board: Board | undefined | null, limit: number = WORKING_LIMIT): WorkingTile[] {
  if (!board) return []
  const started = new Map(board.timeline.map((entry) => [entry.runId, entry.startedAt]))
  const tiles: WorkingTile[] = []
  for (const agent of board.agents) {
    const runId = agent.runningRunIds[0] ?? agent.waitingRunIds[0] ?? agent.askingRunIds[0]
    if (!runId) continue
    const state: WorkingState = agent.runningRunIds[0] ? 'running' : agent.waitingRunIds[0] ? 'waiting' : 'asking'
    tiles.push({
      agentId: agent.id,
      name: agent.name,
      category: agent.category,
      fallback: agent.fallback,
      runId,
      state,
      startedAt: started.get(runId) ?? null,
    })
  }
  const rank: Record<WorkingState, number> = { running: 0, waiting: 1, asking: 2 }
  return tiles.sort((a, b) => rank[a.state] - rank[b.state] || a.name.localeCompare(b.name)).slice(0, limit)
}

/** When a run started: the board's timeline, else the start the run itself reports, else null. Pure. */
export function startedAtOf(tile: WorkingTile, runStartedAt: string | null | undefined): string | null {
  return tile.startedAt ?? runStartedAt ?? null
}

/** What a caption reads of a step: its kind and detail. A full trace step or a glance both fit. */
export type CaptionStep = Pick<RunStep, 'kind' | 'detail'>

/** Sentences for the web browser's own tools, done and about to be done. */
const BROWSER_PHRASES: Record<string, { done: string; next: string }> = {
  open_page: { done: 'Opened a web page', next: 'About to open a web page' },
  read_page: { done: 'Read the web page', next: 'About to read the web page' },
  follow_link: { done: 'Followed a link', next: 'About to follow a link' },
  search_site: { done: 'Searched a website', next: 'About to search a website' },
  fill_field: { done: 'Typed into a field on a web page', next: 'About to type into a field on a web page' },
  click_button: { done: 'Clicked a button on a web page', next: 'About to click a button on a web page' },
  submit_form: { done: 'Submitted a form on a web page', next: 'About to submit a form on a web page' },
}

/** Sentences for the platform's own tools, which are not connectors. */
const OWN_PHRASES: Record<string, { done: string; next: string }> = {
  'person.ask_question': { done: 'Asked a question', next: 'About to ask a question' },
  'memory.remember': { done: 'Noted something to remember', next: 'About to note something to remember' },
  'memory.recall': { done: 'Checked its notes', next: 'About to check its notes' },
}

// @find: tool sentence, caption for a tool call in plain words
/**
 * A tool call as a short sentence for a caption, done ('Opened a web page') or about to be done
 * ('About to use Gmail'). Browser and platform tools have their own words; any other tool is named
 * by its connector. The wire form server__tool reads the same as server.tool. Pure.
 */
export function toolSentence(qualified: string | null | undefined, when: 'done' | 'next'): string {
  if (!qualified || !qualified.trim()) return when === 'done' ? 'Used a tool' : 'About to use a tool'
  const name = qualified.replace('__', '.')
  const own = OWN_PHRASES[name]
  if (own) return own[when]
  const dot = name.indexOf('.')
  const server = dot > 0 ? name.slice(0, dot) : name
  const tool = dot > 0 ? name.slice(dot + 1) : ''
  if (server === 'browser') {
    const phrase = BROWSER_PHRASES[tool]
    if (phrase) return phrase[when]
    return when === 'done' ? 'Used the web browser' : 'About to use the web browser'
  }
  const connector = serverLabel(server)
  return when === 'done' ? `Used ${connector}` : `About to use ${connector}`
}

// @find: step caption, what the agent is doing now, current step of a run
/**
 * A few words for what a working agent is doing now: the browser's own words while it acts, a
 * person's control, waiting for an approval or an answer, or its latest step. Pure.
 */
export function stepCaption(
  steps: readonly CaptionStep[] | undefined,
  state: WorkingState,
  live?: BrowserLive | null,
  personInControl = false,
): string {
  if (personInControl) return 'A person has control of the web browser'
  if (state === 'waiting') return 'Waiting for an approval'
  if (state === 'asking') return 'Waiting for an answer to its question'
  if (live?.busy && live.action) return live.action
  const last = steps && steps.length > 0 ? steps[steps.length - 1]! : null
  if (!last) return 'Starting'
  switch (last.kind) {
    case 'model_call': {
      const planned = stringsOf(last.detail, 'toolCalls')
      return planned.length > 0 ? toolSentence(planned[0]!, 'next') : 'Thinking about the next step'
    }
    case 'tool_call':
      return toolSentence(textOf(last.detail, 'tool'), 'done')
    case 'knowledge_query':
      return 'Searched the documents'
    case 'memory_read':
    case 'memory_write':
      return 'Checked its notes'
    case 'note': {
      const type = textOf(last.detail, 'type')
      if (type === 'taken_over') return 'A person has control of the web browser'
      if (type === 'handed_back') return 'Carrying on after a person handed the browser back'
      return 'Getting started'
    }
    default:
      return 'Working'
  }
}

/** A pointer position on screen. */
export type ScreenPoint = { clientX: number; clientY: number }
/** Where the picture is drawn on screen. */
export type ScreenBox = { left: number; top: number; width: number; height: number }
/** The picture's own size in pixels. */
export type FrameSize = { width: number; height: number }
/** A click on the frame, in the frame's own pixels, with the frame's size, as the server expects it. */
export type FrameClick = { x: number; y: number; width: number; height: number }

// @find: framePoint, map click on live frame to page coordinates, takeover click
/**
 * Maps a click on the live frame, drawn at `box` on screen, to the frame's own pixels: what the
 * server scales to the page. Null when the point is outside the picture or either size is unknown.
 * Rounded to whole pixels. Pure.
 */
export function framePoint(point: ScreenPoint, box: ScreenBox, frame: FrameSize): FrameClick | null {
  if (box.width <= 0 || box.height <= 0 || frame.width <= 0 || frame.height <= 0) return null
  const offsetX = point.clientX - box.left
  const offsetY = point.clientY - box.top
  if (offsetX < 0 || offsetY < 0 || offsetX > box.width || offsetY > box.height) return null
  return {
    x: Math.round((offsetX / box.width) * frame.width),
    y: Math.round((offsetY / box.height) * frame.height),
    width: frame.width,
    height: frame.height,
  }
}

/** The frame size the browser runner draws at, used before a picture has loaded. */
export const DEFAULT_FRAME: FrameSize = { width: 1280, height: 800 }

/** The keys a person can press from the desk, with what a screen reader hears. */
export const TAKEOVER_KEYS: ReadonlyArray<{ key: string; label: string; text: string }> = [
  { key: 'Enter', label: 'Press Enter', text: 'Enter' },
  { key: 'Tab', label: 'Press Tab', text: 'Tab' },
  { key: 'Escape', label: 'Press Escape', text: 'Esc' },
  { key: 'Backspace', label: 'Press Backspace', text: 'Backspace' },
  { key: 'ArrowUp', label: 'Press the up arrow', text: '↑' },
  { key: 'ArrowDown', label: 'Press the down arrow', text: '↓' },
  { key: 'ArrowLeft', label: 'Press the left arrow', text: '←' },
  { key: 'ArrowRight', label: 'Press the right arrow', text: '→' },
]

/** How far one press of a scroll button moves the page, in pixels. */
export const SCROLL_STEP = 600

/**
 * The name of the person holding control, for "Priya has control": their display name from the
 * member list, else a neutral phrase. Pure.
 */
export function holderName(takenBy: string | null | undefined, names: Record<string, { displayName?: string | null }>): string {
  if (!takenBy) return 'Someone'
  const name = names[takenBy]?.displayName?.trim()
  return name || 'Another person'
}

// @find: control ended message, why control of the browser ended, takeover ended without hand back
/**
 * Why control of the web browser left the signed-in person, when they did not hand it back
 * themselves: 30 minutes passed, someone else handed it back, the run stopped or parked, or it
 * returned for a reason the server did not give. Null when there is nothing to say (they handed it
 * back themselves, or still have it). Pure.
 *
 * @param view the takeover as it stands now
 * @param me the signed-in person's user id
 * @param agentName the agent's name
 * @param names display names by user id, for who handed it back
 */
export function controlEndedMessage(
  view: TakeoverView | null | undefined,
  me: string | null | undefined,
  agentName: string,
  names: Record<string, { displayName?: string | null }>,
): string | null {
  if (!view || ((view.active || view.starting) && view.mine)) return null
  switch (view.status) {
    case 'expired':
      return `Control returned to ${agentName} after 30 minutes.`
    case 'released':
      if (view.releasedBy && view.releasedBy === me) return null
      return `${holderName(view.releasedBy, names)} handed control back to ${agentName}.`
    case 'ended':
      return `Control of the web browser ended because ${agentName} stopped working on this run or is waiting for a person.`
    default:
      return `Control returned to ${agentName}.`
  }
}

/** Whole minutes left before control returns to the agent by itself, at least 0. Pure. */
export function minutesLeft(endsAt: string | null | undefined, now: number): number | null {
  if (!endsAt) return null
  const ends = Date.parse(endsAt)
  if (Number.isNaN(ends)) return null
  return Math.max(0, Math.ceil((ends - now) / 60_000))
}

/**
 * Wheel movement collected into one scroll: the summed distance, rounded and kept within the
 * server's limit of 5000 pixels either way; 0 when there is nothing to send. Pure.
 */
export function wheelDistance(deltas: readonly number[]): number {
  const total = Math.round(deltas.reduce((sum, delta) => sum + delta, 0))
  return Math.max(-5000, Math.min(5000, total))
}
