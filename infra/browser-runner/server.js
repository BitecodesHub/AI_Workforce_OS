// @find: browser runner, headless chromium, playwright, browser sidecar, open page, read page, follow link, fill field, click button, submit form, screenshot, browser session per run, idle timeout, shared secret, POST /sessions/{id}/open, DELETE /sessions/{id}, GET /health
// @what: The browser runner: a small HTTP server that drives one shared headless Chromium, one browser context per run, and answers each step with the page as text plus a JPEG screenshot.
// @flow: Called by mcp-core BrowserAdapter (orchestrator) with the X-Runner-Secret header; every Chromium request goes through proxy.js, which applies guard.js
'use strict'

const http = require('node:http')
const crypto = require('node:crypto')
const { chromium } = require('playwright-core')
const { checkAndResolve, checkUrl, PRIVATE_MESSAGE } = require('./guard')
const { startProxy } = require('./proxy')

const PORT = Number(process.env.BROWSER_RUNNER_PORT || 8090)
const HOST = process.env.BROWSER_RUNNER_HOST || '0.0.0.0'
const SECRET = process.env.BROWSER_RUNNER_SECRET || ''
const MAX_SESSIONS = Number(process.env.BROWSER_RUNNER_MAX_SESSIONS || 2)
const SESSION_IDLE_MS = Number(process.env.BROWSER_RUNNER_SESSION_IDLE_MS || 2 * 60 * 1000)
const BROWSER_IDLE_MS = Number(process.env.BROWSER_RUNNER_BROWSER_IDLE_MS || 10 * 60 * 1000)
// A run that finds every page in use waits this long for one to free up, instead of being refused.
const QUEUE_WAIT_MS = Number(process.env.BROWSER_RUNNER_QUEUE_WAIT_MS || 15 * 1000)
// After the page's own content has arrived, at most this long for its pictures and late content.
const SETTLE_MS = 2500
const PAGE_TIMEOUT_MS = Number(process.env.BROWSER_RUNNER_PAGE_TIMEOUT_MS || 20 * 1000)
const TEXT_LIMIT = 8000
const LINK_LIMIT = 40
const FIELD_LIMIT = 40
const VIEWPORT = { width: 1280, height: 800 }
const MAX_BODY = 64 * 1024

let browser = null
let launching = null
let proxy = null
let browserIdleSince = Date.now()
const sessions = new Map()
// Per run, the last address it had open: if Chromium restarts mid-run, the page is opened again.
const lastPages = new Map()
const LAST_PAGE_MS = 30 * 60 * 1000

class Refusal extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

function log(message) {
  process.stdout.write(`${new Date().toISOString()} ${message}\n`)
}

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
]

async function launchOnce() {
  return chromium.launch({
    headless: true,
    proxy: { server: `http://127.0.0.1:${proxy.port}` },
    args: LAUNCH_ARGS,
  })
}

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

async function closeSession(id) {
  const session = sessions.get(id)
  if (!session) return false
  sessions.delete(id)
  if (sessions.size === 0) browserIdleSince = Date.now()
  await session.context.close().catch(() => {})
  return true
}

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
  if (browser && sessions.size === 0 && now - browserIdleSince > BROWSER_IDLE_MS) {
    log('closing idle Chromium')
    const closing = browser
    browser = null
    await closing.close().catch(() => {})
  }
}, 15 * 1000).unref()

// ---- What one step answers -------------------------------------------------------------------

