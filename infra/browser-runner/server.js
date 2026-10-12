// @find: browser runner, headless chromium, playwright, browser sidecar, open page, read page, follow link, fill field, click button, submit form, screenshot, browser session per run, idle timeout, shared secret, POST /sessions/{id}/open, DELETE /sessions/{id}, GET /health
// @what: The browser runner: a small HTTP server that drives one shared headless Chromium, one browser context per run, and answers each step with the page as text plus a JPEG screenshot.
// @flow: Called by mcp-core BrowserAdapter (orchestrator) with the X-Runner-Secret header; every Chromium request goes through proxy.js, which applies guard.js
'use strict'

const http = require('node:http')
const crypto = require('node:crypto')
const { chromium } = require('playwright-core')
const { checkAndResolve, checkUrl, PRIVATE_MESSAGE } = require('./guard')
const { startProxy } = require('./proxy')

// Settings come from the environment so the same image runs locally and on the server. Without
// BROWSER_RUNNER_SECRET every request except GET /health is refused.
const PORT = Number(process.env.BROWSER_RUNNER_PORT || 8090)
const HOST = process.env.BROWSER_RUNNER_HOST || '0.0.0.0'
const SECRET = process.env.BROWSER_RUNNER_SECRET || ''
const MAX_SESSIONS = Number(process.env.BROWSER_RUNNER_MAX_SESSIONS || 2)
const SESSION_IDLE_MS = Number(process.env.BROWSER_RUNNER_SESSION_IDLE_MS || 2 * 60 * 1000)
// Chromium stays running between runs by default (0), so no step waits for it to start; a positive
// value closes it after that long with no open page, to save memory on a small server.
const BROWSER_IDLE_MS = Number(process.env.BROWSER_RUNNER_BROWSER_IDLE_MS || 0)
// A run that finds every page in use waits this long for one to free up, instead of being refused.
const QUEUE_WAIT_MS = Number(process.env.BROWSER_RUNNER_QUEUE_WAIT_MS || 15 * 1000)
// After the page's own content has arrived, at most this long for its pictures and late content.
const SETTLE_MS = 2500
const PAGE_TIMEOUT_MS = Number(process.env.BROWSER_RUNNER_PAGE_TIMEOUT_MS || 20 * 1000)
// What one step sends back, kept small: every character is read by the model on every later turn,
// so a lean page answers faster and keeps runs on the fastest models. read_page with "from" gives
// the rest of a long page.
const TEXT_LIMIT = 3500
const LINK_LIMIT = 25
const FIELD_LIMIT = 15
const BUTTON_LIMIT = 12
// The live view: one small frame shared by every viewer, taken at most this often.
const LIVE_FRAME_MS = 700
const VIEWPORT = { width: 1280, height: 800 }
const MAX_BODY = 64 * 1024

// Process-wide state: the one shared Chromium, the launch in progress (so concurrent steps share
// one launch), the filtering proxy, the time the last session ended, and the open sessions by run.
let browser = null
let launching = null
let proxy = null
let browserIdleSince = Date.now()
const sessions = new Map()
// Per run, the last address it had open: if Chromium restarts mid-run, the page is opened again.
const lastPages = new Map()
// The live view: the newest frame of each page (a WeakMap, so a closed page's frame goes with it),
// and per run what the browser is doing right now, shown to the person watching.
const frames = new WeakMap()
const activities = new Map()
const LAST_PAGE_MS = 30 * 60 * 1000

/**
 * An expected, user-facing failure. The HTTP layer sends its status and message to the caller as
 * {error: message}; any other error becomes a generic 500 and is only logged.
 */
class Refusal extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

/**
 * Writes one timestamped line to standard output. Never throws; carries no secrets by design,
 * so callers pass host names and counts, not page content.
 */
function log(message) {
  process.stdout.write(`${new Date().toISOString()} ${message}\n`)
}

// Resolves after MS milliseconds.
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/** Resolves when PROMISE settles or after MS, whichever is first; never rejects. */
function within(promise, ms) {
  return Promise.race([Promise.resolve(promise).catch(() => {}), sleep(ms)])
}

// ---- Browser and sessions ---------------------------------------------------------------------

const LAUNCH_ARGS = [
  // Loopback is normally sent around a proxy; this sends it through, where it is refused.
  '--proxy-bypass-list=<-loopback>',
  '--force-webrtc-ip-handling-policy=disable_non_proxied_udp',
  '--disable-dev-shm-usage',
  '--no-first-run',
  '--no-default-browser-check',
  '--disable-background-networking',
  '--disable-component-update',
  '--disable-domain-reliability',
  '--disable-client-side-phishing-detection',
  '--disable-extensions',
  '--disable-sync',
  '--disable-gpu',
  '--disable-features=Translate,MediaRouter,OptimizationHints,AutofillServerCommunication',
  // Videos do not start on their own: they cost time and memory and the agent reads, not watches.
  '--autoplay-policy=user-gesture-required',
  '--mute-audio',
  // Keeps memory bounded on a small server: at most three renderer processes.
  '--renderer-process-limit=3',
  // Headless pages are never "in the background": timers and rendering run at full speed.
  '--disable-renderer-backgrounding',
  '--disable-background-timer-throttling',
  '--disable-backgrounding-occluded-windows',
]

