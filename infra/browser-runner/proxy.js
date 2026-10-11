// @find: browser runner filtering proxy, egress filter, every chromium request checked, dns rebinding protection, CONNECT tunnel, private address block for sub-requests
// @what: A small forward proxy Chromium sends every request through: it resolves each host itself, refuses any private or loopback address and connects only to the address it checked.
// @flow: Started by server.js on 127.0.0.1; Chromium is launched with --proxy-server pointing at it; uses guard.js
'use strict'

const http = require('node:http')
const net = require('node:net')
const { resolvePublic } = require('./guard')

/**
 * Starts the proxy and resolves to {port, close}. Because the proxy resolves the name and dials
 * the IP it checked, a name that answers a public address to a check and a private one a moment
 * later (DNS rebinding) cannot slip through: Chromium never resolves names itself.
 */
function startProxy({ lookup, onRefused = () => {} } = {}) {
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
    const resolved = await resolvePublic(target.hostname.replace(/^\[|\]$/g, ''), lookup)
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
    const resolved = await resolvePublic(host, lookup)
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

module.exports = { startProxy }
