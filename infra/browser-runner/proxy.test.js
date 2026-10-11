// @find: tests for browser runner proxy, CONNECT to private address refused, http to loopback refused, public address tunnelled
// @what: Node tests for proxy.js: private destinations are refused before any connection is made, public ones are tunnelled.
'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const net = require('node:net')
const { startProxy } = require('./proxy')

function rawRequest(port, text) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1', () => socket.write(text))
    let data = ''
    socket.on('data', (chunk) => {
      data += chunk
      if (data.includes('\r\n\r\n')) {
        socket.destroy()
        resolve(data.split('\r\n')[0])
      }
    })
    socket.on('error', reject)
  })
}

test('CONNECT and plain requests to private or loopback addresses are refused', async () => {
  const refused = []
  const lookup = async (host) => {
    if (host === 'sneaky.example.com') return [{ address: '127.0.0.1', family: 4 }]
    throw new Error('ENOTFOUND')
  }
  const proxy = await startProxy({ lookup, onRefused: (host) => refused.push(host) })
  try {
    assert.match(await rawRequest(proxy.port, 'CONNECT 169.254.169.254:80 HTTP/1.1\r\nHost: x\r\n\r\n'), / 403 /)
    assert.match(await rawRequest(proxy.port, 'CONNECT sneaky.example.com:443 HTTP/1.1\r\nHost: x\r\n\r\n'), / 403 /)
    assert.match(await rawRequest(proxy.port, 'CONNECT [::1]:443 HTTP/1.1\r\nHost: x\r\n\r\n'), / 403 /)
    assert.match(
      await rawRequest(proxy.port, 'GET http://10.0.0.1/ HTTP/1.1\r\nHost: 10.0.0.1\r\n\r\n'),
      / 403 /,
    )
    assert.deepEqual(refused, ['169.254.169.254', 'sneaky.example.com', '::1', '10.0.0.1'])
  } finally {
    await proxy.close()
  }
})