/**
 * Launches one headless Chromium that sends every request through the local filtering proxy.
 * Requires the proxy to be running. Rejects if Chromium cannot start; the caller retries once.
 * @returns {Promise<import('playwright-core').Browser>}
 */
async function launchOnce() {
  return chromium.launch({
    headless: true,
    proxy: { server: `http://127.0.0.1:${proxy.port}` },
    args: LAUNCH_ARGS,
  })
}

/**
 * Returns the shared Chromium, starting it (and the proxy) when needed.
 * Flow: reuse a connected browser; join a launch already in progress; otherwise start the proxy
 * once, launch with one retry, and register a disconnect handler that clears the browser and
 * every session so the next step starts afresh.
 * @returns {Promise<import('playwright-core').Browser>}
 * Failure: rejects when the second launch attempt also fails; the HTTP layer reports it as a 500.
 */
async function ensureBrowser() {
  if (browser && browser.isConnected()) return browser
  if (launching) return launching
  launching = (async () => {
    if (!proxy) proxy = await startProxy({ onRefused: (host) => log(`refused a request to ${host}`) })
    // One retry: a launch can fail once while the previous Chromium is still exiting.
    const launched = await launchOnce().catch(async (error) => {
      log(`Chromium did not start (${String(error && error.message).split('\n')[0]}); trying again`)
      await sleep(1000)
      return launchOnce()
    })
    launched.on('disconnected', () => {
      if (browser === launched) {
        log('Chromium stopped; the next step starts it again and reopens each run\'s page')
        browser = null
      }
      sessions.clear()
    })
    browser = launched
    log('Chromium started')
    return launched
  })()
  try {
    return await launching
  } finally {
    launching = null
  }
}

/**
 * Closes one run's browser context and forgets the session.
 * @param {string} id the run identifier.
 * @returns {Promise<boolean>} false when the run had no session. Errors from closing are ignored.
 * Side effects: may reset the browser idle clock when the last session ends.
 */
async function closeSession(id) {
  const session = sessions.get(id)
  if (!session) return false
  sessions.delete(id)
  if (sessions.size === 0) browserIdleSince = Date.now()
  await session.context.close().catch(() => {})
  return true
}

/**
 * Finds a run's session, optionally creating it.
 * Flow: return and refresh an existing session; without create, return null; otherwise wait for a
 * free slot (closing the longest idle session once it has been unused for 30 seconds), start
 * Chromium if needed, and open a fresh context with one page. Extra pages and dialogs are
 * closed or dismissed automatically.
 * @param {string} id the run identifier.
 * @param {boolean} create whether a missing session may be created.
 * @returns {Promise<object|null>} {id, context, page, lastUsed}, or null when absent and not created.
 * Failure: throws Refusal 429 when no slot frees up within QUEUE_WAIT_MS.
 */
async function sessionFor(id, create) {
  const existing = sessions.get(id)
  if (existing) {
    existing.lastUsed = Date.now()
    return existing
  }
  if (!create) return null
  // Every page in use: the longest idle one gives way after 30 seconds unused; otherwise this run
  // waits in line for a free page rather than being refused straight away.
  const waitUntil = Date.now() + QUEUE_WAIT_MS
  while (sessions.size >= MAX_SESSIONS) {
    const oldest = [...sessions.entries()].sort((a, b) => a[1].lastUsed - b[1].lastUsed)[0]
    if (oldest && Date.now() - oldest[1].lastUsed > 30 * 1000) {
      await closeSession(oldest[0])
      break
    }
    if (Date.now() >= waitUntil) {
      throw new Refusal(429, 'The web browser is busy with other runs. Try again in a minute.')
    }
    await sleep(250)
    const appeared = sessions.get(id)
    if (appeared) return appeared
  }
  const running = await ensureBrowser()
  const context = await running.newContext({
    viewport: VIEWPORT,
    acceptDownloads: false,
    serviceWorkers: 'block',
    javaScriptEnabled: true,
    locale: 'en-AU',
    userAgent: undefined,
  })
  context.setDefaultNavigationTimeout(PAGE_TIMEOUT_MS)
  context.setDefaultTimeout(10 * 1000)
  const page = await context.newPage()
  // Pop-ups open in the background and are closed: the agent works on one page.
  context.on('page', (opened) => {
    if (opened !== page) opened.close().catch(() => {})
  })
  page.on('dialog', (dialog) => dialog.dismiss().catch(() => {}))
  const session = { id, context, page, lastUsed: Date.now() }
  sessions.set(id, session)
  return session
}

