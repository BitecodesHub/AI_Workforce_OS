// @find: browser runner takeover, person takes over the browser, take control, click at coordinates, type into focused field, key press, scroll, navigate, hold page open, POST /sessions/{id}/takeover/{action}, field label not typed text
// @what: Checks and carries out the actions of a person who has taken over a run's page: a click scaled from the frame to the viewport, typing, a key press, a scroll or opening an address.
// @flow: Used by server.js for POST /sessions/{id}/takeover/{action}; the orchestrator's TakeoverService sends these through mcp-core BrowserAdapter.takeover
'use strict'

const { checkUrl } = require('./guard')

// The actions a person may take on a page they have taken over, and "hold", which keeps the page
// open while they look without acting.
const TAKEOVER_ACTIONS = ['click', 'type', 'key', 'scroll', 'navigate', 'hold']

// Keys a person may press. Anything else is typed as text, or not at all.
const KEYS = new Set([
  'Enter',
  'Tab',
  'Shift+Tab',
  'Escape',
  'Backspace',
  'Delete',
  'ArrowUp',
  'ArrowDown',
  'ArrowLeft',
  'ArrowRight',
  'PageUp',
  'PageDown',
  'Home',
  'End',
])

// The most text one "type" sends, and the widest frame a click can be measured against.
const TEXT_LIMIT = 2000
const MAX_FRAME = 10000
const MAX_SCROLL = 5000
const URL_LIMIT = 2048
// A person holding a page keeps it open at most this long; the orchestrator returns control then.
const HOLD_MS = 30 * 60 * 1000

/** A refusal in the shape server.js turns into {error} with the status. */
function refuse(status, message) {
  return { ok: false, status, message }
}

/** Whether a value is a finite number. Pure. */
function finite(value) {
  return typeof value === 'number' && Number.isFinite(value)
}

/**
 * Scales a point measured on a frame of WIDTH x HEIGHT pixels to the page's viewport, and keeps it
 * inside the viewport. The live frame is taken at the viewport's own size, but a frame drawn
 * smaller (or a future smaller frame) must still land where the person pointed.
 * @param {{x: number, y: number, width: number, height: number}} point the click on the frame.
 * @param {{width: number, height: number}} viewport the page's viewport.
 * @returns {{x: number, y: number}} viewport coordinates, rounded to a tenth of a pixel. Pure.
 */
function scalePoint(point, viewport) {
  const x = (point.x / point.width) * viewport.width
  const y = (point.y / point.height) * viewport.height
  const clamp = (value, max) => Math.round(Math.min(Math.max(value, 0), max - 1) * 10) / 10
  return { x: clamp(x, viewport.width), y: clamp(y, viewport.height) }
}

/**
 * Checks one takeover action's body and answers the values to act on, or a refusal.
 *
 * Rules: click needs x and y inside a frame of width and height (1 to 10000 pixels) and is scaled
 * to the viewport; type needs 1 to 2000 characters of text; key must be one of KEYS; scroll needs
 * a whole dy between -5000 and 5000, not 0; navigate needs an http or https address that passes
 * the guard's syntax check (the full check, with the name looked up, happens when it is opened);
 * hold needs on as true or false.
 * @param {string} action one of TAKEOVER_ACTIONS.
 * @param {object} body the parsed request body.
 * @param {{width: number, height: number}} viewport the page's viewport.
 * @returns {{ok: true, value: object} | {ok: false, status: number, message: string}} never throws. Pure.
 */
function validateTakeover(action, body, viewport) {
  const input = body && typeof body === 'object' ? body : {}
  switch (action) {
    case 'click': {
      const { x, y, width, height } = input
      if (![x, y, width, height].every(finite)) return refuse(400, 'Say where to click, with the size of the picture clicked on.')
      if (width < 1 || height < 1 || width > MAX_FRAME || height > MAX_FRAME) return refuse(400, 'The picture size is not valid.')
      if (x < 0 || y < 0 || x > width || y > height) return refuse(400, 'That point is outside the page.')
      return { ok: true, value: scalePoint({ x, y, width, height }, viewport) }
    }
    case 'type': {
      const text = typeof input.text === 'string' ? input.text : ''
      if (text.length === 0) return refuse(400, 'Say what to type.')
      if (text.length > TEXT_LIMIT) return refuse(400, `Type at most ${TEXT_LIMIT} characters at a time.`)
      return { ok: true, value: { text } }
    }
    case 'key': {
      const key = typeof input.key === 'string' ? input.key : ''
      if (!KEYS.has(key)) return refuse(400, 'That key cannot be pressed here.')
      return { ok: true, value: { key } }
    }
    case 'scroll': {
      const dy = input.dy
      if (!Number.isInteger(dy) || dy === 0 || Math.abs(dy) > MAX_SCROLL) {
        return refuse(400, `Scroll by a whole number of pixels between -${MAX_SCROLL} and ${MAX_SCROLL}.`)
      }
      return { ok: true, value: { dy } }
    }
    case 'navigate': {
      const url = typeof input.url === 'string' ? input.url.trim() : ''
      if (!url) return refuse(400, 'Give the full web address of the page to open, starting with https://.')
      if (url.length > URL_LIMIT) return refuse(400, 'That address is too long.')
      const checked = checkUrl(url)
      if (!checked.ok) return refuse(422, checked.reason)
      return { ok: true, value: { url } }
    }
    case 'hold': {
      if (typeof input.on !== 'boolean') return refuse(400, 'Say whether to hold the page, as true or false.')
      return { ok: true, value: { on: input.on } }
    }
    default:
      return refuse(404, 'Not found.')
  }
}

