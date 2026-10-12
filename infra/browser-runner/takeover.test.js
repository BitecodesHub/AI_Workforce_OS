// @find: tests for browser runner takeover, person takes over the browser, click scaling, type into focused field, key whitelist, scroll limits, navigate guard, private address refused, typed text never returned
// @what: Node tests for takeover.js: each person action's checks, the click scaled from frame to viewport, typing reporting the field label (also inside shadow roots and frames) and never the text, and navigate refused for private addresses.
'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { PRIVATE_MESSAGE, checkAndResolve } = require('./guard')
const { KEYS, TEXT_LIMIT, scalePoint, validateTakeover, performTakeover, focusedLabelIn, focusedFieldLabel } = require('./takeover')

const VIEWPORT = { width: 1280, height: 800 }

/** A stand-in page that records what was done to it; FOCUSED is the focused field's label, or null for none. */
function fakePage(focused) {
  const done = []
  return {
    done,
    mouse: {
      move: async (x, y) => done.push(['move', x, y]),
      click: async (x, y) => done.push(['click', x, y]),
      wheel: async (dx, dy) => done.push(['wheel', dx, dy]),
    },
    keyboard: {
      type: async (text) => done.push(['type', text]),
      press: async (key) => done.push(['press', key]),
    },
    evaluate: async () => (typeof focused === 'string' ? { label: focused } : null),
  }
}

/** A stand-in form field: an input (or TAG) with a label element LABEL and attributes ATTRS. */
function field(label, attrs = {}, tag = 'INPUT') {
  return {
    tagName: tag,
    labels: label ? [{ innerText: label }] : [],
    isContentEditable: false,
    getAttribute: (name) => (name in attrs ? attrs[name] : null),
  }
}

/** A stand-in document whose focus is on ACTIVE; HAS_FOCUS answers document.hasFocus(). */
function doc(active, hasFocus = true) {
  const body = { tagName: 'BODY', getAttribute: () => null }
  return { body, activeElement: active === 'body' ? body : active, hasFocus: () => hasFocus }
}

const deps = (navigate) => ({ navigate: navigate || (async () => {}), settle: async () => {}, viewport: VIEWPORT })

test('a click is checked against the frame and scaled to the viewport', () => {
  assert.deepEqual(scalePoint({ x: 320, y: 200, width: 640, height: 400 }, VIEWPORT), { x: 640, y: 400 })
  assert.deepEqual(scalePoint({ x: 640, y: 400, width: 640, height: 400 }, VIEWPORT), { x: 1279, y: 799 })
  const ok = validateTakeover('click', { x: 10, y: 20, width: 1280, height: 800 }, VIEWPORT)
  assert.equal(ok.ok, true)
  assert.deepEqual(ok.value, { x: 10, y: 20 })
  for (const body of [
    {},
    { x: '10', y: 20, width: 1280, height: 800 },
    { x: 10, y: 20, width: 0, height: 800 },
    { x: 10, y: 20, width: 20000, height: 800 },
    { x: -1, y: 20, width: 1280, height: 800 },
    { x: 10, y: 900, width: 1280, height: 800 },
    { x: Number.NaN, y: 1, width: 1280, height: 800 },
  ]) {
    const refused = validateTakeover('click', body, VIEWPORT)
    assert.equal(refused.ok, false, JSON.stringify(body))
    assert.equal(refused.status, 400)
  }
})