// Housekeeping every 15 seconds (unref'd, so it never keeps the process alive): closes sessions
// idle beyond SESSION_IDLE_MS, forgets remembered pages older than LAST_PAGE_MS, and closes
// Chromium itself when no session has existed for BROWSER_IDLE_MS (never, when it is 0, the default).
setInterval(async () => {
  const now = Date.now()
  for (const [id, session] of sessions) {
    if (now - session.lastUsed > SESSION_IDLE_MS) {
      log(`closing idle session ${id}`)
      await closeSession(id)
    }
  }
  for (const [id, last] of lastPages) {
    if (now - last.at > LAST_PAGE_MS) lastPages.delete(id)
  }
  if (BROWSER_IDLE_MS > 0 && browser && sessions.size === 0 && now - browserIdleSince > BROWSER_IDLE_MS) {
    log('closing idle Chromium')
    const closing = browser
    browser = null
    await closing.close().catch(() => {})
  }
}, 15 * 1000).unref()

// ---- What one step answers -------------------------------------------------------------------

/**
 * Describes the page as it stands now: address, title, visible text, links, fillable fields,
 * buttons and a JPEG screenshot. Text and picture are taken together. Lists and text are
 * capped (TEXT_LIMIT, LINK_LIMIT, FIELD_LIMIT, 20 buttons).
 * @param {import('playwright-core').Page} page the run's page.
 * @param {string} [note] a plain-language remark for the agent, added as "note" when present.
 * @returns {Promise<object>} {url, title, text, links, fields, buttons, screenshot (base64), note?}.
 * Failure: never throws; a failed read gives empty text and lists, and a failed screenshot gives
 * an empty string. Does not change the page.
 */