// @find: focused field label, typing into a field in a shadow root or an iframe
/**
 * Reads, in a document, the label of the field that has the keyboard: its label element,
 * aria-label, placeholder or name, in that order. Follows focus into open shadow roots and into
 * frames from the same site; never reads the field's value. Self-contained, because the browser
 * runs it in the page (page.evaluate serialises it), and pure over the document it is given, so
 * tests pass a stand-in.
 * @param {{requireFocus?: boolean}} [options] requireFocus: answer null unless this document has
 *   focus (used for each frame of another site, which the page itself cannot look into).
 * @param {Document} [given] the document to read; the page's own when left out.
 * @returns {{label: string} | {frame: true} | null} the label (at most 80 characters, '' when the
 *   field has none); {frame: true} when focus is inside a frame of another site; null when nothing
 *   that takes typing has focus.
 */
function focusedLabelIn(options, given) {
  const doc = given || document
  if (options && options.requireFocus && typeof doc.hasFocus === 'function' && !doc.hasFocus()) return null
  const NOT_TYPED = ['button', 'submit', 'reset', 'checkbox', 'radio', 'file', 'image', 'hidden', 'range', 'color']
  let el = doc.activeElement
  let body = doc.body
  // Into shadow roots and same-site frames, a bounded number of levels deep.
  for (let depth = 0; el && depth < 10; depth++) {
    if (el.shadowRoot && el.shadowRoot.activeElement) {
      el = el.shadowRoot.activeElement
      continue
    }
    const tag = String(el.tagName || '').toLowerCase()
    if (tag === 'iframe' || tag === 'frame') {
      let inner = null
      try {
        inner = el.contentDocument
      } catch {
        inner = null
      }
      if (!inner) return { frame: true }
      el = inner.activeElement
      body = inner.body
      continue
    }
    break
  }
  if (!el || el === body) return null
  const tag = String(el.tagName || '').toLowerCase()
  const type = String((el.getAttribute && el.getAttribute('type')) || 'text').toLowerCase()
  const editable = el.isContentEditable === true || tag === 'textarea' || (tag === 'input' && !NOT_TYPED.includes(type))
  if (!editable) return null
  let label = ''
  if (el.labels && el.labels.length) label = el.labels[0].innerText || el.labels[0].textContent || ''
  if (!label && el.getAttribute) label = el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.getAttribute('name') || ''
  return { label: String(label).replace(/\s+/g, ' ').trim().slice(0, 80) }
}

/**
 * The label of the field that has the keyboard in the run's page, never its value. Looks in the
 * page (with its shadow roots and same-site frames) first; when focus is inside a frame from
 * another site, asks each frame, and uses the one that has focus.
 * @param {import('playwright-core').Page} page the run's page.
 * @returns {Promise<string|null>} at most 80 characters; '' for a field with no label, or inside a
 *   frame whose field cannot be read (typed into as "an unnamed field"); null when nothing that
 *   takes typing has focus. Never throws.
 */
async function focusedFieldLabel(page) {
  const found = await Promise.resolve()
    .then(() => page.evaluate(focusedLabelIn, {}))
    .catch(() => null)
  if (!found) return null
  if (typeof found.label === 'string') return found.label
  const frames = typeof page.frames === 'function' ? page.frames() : []
  const main = typeof page.mainFrame === 'function' ? page.mainFrame() : null
  for (const frame of frames) {
    if (frame === main) continue
    const inner = await Promise.resolve()
      .then(() => frame.evaluate(focusedLabelIn, { requireFocus: true }))
      .catch(() => null)
    if (inner && typeof inner.label === 'string') return inner.label
  }
  // Something inside another site's frame has focus, but its label cannot be read.
  return ''
}

/**
 * Carries out one checked takeover action on the run's page.
 * Flow: click moves the mouse and clicks at the viewport point, then lets the page settle; type
 * reads the focused field's label first (refused with 409 when nothing that takes typing has
 * focus) and types the text; key presses the key; scroll moves the mouse to the middle of the page
 * and turns the wheel; navigate opens the address through deps.navigate, which applies the full
 * address guard. Never logs or returns the typed text.
 * @param {import('playwright-core').Page} page the run's page.
 * @param {string} action one of click, type, key, scroll, navigate.
 * @param {object} value the checked values from validateTakeover.
 * @param {{navigate: Function, settle: Function, viewport: {width: number, height: number}}} deps
 *   navigate(page, url) opens an address after the guard (throws a refusal), settle(page) waits
 *   for the page to quieten.
 * @returns {Promise<{ok: true, field?: string} | {ok: false, status: number, message: string}>}
 * Failure: a refusal from deps.navigate is rethrown for the HTTP layer; other errors propagate.
 */
async function performTakeover(page, action, value, deps) {
  switch (action) {
    case 'click':
      await page.mouse.move(value.x, value.y)
      await page.mouse.click(value.x, value.y)
      await deps.settle(page)
      return { ok: true }
    case 'type': {
      const label = await focusedFieldLabel(page)
      if (label === null) return refuse(409, 'Click the field to type into first.')
      await page.keyboard.type(value.text, { delay: 5 })
      return { ok: true, field: label || 'an unnamed field' }
    }
    case 'key':
      await page.keyboard.press(value.key)
      await deps.settle(page)
      return { ok: true }
    case 'scroll':
      await page.mouse.move(deps.viewport.width / 2, deps.viewport.height / 2)
      await page.mouse.wheel(0, value.dy)
      return { ok: true }
    case 'navigate':
      await deps.navigate(page, value.url)
      return { ok: true }
    default:
      return refuse(404, 'Not found.')
  }
}

module.exports = {
  TAKEOVER_ACTIONS,
  KEYS,
  TEXT_LIMIT,
  HOLD_MS,
  scalePoint,
  validateTakeover,
  focusedLabelIn,
  focusedFieldLabel,
  performTakeover,
}
