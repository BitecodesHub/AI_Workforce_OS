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
const BROWSER_IDLE_MS = Number(process.env.BROWSER_RUNNER_BROWSER_IDLE_MS || 5 * 60 * 1000)
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

class Refusal extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

function log(message) {
  process.stdout.write(`${new Date().toISOString()} ${message}\n`)
}

// ---- Browser and sessions ---------------------------------------------------------------------

async function ensureBrowser() {
  if (browser && browser.isConnected()) return browser
  if (launching) return launching
  launching = (async () => {
    if (!proxy) proxy = await startProxy({ onRefused: (host) => log(`refused a request to ${host}`) })
    const launched = await chromium.launch({
      headless: true,
      proxy: { server: `http://127.0.0.1:${proxy.port}` },
      args: [
        // Loopback is normally sent around a proxy; this sends it through, where it is refused.
        '--proxy-bypass-list=<-loopback>',
        '--force-webrtc-ip-handling-policy=disable_non_proxied_udp',
        '--disable-dev-shm-usage',
        '--no-first-run',
        '--disable-background-networking',
        '--disable-extensions',
        '--disable-sync',
        '--mute-audio',
      ],
    })
    launched.on('disconnected', () => {
      browser = null
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
  if (sessions.size >= MAX_SESSIONS) {
    // The longest idle page gives way if it has been left for a while; otherwise the runner is busy.
    const oldest = [...sessions.entries()].sort((a, b) => a[1].lastUsed - b[1].lastUsed)[0]
    if (oldest && Date.now() - oldest[1].lastUsed > 30 * 1000) {
      await closeSession(oldest[0])
    } else {
      throw new Refusal(429, 'The web browser is busy with other runs. Try again in a minute.')
    }
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
  const facts = await page
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
  const image = await page.screenshot({ type: 'jpeg', quality: 60, timeout: 10000 }).catch(() => null)
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

async function navigate(page, raw) {
  const checked = await checkAndResolve(raw)
  if (!checked.ok) throw new Refusal(422, checked.reason)
  try {
    const response = await page.goto(checked.url.href, { waitUntil: 'domcontentloaded', timeout: PAGE_TIMEOUT_MS })
    await page.waitForLoadState('load', { timeout: 5000 }).catch(() => {})
    // A redirect that led somewhere private was refused by the proxy; say so plainly.
    if (!checkUrl(page.url()).ok) {
      await page.goto('about:blank').catch(() => {})
      throw new Refusal(422, PRIVATE_MESSAGE)
    }
    if (response && response.status() >= 400) return `The site answered with status ${response.status()}.`
    return null
  } catch (error) {
    if (error instanceof Refusal) throw error
    const message = String(error && error.message ? error.message : error)
    if (/ERR_TUNNEL_CONNECTION_FAILED|ERR_BLOCKED|403/.test(message)) throw new Refusal(422, PRIVATE_MESSAGE)
    if (/Timeout/i.test(message)) throw new Refusal(504, 'The page did not finish loading within 20 seconds.')
    if (/ERR_NAME_NOT_RESOLVED/.test(message)) {
      throw new Refusal(422, 'The address could not be found. Check that it is spelled correctly.')
    }
    throw new Refusal(502, 'The page could not be loaded.')
  }
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
  await page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => {})
}

const actions = {
  async open(id, body) {
    const session = await sessionFor(id, true)
    const note = await navigate(session.page, body.url)
    return snapshot(session.page, note)
  },

  async read(id) {
    const session = await sessionFor(id, false)
    if (!session) throw new Refusal(404, 'No page is open in this run. Open one first with open_page.')
    return snapshot(session.page)
  },

  async follow(id, body) {
    const session = await sessionFor(id, false)
    if (!session) throw new Refusal(404, 'No page is open in this run. Open one first with open_page.')
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

  async fill(id, body) {
    const session = await sessionFor(id, false)
    if (!session) throw new Refusal(404, 'No page is open in this run. Open one first with open_page.')
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

const ROUTE = /^\/sessions\/([A-Za-z0-9-]{1,80})(?:\/(open|read|follow|fill|click|submit))?$/

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

server.requestTimeout = 60 * 1000
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
process.on('SIGINT', shutdown)