async function snapshot(page, note) {
  await page.waitForLoadState('domcontentloaded', { timeout: 5000 }).catch(() => {})
  // The text and the picture are taken at the same time.
  const factsTaken = page
    .evaluate(
      ({ textLimit, linkLimit, fieldLimit }) => {
        const visible = (el) => {
          const box = el.getBoundingClientRect()
          const style = window.getComputedStyle(el)
          return box.width > 0 && box.height > 0 && style.visibility !== 'hidden' && style.display !== 'none'
        }
        const text = (document.body ? document.body.innerText : '').replace(/\n{3,}/g, '\n\n').trim()
        const links = []
        for (const a of document.querySelectorAll('a[href]')) {
          if (links.length >= linkLimit) break
          const href = a.href
          const label = (a.innerText || a.getAttribute('aria-label') || a.title || '').replace(/\s+/g, ' ').trim()
          if (!label || !/^https?:/i.test(href) || !visible(a)) continue
          links.push({ text: label.slice(0, 120), href })
        }
        const fields = []
        for (const el of document.querySelectorAll('input, textarea, select')) {
          if (fields.length >= fieldLimit) break
          const type = (el.getAttribute('type') || el.tagName).toLowerCase()
          if (['hidden', 'submit', 'button', 'image', 'reset', 'file'].includes(type) || !visible(el)) continue
          let label = ''
          if (el.labels && el.labels.length) label = el.labels[0].innerText
          if (!label) label = el.getAttribute('aria-label') || ''
          fields.push({
            label: label.replace(/\s+/g, ' ').trim().slice(0, 120),
            name: el.getAttribute('name') || '',
            type,
            placeholder: el.getAttribute('placeholder') || '',
          })
        }
        const buttons = []
        for (const el of document.querySelectorAll('button, input[type=submit], input[type=button], [role=button]')) {
          if (buttons.length >= 20) break
          const label = (el.innerText || el.value || el.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim()
          if (label && visible(el)) buttons.push(label.slice(0, 80))
        }
        return { title: document.title || '', text: text.slice(0, textLimit), links, fields, buttons }
      },
      { textLimit: TEXT_LIMIT, linkLimit: LINK_LIMIT, fieldLimit: FIELD_LIMIT },
    )
    .catch(() => ({ title: '', text: '', links: [], fields: [], buttons: [] }))
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
  if (note) answer.note = note
  return answer
}

/** Network failures worth one more try: the connection dropped or the network changed. */
const TRANSIENT = /ERR_CONNECTION_RESET|ERR_CONNECTION_CLOSED|ERR_NETWORK_CHANGED|ERR_EMPTY_RESPONSE|ERR_HTTP2_PROTOCOL_ERROR|ERR_TIMED_OUT|ERR_SOCKET_NOT_CONNECTED/

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

function sameText(a, b) {
  return a.replace(/\s+/g, ' ').trim().toLowerCase() === b.replace(/\s+/g, ' ').trim().toLowerCase()
}

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

function remember(id, page) {
  const url = page.url()
  if (checkUrl(url).ok) lastPages.set(id, { url, at: Date.now() })
}

const actions = {
  async open(id, body) {
    const session = await sessionFor(id, true)
    const note = await navigate(session.page, body.url)
    return snapshot(session.page, note)
  },

  async read(id) {
    const { session, note } = await pageFor(id)
    return snapshot(session.page, note)
  },

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
  async search(id, body) {
    const { session } = await pageFor(id)
    const page = session.page
    const query = String(body.query || '').trim()
    if (!query) throw new Refusal(400, 'Say what to search for.')
    let box = null
    if (body.field) {
      box = await findField(page, body.field)
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
    await box.fill(query)
    await box.press('Enter')
    await settle(page)
    return snapshot(page)
  },

  async fill(id, body) {
    const { session } = await pageFor(id)
    await fill(session.page, body.field, body.value ?? '')
    return snapshot(session.page, 'Typed into the field. Nothing was sent.')
  },

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
        return snapshot(page)
      }
    }
    throw new Refusal(404, `No button with the text "${wanted}" is on the page.`)
  },

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

function send(res, status, body) {
  const text = body === undefined ? '' : JSON.stringify(body)
  res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' })
  res.end(text)
}

function authorised(req) {
  if (!SECRET) return false
  const given = Buffer.from(String(req.headers['x-runner-secret'] || ''))
  const expected = Buffer.from(SECRET)
  return given.length === expected.length && crypto.timingSafeEqual(given, expected)
}

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

const ROUTE = /^\/sessions\/([A-Za-z0-9-]{1,80})(?:\/(open|read|follow|search|fill|click|submit))?$/

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
    if (req.method !== 'POST' || !action) {
      send(res, 405, { error: 'Not allowed.' })
      return
    }
    const body = await readBody(req)
    const started = Date.now()
    const answer = await actions[action](id, body)
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
})

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
