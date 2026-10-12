// @find: tests for browser runner takeover routes, POST /sessions/{id}/takeover/{action}, hold page, shared secret, private redirect refused, navigate guard wired, page busy
// @what: Node tests for the runner's takeover HTTP routes in server.js, against stand-in pages: the secret, the method, holding a page, a click that ends somewhere private, navigate through the server's own address guard, and a busy page.
'use strict'

// The secret is read when server.js loads, so it is set first. Nothing here starts Chromium.
process.env.BROWSER_RUNNER_SECRET = 'test-secret'

const test = require('node:test')
const assert = require('node:assert/strict')
const { PRIVATE_MESSAGE } = require('./guard')
const { server, sessions, activities, useLookup } = require('./server')

const ID = 'run-0001'
let base = ''

/** A stand-in page at URL; AFTER_CLICK is where a click takes it. Records goto calls. */
function standInPage(url, afterClick = url) {
  const page = {
    current: url,
    gotos: [],
    typed: [],
    url: () => page.current,
    title: async () => 'Example',
    screenshot: async () => Buffer.from('jpeg'),
    waitForLoadState: async () => {},
    goto: async (to) => {
      page.gotos.push(to)
      page.current = to
      return null
    },
    mouse: {
      move: async () => {},
      click: async () => {
        page.current = afterClick
      },
      wheel: async () => {},
    },
    keyboard: {
      type: async (text) => page.typed.push(text),
      press: async () => {},
    },
    evaluate: async () => ({ label: 'Email' }),
  }
  return page
}

/** Puts a stand-in session for ID in the runner, as an open page would be. */
function openSession(page) {
  sessions.set(ID, { id: ID, context: { close: async () => {} }, page, lastUsed: Date.now(), heldUntil: 0 })
}

/** One request to the runner; answers {status, body}. */
async function call(path, { method = 'POST', body, secret = 'test-secret' } = {}) {
  const headers = { 'content-type': 'application/json' }
  if (secret) headers['x-runner-secret'] = secret
  const response = await fetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await response.text()
  return { status: response.status, body: text ? JSON.parse(text) : null }
}

test.before(async () => {
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  base = `http://127.0.0.1:${server.address().port}`
})

test.after(async () => {
  sessions.clear()
  await new Promise((resolve) => server.close(resolve))
})

test.beforeEach(() => {
  sessions.clear()
  activities.clear()
  useLookup(undefined)
})

test('the takeover routes need the secret and a POST', async () => {
  openSession(standInPage('https://example.com/'))
  assert.equal((await call(`/sessions/${ID}/takeover/click`, { body: {}, secret: '' })).status, 401)
  assert.equal((await call(`/sessions/${ID}/takeover/click`, { body: {}, secret: 'wrong-secret' })).status, 401)
  assert.equal((await call(`/sessions/${ID}/takeover/click`, { method: 'GET' })).status, 405)
  assert.equal((await call(`/sessions/${ID}/takeover/submit`, { body: {} })).status, 404)
})

test('holding a page: refused for a run with no page, letting go of one is fine, and a held page is marked', async () => {
  const missing = await call(`/sessions/${ID}/takeover/hold`, { body: { on: true } })
  assert.equal(missing.status, 404)
  const letGo = await call(`/sessions/${ID}/takeover/hold`, { body: { on: false } })
  assert.equal(letGo.status, 200)
  assert.deepEqual(letGo.body, { held: false })

  openSession(standInPage('https://example.com/'))
  const held = await call(`/sessions/${ID}/takeover/hold`, { body: { on: true } })
  assert.equal(held.status, 200)
  assert.ok(sessions.get(ID).heldUntil > Date.now())
  await call(`/sessions/${ID}/takeover/hold`, { body: { on: false } })
  assert.equal(sessions.get(ID).heldUntil, 0)
})

test('a click that leads somewhere private is refused and the page is left blank', async () => {
  const page = standInPage('https://example.com/', 'http://10.0.0.5/admin')
  openSession(page)

  const answer = await call(`/sessions/${ID}/takeover/click`, { body: { x: 10, y: 10, width: 1280, height: 800 } })

  assert.equal(answer.status, 422)
  assert.equal(answer.body.error, PRIVATE_MESSAGE)
  assert.deepEqual(page.gotos, ['about:blank'])
})

test('navigate goes through the server\'s own address guard, with the name looked up', async () => {
  useLookup(async (host) =>
    host === 'inside.example.com' ? [{ address: '10.0.0.5', family: 4 }] : [{ address: '93.184.215.14', family: 4 }],
  )
  const page = standInPage('https://example.com/')
  openSession(page)

  const refused = await call(`/sessions/${ID}/takeover/navigate`, { body: { url: 'https://inside.example.com/admin' } })
  assert.equal(refused.status, 422)
  assert.equal(refused.body.error, PRIVATE_MESSAGE)
  assert.deepEqual(page.gotos, [])

  const opened = await call(`/sessions/${ID}/takeover/navigate`, { body: { url: 'https://example.com/prices' } })
  assert.equal(opened.status, 200)
  assert.equal(opened.body.url, 'https://example.com/prices')
  assert.equal(opened.body.frame, Buffer.from('jpeg').toString('base64'))
})

test('typing answers the field label and never the text; a page busy with a step refuses a person\'s action', async () => {
  const page = standInPage('https://example.com/sign-in')
  openSession(page)

  const typed = await call(`/sessions/${ID}/takeover/type`, { body: { text: 'hunter2-correct' } })
  assert.equal(typed.status, 200)
  assert.equal(typed.body.field, 'Email')
  assert.equal(JSON.stringify(typed.body).includes('hunter2'), false)
  assert.deepEqual(page.typed, ['hunter2-correct'])

  activities.set(ID, { label: 'Opening example.com', since: Date.now() })
  const busy = await call(`/sessions/${ID}/takeover/key`, { body: { key: 'Enter' } })
  assert.equal(busy.status, 409)
  assert.match(busy.body.error, /still busy/)
})
