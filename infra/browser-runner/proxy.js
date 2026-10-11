// @find: browser runner filtering proxy, egress filter, every chromium request checked, dns rebinding protection, CONNECT tunnel, private address block for sub-requests
// @what: A small forward proxy Chromium sends every request through: it resolves each host itself, refuses any private or loopback address and connects only to the address it checked.
// @flow: Started by server.js on 127.0.0.1; Chromium is launched with --proxy-server pointing at it; uses guard.js
'use strict'

const http = require('node:http')
const net = require('node:net')
const { resolvePublic } = require('./guard')

/*
 * Ad and tracking hosts are refused outright: they are most of the requests on a heavy page and
 * none of them changes what the agent reads, so pages load sooner and use less memory. Matched on
 * the host and every parent domain.
 */
const BLOCKED_HOSTS = new Set([
  'doubleclick.net',
  'googlesyndication.com',
  'googleadservices.com',
  'google-analytics.com',
  'googletagmanager.com',
  'googletagservices.com',
  'adservice.google.com',
  'connect.facebook.net',
  'scorecardresearch.com',
  'hotjar.com',
  'hotjar.io',
  'segment.io',
  'segment.com',
  'mixpanel.com',
  'amplitude.com',
  'criteo.com',
  'criteo.net',
  'taboola.com',
  'outbrain.com',
  'adnxs.com',
  'rubiconproject.com',
  'pubmatic.com',
  'quantserve.com',
  'moatads.com',
  'amazon-adsystem.com',
  'ads-twitter.com',
  'analytics.tiktok.com',
  'bat.bing.com',
  'clarity.ms',
  'newrelic.com',
  'nr-data.net',
])

/**
 * Whether a host, or any parent domain of it, is on the ad and tracking list.
 * @param {string} host a host name; case and one trailing dot are ignored.
 * @returns {boolean} true when the request should be refused. Pure; never throws.
 */
function isBlockedHost(host) {
  const name = String(host || '').toLowerCase().replace(/\.$/, '')
  const parts = name.split('.')
  for (let index = 0; index < parts.length - 1; index++) {
    if (BLOCKED_HOSTS.has(parts.slice(index).join('.'))) return true
  }
  return false
}

/*
 * Answers for the same host are kept for a minute (a refusal for ten seconds), so a page that asks
 * one host for fifty files is resolved once. Safe against DNS rebinding: the proxy still dials only
 * the address it checked.
 */
const ANSWER_MS = 60 * 1000
const REFUSAL_MS = 10 * 1000
const CACHE_LIMIT = 500

/**
 * Wraps resolvePublic in a small in-memory cache.
 * @param {Function} [lookup] a dns.lookup compatible function, replaceable in tests.
 * @returns {function(string): Promise<object>} resolves a host to the guard's {ok, address} or
 *   {ok: false, reason} answer. The cache holds at most CACHE_LIMIT hosts and drops the oldest
 *   entry when full. Side effects: DNS queries on a miss; never throws.
 */
function cachedResolver(lookup) {
  const cache = new Map()
  return async (host) => {
    const key = String(host).toLowerCase()
    const hit = cache.get(key)
    if (hit && hit.expires > Date.now()) return hit.answer
    const answer = await resolvePublic(key, lookup)
    if (cache.size >= CACHE_LIMIT) cache.delete(cache.keys().next().value)
    cache.set(key, { answer, expires: Date.now() + (answer.ok ? ANSWER_MS : REFUSAL_MS) })
    return answer
  }
}

/**
 * Starts the proxy and resolves to {port, close}. Because the proxy resolves the name and dials
 * the IP it checked, a name that answers a public address to a check and a private one a moment
 * later (DNS rebinding) cannot slip through: Chromium never resolves names itself.
 *
 * Flow: plain http requests are rebuilt as an upstream request to the checked address (headers
 * for proxying are removed, 20 s timeout); CONNECT requests become a raw tunnel to the checked
 * address and port (30 s idle timeout). Ad and tracking hosts and non-public addresses get 403.
 * Only http requests and CONNECT are accepted; the proxy listens on 127.0.0.1 and a free port.
 * @param {{lookup?: Function, onRefused?: function(string): void}} [options] lookup replaces
 *   dns.lookup in tests; onRefused is called with the host name of every address refused by the
 *   guard (not for the ad list).
 * @returns {Promise<{port: number, close: function(): Promise<void>}>} the chosen port and a
 *   function that stops the server.
 * Failure: a malformed request gets 400, an upstream error or timeout 502; no error reaches the
 *   caller after the proxy has started.
 */
function startProxy({ lookup, onRefused = () => {} } = {}) {
  const resolveHost = cachedResolver(lookup)
  const server = http.createServer(async (req, res) => {
    // Plain http: the request line carries the absolute address.
    let target
    try {
      target = new URL(req.url)
    } catch {
      res.writeHead(400).end()
      return
    }
    if (target.protocol !== 'http:') {
      res.writeHead(403).end()
      return
    }
    if (isBlockedHost(target.hostname)) {
      res.writeHead(403).end()
      return
    }
    const resolved = await resolveHost(target.hostname.replace(/^\[|\]$/g, ''))
    if (!resolved.ok) {
      onRefused(target.hostname)
      res.writeHead(403, { 'content-type': 'text/plain' }).end('Refused by the browser runner.')
      return
    }
    const headers = { ...req.headers }
    delete headers['proxy-connection']
    delete headers['proxy-authorization']
    const upstream = http.request(
      {
        host: resolved.address,
        port: target.port || 80,
        method: req.method,
        path: target.pathname + target.search,
        headers: { ...headers, host: target.host },
        timeout: 20000,
      },
      (answer) => {
        res.writeHead(answer.statusCode || 502, answer.headers)
        answer.pipe(res)
      },
    )
    upstream.on('timeout', () => upstream.destroy(new Error('timeout')))
    upstream.on('error', () => {
      if (!res.headersSent) res.writeHead(502)
      res.end()
    })
    req.pipe(upstream)
  })

  // https and websockets: a CONNECT tunnel to host:port, opened only to the checked address.
  server.on('connect', async (req, socket, head) => {
    socket.on('error', () => {})
    const match = /^\[?([^\]]+?)\]?:(\d{1,5})$/.exec(req.url || '')
    if (!match) {
      socket.end('HTTP/1.1 400 Bad Request\r\n\r\n')
      return
    }
    const [, host, port] = match
    if (isBlockedHost(host)) {
      socket.end('HTTP/1.1 403 Forbidden\r\n\r\n')
      return
    }
    const resolved = await resolveHost(host)
    if (!resolved.ok) {
      onRefused(host)
      socket.end('HTTP/1.1 403 Forbidden\r\n\r\n')
      return
    }
    const upstream = net.connect({ host: resolved.address, port: Number(port) }, () => {
      socket.write('HTTP/1.1 200 Connection Established\r\n\r\n')
      if (head && head.length) upstream.write(head)
      upstream.pipe(socket)
      socket.pipe(upstream)
    })
    upstream.setTimeout(30000, () => upstream.destroy())
    upstream.on('error', () => socket.end('HTTP/1.1 502 Bad Gateway\r\n\r\n'))
    socket.on('close', () => upstream.destroy())
  })

  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      resolve({ port: server.address().port, close: () => new Promise((done) => server.close(done)) })
    })
  })
}

module.exports = { startProxy, isBlockedHost }