async function snapshot(page, note, from = 0) {
  await page.waitForLoadState('domcontentloaded', { timeout: 5000 }).catch(() => {})
  // The text and the picture are taken at the same time.
  const factsTaken = page
    .evaluate(
      ({ textLimit, linkLimit, fieldLimit, buttonLimit, from }) => {
        const visible = (el) => {
          const box = el.getBoundingClientRect()
          const style = window.getComputedStyle(el)
          return box.width > 0 && box.height > 0 && style.visibility !== 'hidden' && style.display !== 'none'
        }
        // Runs of blank lines and spaces are collapsed: they cost the model time and say nothing.
        const all = (document.body ? document.body.innerText : '')
          .replace(/[ \t]+/g, ' ')
          .replace(/\n\s*\n+/g, '\n')
          .trim()
        const start = Math.max(0, Math.min(Number(from) || 0, all.length))
        const text = all.slice(start, start + textLimit)
        const links = []
        const seen = new Set()
        for (const a of document.querySelectorAll('a[href]')) {
          if (links.length >= linkLimit) break
          const href = a.href
          const label = (a.innerText || a.getAttribute('aria-label') || a.title || '').replace(/\s+/g, ' ').trim()
          if (!label || !/^https?:/i.test(href) || !visible(a)) continue
          // A link on the same site is given by its path alone, which is all the model needs to
          // follow it and a fraction of the length; a duplicate is listed once.
          let shown = href
          try {
            const target = new URL(href)
            if (target.origin === location.origin) shown = target.pathname + target.search
          } catch {}
          const key = label + ' ' + shown
          if (seen.has(key)) continue
          seen.add(key)
          links.push({ text: label.slice(0, 80), href: shown.slice(0, 160) })
        }
        const fields = []
        for (const el of document.querySelectorAll('input, textarea, select')) {
          if (fields.length >= fieldLimit) break
          const type = (el.getAttribute('type') || el.tagName).toLowerCase()
          if (['hidden', 'submit', 'button', 'image', 'reset', 'file'].includes(type) || !visible(el)) continue
          let label = ''
          if (el.labels && el.labels.length) label = el.labels[0].innerText
          if (!label) label = el.getAttribute('aria-label') || ''
          // Only the parts that say something, so an empty placeholder costs nothing.
          const field = { type }
          const cleanLabel = label.replace(/\s+/g, ' ').trim().slice(0, 80)
          if (cleanLabel) field.label = cleanLabel
          const name = el.getAttribute('name') || ''
          if (name) field.name = name.slice(0, 60)
          const placeholder = el.getAttribute('placeholder') || ''
          if (placeholder && placeholder !== cleanLabel) field.placeholder = placeholder.slice(0, 80)
          fields.push(field)
        }
        const buttons = []
        for (const el of document.querySelectorAll('button, input[type=submit], input[type=button], [role=button]')) {
          if (buttons.length >= buttonLimit) break
          const label = (el.innerText || el.value || el.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim()
          if (label && visible(el) && !buttons.includes(label.slice(0, 60))) buttons.push(label.slice(0, 60))
        }
        const more = start + text.length < all.length ? start + text.length : null
        return { title: document.title || '', text, more, links, fields, buttons }
      },
      { textLimit: TEXT_LIMIT, linkLimit: LINK_LIMIT, fieldLimit: FIELD_LIMIT, buttonLimit: BUTTON_LIMIT, from },
    )
    .catch(() => ({ title: '', text: '', more: null, links: [], fields: [], buttons: [] }))
  const imageTaken = page
    .screenshot({ type: 'jpeg', quality: 55, timeout: 8000, animations: 'disabled', caret: 'hide', scale: 'css' })
    .catch(() => null)
  const [facts, image] = await Promise.all([factsTaken, imageTaken])
  const answer = {
    url: page.url(),
    title: facts.title,
    text: facts.text,
    links: facts.links,
    fields: facts.fields,
    buttons: facts.buttons,
    screenshot: image ? image.toString('base64') : '',
  }
  // Where the rest of a long page starts, for read_page's "from".
  if (facts.more !== null && facts.more !== undefined) answer.more = facts.more
  if (note) answer.note = note
  // The newest picture doubles as the live view's frame until the next one is taken.
  if (image) frames.set(page, { jpeg: image, at: Date.now() })
  return answer
}

/** Network failures worth one more try: the connection dropped or the network changed. */
const TRANSIENT = /ERR_CONNECTION_RESET|ERR_CONNECTION_CLOSED|ERR_NETWORK_CHANGED|ERR_EMPTY_RESPONSE|ERR_HTTP2_PROTOCOL_ERROR|ERR_TIMED_OUT|ERR_SOCKET_NOT_CONNECTED/

/**
 * Whether the page already shows more than a little text (over 40 characters).
 * @returns {Promise<boolean>} false on any error. Does not change the page.
 */
async function hasContent(page) {
  return page
    .evaluate(() => Boolean(document.body && document.body.innerText && document.body.innerText.trim().length > 40))
    .catch(() => false)
}

/*
 * Cookie and consent banners cover the page and its text. One is closed by choosing the option that
 * shares the least ("Reject all" and its kin), never "Accept"; nothing else is clicked, and a page
 * without one costs a fraction of a second.
 */
const REJECT_LABEL = /^(reject all|reject|decline all|decline|refuse all|only necessary|necessary only|use necessary cookies only|allow necessary only|continue without accepting)$/i

/**
 * Closes a cookie or consent banner by choosing a reject option, never accept.
 * Flow: for up to six frames, check quickly whether a button with a reject label exists, and if
 * it is visible click it.
 * @param {import('playwright-core').Page} page the run's page.
 * @returns {Promise<string|null>} a note for the agent when a banner was closed, otherwise null.
 * Side effects: at most one click per page. Slow or failing frames are skipped; callers also
 * catch any error.
 */
async function dismissConsent(page) {
  const frames = page.frames().slice(0, 6)
  for (const frame of frames) {
    // A quick look inside the frame first, so a page with no banner costs a few milliseconds.
    const present = await within(
      frame.evaluate((source) => {
        const pattern = new RegExp(source, 'i')
        return [...document.querySelectorAll('button, [role=button], input[type=button], input[type=submit]')].some(
          (el) => pattern.test((el.innerText || el.value || el.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim()),
        )
      }, REJECT_LABEL.source),
      300,
    )
    if (present !== true) continue
    const button = frame.getByRole('button', { name: REJECT_LABEL }).first()
    const visible = await within(button.isVisible(), 400)
    if (visible === true) {
      const clicked = await button.click({ timeout: 1500 }).then(() => true, () => false)
      if (clicked) {
        await within(page.waitForLoadState('domcontentloaded'), 1500)
        return 'A cookie banner was closed by choosing to reject optional cookies.'
      }
    }
  }
  return null
}

/**
 * Opens an address in the run's page after the address guard has approved it.
 * Flow: guard check and DNS resolution; goto with one retry for transient network errors; a
 * slow page that has shown content is read as it stands; wait briefly for late content; refuse
 * if a redirect ended somewhere private; try to close a consent banner.
 * @param {import('playwright-core').Page} page the run's page.
 * @param {string} raw the address requested by the agent.
 * @returns {Promise<string|null>} notes for the agent (slow load, HTTP status of 400 or more,
 * banner closed) joined into one string, or null.
 * Side effects: navigates the page; navigates to about:blank after a private redirect.
 * Failure: throws Refusal 422 (refused, private or unknown address), 504 (timeout with nothing
 * shown) or 502 (any other load failure).
 */
async function navigate(page, raw) {
  const checked = await checkAndResolve(raw)
  if (!checked.ok) throw new Refusal(422, checked.reason)
  const notes = []
  let response = null
  for (let attempt = 1; ; attempt++) {
    try {
      response = await page.goto(checked.url.href, { waitUntil: 'domcontentloaded', timeout: PAGE_TIMEOUT_MS })
      break
    } catch (error) {
      const message = String(error && error.message ? error.message : error)
      if (/ERR_TUNNEL_CONNECTION_FAILED|ERR_BLOCKED|403/.test(message)) throw new Refusal(422, PRIVATE_MESSAGE)
      if (/ERR_NAME_NOT_RESOLVED/.test(message)) {
        throw new Refusal(422, 'The address could not be found. Check that it is spelled correctly.')
      }
      if (/Timeout/i.test(message)) {
        // A slow page that has shown something is read as it stands, rather than failing the step.
        if (checkUrl(page.url()).ok && (await hasContent(page))) {
          notes.push(`The page was still loading after ${Math.round(PAGE_TIMEOUT_MS / 1000)} seconds; this is what had loaded.`)
          break
        }
        throw new Refusal(504, `The page did not finish loading within ${Math.round(PAGE_TIMEOUT_MS / 1000)} seconds.`)
      }
      if (attempt === 1 && TRANSIENT.test(message)) {
        log(`retrying ${checked.url.host} after ${message.split('\n')[0]}`)
        await sleep(500)
        continue
      }
      throw new Refusal(502, 'The page could not be loaded.')
    }
  }
  // Enough for pictures and late content, without waiting on pages that never go quiet.
  await within(page.waitForLoadState('load'), SETTLE_MS)
  await within(page.waitForLoadState('networkidle'), 500)
  // A redirect that led somewhere private was refused by the proxy; say so plainly.
  if (!checkUrl(page.url()).ok) {
    await page.goto('about:blank').catch(() => {})
    throw new Refusal(422, PRIVATE_MESSAGE)
  }
  const consent = await dismissConsent(page).catch(() => null)
  if (consent) notes.push(consent)
  if (response && response.status() >= 400) notes.unshift(`The site answered with status ${response.status()}.`)
  return notes.length ? notes.join(' ') : null
}

/** Whether two strings are equal ignoring case and runs of whitespace. Pure. */
function sameText(a, b) {
  return a.replace(/\s+/g, ' ').trim().toLowerCase() === b.replace(/\s+/g, ' ').trim().toLowerCase()
}

/**
 * Locates a visible form field by its label, name or placeholder (exact matches first, then
 * partial label and placeholder matches).
 * @param {import('playwright-core').Page} page the run's page.
 * @param {string} field the label, name or placeholder given by the agent.
 * @returns {Promise<import('playwright-core').Locator>} the first visible match.
 * Failure: throws Refusal 400 when no field is named, or 404 when nothing visible matches.
 */
async function findField(page, field) {
  const wanted = String(field || '').trim()
  if (!wanted) throw new Refusal(400, 'Say which field to use, by its label, name or placeholder.')
  const candidates = [
    page.getByLabel(wanted, { exact: true }),
    page.locator(`[name="${wanted.replace(/["\\]/g, '')}"]`),
    page.getByPlaceholder(wanted, { exact: true }),
    page.getByLabel(wanted),
    page.getByPlaceholder(wanted),
  ]
  for (const candidate of candidates) {
    const first = candidate.first()
    if ((await first.count()) > 0 && (await first.isVisible().catch(() => false))) return first
  }
  throw new Refusal(404, `No field called "${wanted}" is on the page.`)
}

/**
 * Types into a field, selects an option, or sets a checkbox or radio button. Never submits.
 * @param {import('playwright-core').Page} page the run's page.
 * @param {string} field how to find the field (see findField).
 * @param {*} value the value, converted to text; for a checkbox, true/yes/on/1/checked mean on.
 * Side effects: changes the page's form state.
 * Failure: throws Refusal 400/404 from findField, or 422 when the element cannot be typed into.
 */
async function fill(page, field, value) {
  const target = await findField(page, field)
  const tag = await target.evaluate((el) => el.tagName.toLowerCase())
  const type = ((await target.getAttribute('type')) || '').toLowerCase()
  if (tag === 'select') {
    await target.selectOption({ label: String(value) }).catch(() => target.selectOption(String(value)))
  } else if (type === 'checkbox' || type === 'radio') {
    const on = /^(true|yes|on|1|checked)$/i.test(String(value))
    await target.setChecked(on)
  } else if (['input', 'textarea'].includes(tag)) {
    await target.fill(String(value))
  } else {
    throw new Refusal(422, `"${field}" is not a field that can be typed into.`)
  }
}

/** Puts a page back after a long wait for approval: the same address, and what was typed into it. */
async function restore(session, state) {
  if (!state || !state.url) return
  if (session.page.url() === state.url) return
  await navigate(session.page, state.url)
  for (const entry of Array.isArray(state.fills) ? state.fills : []) {
    await fill(session.page, entry.field, entry.value).catch(() => {})
  }
}

/**
 * Waits, within fixed limits, for a page to finish loading after a click, search or submit.
 * Never throws and never waits for ever: each wait is capped and its failure ignored.
 */
async function settle(page) {
  await page.waitForLoadState('domcontentloaded', { timeout: PAGE_TIMEOUT_MS }).catch(() => {})
  await within(page.waitForLoadState('load'), SETTLE_MS)
  await within(page.waitForLoadState('networkidle'), 500)
}

/**
 * The run's open page. If there is none but the run had one (Chromium restarted, or the page was
 * closed while idle), it is opened again at the same address, so the run carries on.
 */
async function pageFor(id) {
  const session = await sessionFor(id, false)
  if (session) return { session, note: null }
  const last = lastPages.get(id)
  if (!last) throw new Refusal(404, 'No page is open in this run. Open one first with open_page.')
  const reopened = await sessionFor(id, true)
  await navigate(reopened.page, last.url)
  return { session: reopened, note: 'The page was opened again, so anything typed into it before was cleared.' }
}

/**
 * Records the page's address as the run's last page, so pageFor can reopen it after a restart.
 * @param {string} id the run identifier.
 * @param {import('playwright-core').Page} page the run's page.
 * Side effects: updates lastPages; an address the guard would refuse is not stored. Never throws.
 */
function remember(id, page) {
  const url = page.url()
  if (checkUrl(url).ok) lastPages.set(id, { url, at: Date.now() })
}

/*
 * A search, and only a search: types the words into the site's search box and presses Enter. A box
 * inside a form that posts (sends data rather than asking for a results page) is refused, so this
 * can never send or change anything; that needs fill_field and submit_form, which ask first.
 * Used by the search action and by open with a search. Throws Refusal (404 no box, 422 a posting form).
 */
async function searchOn(page, query, field) {
  let box = null
  if (field) {
    box = await findField(page, field)
  } else {
    const candidates = [
      page.getByRole('searchbox'),
      page.locator('input[type=search]'),
      page.locator('input[name=q], input[name=query], input[name=search], input[name=search_query], input[name=s], input[name=k]'),
      page.locator('input[aria-label*="search" i], input[placeholder*="search" i], textarea[aria-label*="search" i]'),
    ]
    for (const candidate of candidates) {
      const first = candidate.first()
      if ((await first.count()) > 0 && (await first.isVisible().catch(() => false))) {
        box = first
        break
      }
    }
  }
  if (!box) throw new Refusal(404, 'No search box is on this page. Open the site\'s search page, or its address with the search in it.')
  const method = await box
    .evaluate((el) => (el.form ? (el.form.getAttribute('method') || 'get').toLowerCase() : 'get'))
    .catch(() => 'get')
  if (method !== 'get') {
    throw new Refusal(422, 'That box is part of a form that sends data, not a search. Use fill_field and submit_form, which ask a person first.')
  }
  const before = page.url()
  await box.fill(query)
  await box.press('Enter')
  // Single-page sites (YouTube and the like) change the address and then draw the results bit by
  // bit, with no page load to wait for: wait for the address to change, then for the text to stop
  // growing, so the answer has the results and not the empty frame around them.
  await within(page.waitForURL((url) => url.href !== before, { timeout: 4000 }), 4000)
  await settle(page)
  await untilTextSettles(page, 3000)
}

/**
 * Waits until the page's visible text has stopped changing (the same length on two checks 250 ms
 * apart) or MAX_MS has passed, whichever is first. Never throws; a page that cannot be read ends
 * the wait at once.
 */
async function untilTextSettles(page, maxMs) {
  const deadline = Date.now() + maxMs
  let last = -1
  while (Date.now() < deadline) {
    const length = await page.evaluate(() => (document.body ? document.body.innerText.length : 0)).catch(() => null)
    if (length === null || length === last) return
    last = length
    await sleep(250)
  }
}

/** Plain words for what an action is doing, shown in the live view while it runs. */
function describe(action, body) {
  const host = (raw) => {
    try {
      return new URL(String(raw)).host.replace(/^www\./, '')
    } catch {
      return String(raw || 'the page')
    }
  }
  switch (action) {
    case 'open':
      return body.search ? `Opening ${host(body.url)} and searching for "${String(body.search).slice(0, 60)}"` : `Opening ${host(body.url)}`
    case 'read':
      return 'Reading the page'
    case 'follow':
      return `Following the link "${String(body.linkText || '').slice(0, 60)}"`
    case 'search':
      return `Searching for "${String(body.query || '').slice(0, 60)}"`
    case 'fill':
      return `Typing into "${String(body.field || '').slice(0, 60)}"`
    case 'click':
      return `Clicking "${String(body.text || '').slice(0, 60)}"`
    case 'submit':
      return 'Submitting the form'
    default:
      return 'Working'
  }
}

/**
 * The live view of one run: what the browser is doing, the page it has open and a fresh frame.
 * A frame younger than LIVE_FRAME_MS is reused, so several people watching cost one picture; a
 * page still loading is captured as it stands. Answers {busy, action, url, title, frame (base64)
 * or ''}, or null when the run has no page open. Never throws.
 */
async function live(id) {
  const session = sessions.get(id)
  const activity = activities.get(id) || null
  if (!session) return activity ? { busy: true, action: activity.label, url: '', title: '', frame: '' } : null
  const page = session.page
  let frame = frames.get(page)
  if (!frame || Date.now() - frame.at > LIVE_FRAME_MS) {
    const jpeg = await page
      .screenshot({ type: 'jpeg', quality: 45, timeout: 1500, animations: 'allow', caret: 'hide', scale: 'css' })
      .catch(() => null)
    if (jpeg) {
      frame = { jpeg, at: Date.now() }
      frames.set(page, frame)
    }
  }
  const title = await within(page.title(), 300)
  return {
    busy: Boolean(activity),
    action: activity ? activity.label : '',
    since: activity ? activity.since : null,
    url: page.url(),
    title: typeof title === 'string' ? title : '',
    frame: frame ? frame.jpeg.toString('base64') : '',
  }
}

// One handler per action in the route. Each takes (run id, parsed JSON body) and returns the
// snapshot object that the HTTP layer sends back as JSON. Failures are thrown as Refusal.
const actions = {
  // open: creates the run's session if needed and opens body.url.
  // With body.search, the site's own search is run straight after, in the same step, which saves
  // the agent a whole turn for "search YouTube for ..." style requests.
  async open(id, body) {
    const session = await sessionFor(id, true)
    const note = await navigate(session.page, body.url)
    const query = String(body.search || '').trim()
    if (query) {
      await searchOn(session.page, query, null)
      return snapshot(session.page, note)
    }
    return snapshot(session.page, note)
  },

  // read: describes the run's current page; reopens it if Chromium restarted.
  async read(id, body) {
    const { session, note } = await pageFor(id)
    return snapshot(session.page, note, Number(body && body.from) || 0)
  },

  // follow: opens the link whose visible text is body.linkText (exact match first, then partial).
  // Refused with 404 when no link matches and 422 when the link is not an http or https address.
  async follow(id, body) {
    const { session } = await pageFor(id)
    const wanted = String(body.linkText || '').trim()
    if (!wanted) throw new Refusal(400, 'Say which link to follow, by its visible text.')
    const links = await session.page.$$eval('a[href]', (anchors) =>
      anchors.map((a) => ({ text: (a.innerText || a.getAttribute('aria-label') || a.title || '').trim(), href: a.href })),
    )
    const link =
      links.find((l) => sameText(l.text, wanted)) ||
      links.find((l) => l.text.toLowerCase().includes(wanted.toLowerCase()))
    if (!link) throw new Refusal(404, `No link with the text "${wanted}" is on the page.`)
    if (!/^https?:/i.test(link.href)) throw new Refusal(422, 'That link does not lead to a web page.')
    const note = await navigate(session.page, link.href)
    return snapshot(session.page, note)
  },

  /*
   * A search, and only a search: types the words into the site's search box and presses Enter. A box
   * inside a form that posts (sends data rather than asking for a results page) is refused, so this
   * can never send or change anything; that needs fill_field and submit_form, which ask first.
   */
  // With body.url and no page open yet (or a different site wanted), that site is opened first,
  // so "search YouTube for ..." works as one step whichever tool the agent reaches for.
  async search(id, body) {
    const query = String(body.query || '').trim()
    if (!query) throw new Refusal(400, 'Say what to search for.')
    let note = null
    let session
    if (body.url) {
      session = await sessionFor(id, true)
      note = await navigate(session.page, body.url)
    } else {
      if (!sessions.has(id) && !lastPages.has(id)) {
        throw new Refusal(404, 'No page is open yet. Give the site\'s address as url (or use open_page with url and search) to open it and search in one step.')
      }
      ;({ session } = await pageFor(id))
    }
    await searchOn(session.page, query, body.field)
    return snapshot(session.page, note)
  },

  // fill: types body.value into the field body.field. Sends nothing; the answer says so.
  async fill(id, body) {
    const { session } = await pageFor(id)
    await fill(session.page, body.field, body.value ?? '')
    return snapshot(session.page, 'Typed into the field. Nothing was sent.')
  },

  // click: clicks the button whose visible text is body.text. When body.restore holds a url (and
  // earlier fills), a page lost during a long wait for approval is put back first. Fails with 404
  // when there is no page or no matching visible button, 400 when no text is given.
  async click(id, body) {
    const session = await sessionFor(id, Boolean(body.restore && body.restore.url))
    if (!session) throw new Refusal(404, 'No page is open in this run. Open one first with open_page.')
    await restore(session, body.restore)
    const wanted = String(body.text || '').trim()
    if (!wanted) throw new Refusal(400, 'Say which button to click, by its visible text.')
    const page = session.page
    const candidates = [
      page.getByRole('button', { name: wanted, exact: true }),
      page.locator(`input[type=submit][value="${wanted.replace(/["\\]/g, '')}"]`),
      page.getByRole('button', { name: wanted }),
    ]
    for (const candidate of candidates) {
      const first = candidate.first()
      if ((await first.count()) > 0 && (await first.isVisible().catch(() => false))) {
        await first.click({ timeout: 10000 })
        await settle(page)
        await untilTextSettles(page, 2000)
        return snapshot(page)
      }
    }
    throw new Refusal(404, `No button with the text "${wanted}" is on the page.`)
  },

  // submit: submits the form that contains body.field, or the first form on the page. Restores a
  // lost page first, as click does. Fails with 404 when there is no page or no form.
  async submit(id, body) {
    const session = await sessionFor(id, Boolean(body.restore && body.restore.url))
    if (!session) throw new Refusal(404, 'No page is open in this run. Open one first with open_page.')
    await restore(session, body.restore)
    const page = session.page
    let form
    if (body.field) {
      const target = await findField(page, body.field)
      form = await target.evaluateHandle((el) => el.form)
    } else {
      form = await page.evaluateHandle(() => document.querySelector('form'))
    }
    const exists = await form.evaluate((f) => Boolean(f)).catch(() => false)
    if (!exists) throw new Refusal(404, 'No form is on the page to submit.')
    await form.evaluate((f) => (typeof f.requestSubmit === 'function' ? f.requestSubmit() : f.submit()))
    await settle(page)
    return snapshot(page)
  },
}

// ---- HTTP ------------------------------------------------------------------------------------

/**
 * Sends a JSON response (or an empty body when body is undefined) with caching switched off.
 * @param {import('node:http').ServerResponse} res
 * @param {number} status the HTTP status.
 * @param {*} [body] anything JSON.stringify accepts.
 */
function send(res, status, body) {
  const text = body === undefined ? '' : JSON.stringify(body)
  res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' })
  res.end(text)
}

/**
 * Checks the X-Runner-Secret header against the configured secret in constant time.
 * @returns {boolean} false when no secret is configured or the header differs. Never throws.
 */
function authorised(req) {
  if (!SECRET) return false
  const given = Buffer.from(String(req.headers['x-runner-secret'] || ''))
  const expected = Buffer.from(SECRET)
  return given.length === expected.length && crypto.timingSafeEqual(given, expected)
}

/**
 * Reads and parses a JSON request body of at most MAX_BODY bytes.
 * @returns {Promise<object>} the parsed body, or {} when it is empty.
 * Failure: rejects with Refusal 413 when too large (the connection is then destroyed) or 400
 * when it is not valid JSON.
 */
function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0
    const chunks = []
    req.on('data', (chunk) => {
      size += chunk.length
      if (size > MAX_BODY) {
        reject(new Refusal(413, 'The request is too large.'))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => {
      if (chunks.length === 0) return resolve({})
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')))
      } catch {
        reject(new Refusal(400, 'The request was not valid JSON.'))
      }
    })
    req.on('error', reject)
  })
}