test('typing, keys, scrolling and holding are checked before anything is done', () => {
  assert.equal(validateTakeover('type', { text: 'hello' }, VIEWPORT).ok, true)
  assert.equal(validateTakeover('type', { text: '' }, VIEWPORT).ok, false)
  assert.equal(validateTakeover('type', { text: 42 }, VIEWPORT).ok, false)
  assert.equal(validateTakeover('type', { text: 'x'.repeat(TEXT_LIMIT + 1) }, VIEWPORT).ok, false)
  for (const key of ['Enter', 'Tab', 'Escape', 'Backspace', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight']) {
    assert.equal(KEYS.has(key), true, key)
    assert.equal(validateTakeover('key', { key }, VIEWPORT).ok, true, key)
  }
  for (const key of ['Control+a', 'Meta+w', 'F12', 'a', '', null]) {
    assert.equal(validateTakeover('key', { key }, VIEWPORT).ok, false, String(key))
  }
  assert.deepEqual(validateTakeover('scroll', { dy: -600 }, VIEWPORT), { ok: true, value: { dy: -600 } })
  for (const dy of [0, 1.5, 6000, -6000, '100', undefined]) {
    assert.equal(validateTakeover('scroll', { dy }, VIEWPORT).ok, false, String(dy))
  }
  assert.deepEqual(validateTakeover('hold', { on: true }, VIEWPORT), { ok: true, value: { on: true } })
  assert.equal(validateTakeover('hold', { on: 'yes' }, VIEWPORT).ok, false)
  assert.equal(validateTakeover('submit', {}, VIEWPORT).status, 404)
  assert.equal(validateTakeover('click', null, VIEWPORT).ok, false)
})

test('navigate refuses what open refuses: other schemes, credentials, local names and private addresses', () => {
  assert.equal(validateTakeover('navigate', { url: 'https://example.com/a' }, VIEWPORT).ok, true)
  assert.match(validateTakeover('navigate', { url: 'file:///etc/passwd' }, VIEWPORT).message, /Only web pages/)
  assert.match(validateTakeover('navigate', { url: 'javascript:alert(1)' }, VIEWPORT).message, /Only web pages/)
  assert.match(validateTakeover('navigate', { url: 'https://user:pw@example.com' }, VIEWPORT).message, /user name/)
  for (const url of ['http://localhost:8083/', 'http://169.254.169.254/latest/meta-data/', 'http://10.0.0.1/', 'http://[::1]/']) {
    const refused = validateTakeover('navigate', { url }, VIEWPORT)
    assert.equal(refused.ok, false, url)
    assert.equal(refused.status, 422, url)
    assert.equal(refused.message, PRIVATE_MESSAGE, url)
  }
  assert.equal(validateTakeover('navigate', { url: '' }, VIEWPORT).status, 400)
  assert.equal(validateTakeover('navigate', { url: 'https://example.com/' + 'a'.repeat(3000) }, VIEWPORT).status, 400)
})

test('a name that resolves inside the network is refused when the page is opened', async () => {
  const lookup = async (host) => {
    if (host === 'inside.example.com') return [{ address: '10.0.0.5', family: 4 }]
    return [{ address: '93.184.215.14', family: 4 }]
  }
  const opened = []
  // The same guard open uses: the name is looked up and every address must be public.
  const navigate = async (page, url) => {
    const checked = await checkAndResolve(url, lookup)
    if (!checked.ok) throw Object.assign(new Error(checked.reason), { status: 422 })
    opened.push(url)
  }
  const value = validateTakeover('navigate', { url: 'https://inside.example.com/admin' }, VIEWPORT).value
  await assert.rejects(performTakeover(fakePage(null), 'navigate', value, deps(navigate)), { message: PRIVATE_MESSAGE })
  await performTakeover(fakePage(null), 'navigate', { url: 'https://example.com/' }, deps(navigate))
  assert.deepEqual(opened, ['https://example.com/'])
})

test('typing reports the focused field by its label and never returns what was typed', async () => {
  const page = fakePage('Password')
  const answer = await performTakeover(page, 'type', { text: 'correct horse battery staple' }, deps())
  assert.deepEqual(answer, { ok: true, field: 'Password' })
  assert.equal(JSON.stringify(answer).includes('correct horse'), false)
  assert.deepEqual(page.done, [['type', 'correct horse battery staple']])

  const unnamed = await performTakeover(fakePage(''), 'type', { text: 'x' }, deps())
  assert.equal(unnamed.field, 'an unnamed field')

  const nothingFocused = fakePage(null)
  const refused = await performTakeover(nothingFocused, 'type', { text: 'secret' }, deps())
  assert.deepEqual(refused, { ok: false, status: 409, message: 'Click the field to type into first.' })
  assert.deepEqual(nothingFocused.done, [])
})

test('click, key and scroll act on the page where and how they were asked', async () => {
  const page = fakePage(null)
  await performTakeover(page, 'click', { x: 640, y: 400 }, deps())
  await performTakeover(page, 'key', { key: 'Enter' }, deps())
  await performTakeover(page, 'scroll', { dy: 600 }, deps())
  assert.deepEqual(page.done, [
    ['move', 640, 400],
    ['click', 640, 400],
    ['press', 'Enter'],
    ['move', 640, 400],
    ['wheel', 0, 600],
  ])
})

test('the focused field is found inside open shadow roots and frames from the same site', () => {
  assert.deepEqual(focusedLabelIn({}, doc(field('Email'))), { label: 'Email' })
  assert.deepEqual(focusedLabelIn({}, doc(field('', { 'aria-label': 'Search the shop' }))), { label: 'Search the shop' })
  const host = { tagName: 'PAY-FORM', getAttribute: () => null, shadowRoot: { activeElement: field('Card number') } }
  assert.deepEqual(focusedLabelIn({}, doc(host)), { label: 'Card number' })
  const sameSite = { tagName: 'IFRAME', getAttribute: () => null, contentDocument: doc(field('', { placeholder: 'Password' })) }
  assert.deepEqual(focusedLabelIn({}, doc(sameSite)), { label: 'Password' })
  // A shadow root inside a frame, as sign-in widgets often are.
  const nested = { tagName: 'IFRAME', getAttribute: () => null, contentDocument: doc(host) }
  assert.deepEqual(focusedLabelIn({}, doc(nested)), { label: 'Card number' })
  const otherSite = { tagName: 'IFRAME', getAttribute: () => null, contentDocument: null }
  assert.deepEqual(focusedLabelIn({}, doc(otherSite)), { frame: true })
  assert.equal(focusedLabelIn({}, doc('body')), null)
  assert.equal(focusedLabelIn({}, doc(field('Go', { type: 'submit' }))), null)
  assert.equal(focusedLabelIn({}, doc(field('Agree', {}, 'BUTTON'))), null)
  assert.equal(focusedLabelIn({ requireFocus: true }, doc(field('Email'), false)), null)
  assert.equal(focusedLabelIn({}, doc(field('x'.repeat(200)))).label.length, 80)
})

test('typing into a field inside another site\'s frame finds it by focus, or types into it as an unnamed field', async () => {
  const otherSite = { tagName: 'IFRAME', getAttribute: () => null, contentDocument: null }
  const main = { evaluate: async (fn, arg) => fn(arg, doc(otherSite)) }
  const unfocused = { evaluate: async (fn, arg) => fn(arg, doc(field('Old field'), false)) }
  const focused = { evaluate: async (fn, arg) => fn(arg, doc(field('Name on card'))) }
  const page = fakePage(null)
  page.evaluate = main.evaluate
  page.mainFrame = () => main
  page.frames = () => [main, unfocused, focused]
  assert.equal(await focusedFieldLabel(page), 'Name on card')
  const typed = await performTakeover(page, 'type', { text: 'J Smith' }, deps())
  assert.deepEqual(typed, { ok: true, field: 'Name on card' })

  page.frames = () => [main, unfocused]
  assert.equal(await focusedFieldLabel(page), '')
  const unnamed = await performTakeover(page, 'type', { text: 'J Smith' }, deps())
  assert.deepEqual(unnamed, { ok: true, field: 'an unnamed field' })
  assert.equal(JSON.stringify([typed, unnamed]).includes('J Smith'), false)
})