// Matches /sessions/<run id> and /sessions/<run id>/<action>. The run id is limited to letters,
// digits and hyphens, so it is safe to log and to use as a key.
const ROUTE = /^\/sessions\/([A-Za-z0-9-]{1,80})(?:\/(open|read|follow|search|fill|click|submit|live))?$/

// The HTTP server. GET /health is open (a liveness probe); everything else needs the secret.
// POST /sessions/{id}/{open|read|follow|search|fill|click|submit} runs an action;
// DELETE /sessions/{id} ends the run's session. Answers: 200 with the snapshot, 204 for delete,
// 401 wrong secret, 404 unknown route, 405 wrong method, 503 no secret configured, or the status
// of a Refusal; anything unexpected is a generic 500 with details only in the log.
const server = http.createServer(async (req, res) => {
  const path = (req.url || '').split('?')[0]
  if (req.method === 'GET' && path === '/health') {
    send(res, 200, { ok: true, sessions: sessions.size, browser: Boolean(browser) })
    return
  }
  if (!SECRET) {
    send(res, 503, { error: 'The browser runner has no secret configured, so it refuses every request.' })
    return
  }
  if (!authorised(req)) {
    send(res, 401, { error: 'Not allowed.' })
    return
  }
  const match = ROUTE.exec(path)
  if (!match) {
    send(res, 404, { error: 'Not found.' })
    return
  }
  const [, id, action] = match
  try {
    if (req.method === 'DELETE' && !action) {
      lastPages.delete(id)
      await closeSession(id)
      send(res, 204)
      return
    }
    if (req.method === 'GET' && action === 'live') {
      const view = await live(id)
      if (!view) {
        send(res, 404, { error: 'No page is open in this run.' })
        return
      }
      send(res, 200, view)
      return
    }
    if (req.method !== 'POST' || !action || action === 'live') {
      send(res, 405, { error: 'Not allowed.' })
      return
    }
    const body = await readBody(req)
    const started = Date.now()
    activities.set(id, { label: describe(action, body), since: started })
    let answer
    try {
      answer = await actions[action](id, body)
    } finally {
      activities.delete(id)
    }
    const open = sessions.get(id)
    if (open) remember(id, open.page)
    log(`${action} for ${id} in ${Date.now() - started} ms`)
    send(res, 200, answer)
  } catch (error) {
    if (error instanceof Refusal) {
      send(res, error.status, { error: error.message })
    } else {
      log(`${action || req.method} for ${id} failed: ${error && error.message ? error.message.split('\n')[0] : error}`)
      send(res, 500, { error: 'The web browser could not do that.' })
    }
  }
})

server.requestTimeout = 90 * 1000
server.keepAliveTimeout = 65 * 1000
server.listen(PORT, HOST, () => {
  log(`Browser runner listening on ${HOST}:${PORT}${SECRET ? '' : ' (no secret set: every request is refused)'}`)
  // Started now rather than on the first step, so nobody waits for Chromium to start.
  if (SECRET) ensureBrowser().catch((error) => log(`Chromium did not start ahead of use: ${String(error && error.message).split('\n')[0]}`))
})

/**
 * Stops the server, closes every session, Chromium and the proxy, then exits with status 0.
 * Run on SIGTERM and SIGINT. Errors while closing are ignored.
 */
async function shutdown() {
  server.close()
  for (const id of [...sessions.keys()]) await closeSession(id)
  if (browser) await browser.close().catch(() => {})
  if (proxy) await proxy.close().catch(() => {})
  process.exit(0)
}
process.on('SIGTERM', shutdown)
// One failed page must never take the runner down with it.
process.on('unhandledRejection', (error) => log(`unhandled: ${String(error && error.message ? error.message : error).split('\n')[0]}`))
process.on('SIGINT', shutdown)
