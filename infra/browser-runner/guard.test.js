// @find: tests for browser runner address guard, SSRF tests, private address refused, metadata address refused, ipv6 mapped, dns rebinding names
// @what: Node tests for guard.js: which addresses and URLs the browser may and may not reach.
'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { isRefusedAddress, checkUrl, resolvePublic, checkAndResolve, PRIVATE_MESSAGE } = require('./guard')

test('private, loopback, link-local, metadata and reserved IPv4 addresses are refused', () => {
  for (const ip of [
    '127.0.0.1', '127.1.2.3', '10.0.0.1', '172.16.0.1', '172.31.255.255', '192.168.1.1', '169.254.169.254',
    '100.64.0.1', '0.0.0.0', '224.0.0.1', '255.255.255.255', '198.18.0.1', '192.0.0.10',
  ]) {
    assert.equal(isRefusedAddress(ip), true, ip)
  }
  for (const ip of ['93.184.215.14', '8.8.8.8', '172.32.0.1', '1.1.1.1', '100.128.0.1']) {
    assert.equal(isRefusedAddress(ip), false, ip)
  }
})

test('IPv6 loopback, unique local, link-local, mapped and NAT64 addresses are refused', () => {
  for (const ip of [
    '::1', '::', 'fd00:ec2::254', 'fc00::1', 'fe80::1', '::ffff:127.0.0.1', '::ffff:10.0.0.1', '64:ff9b::a00:1',
    'ff02::1', '2001:db8::1', '::ffff:7f00:1',
  ]) {
    assert.equal(isRefusedAddress(ip), true, ip)
  }
  assert.equal(isRefusedAddress('2606:2800:21f:cb07:6820:80da:af6b:8b2c'), false)
  assert.equal(isRefusedAddress('::ffff:93.184.215.14'), false)
  assert.equal(isRefusedAddress('not-an-ip'), true)
})

test('only http and https addresses without credentials pass the syntax check', () => {
  assert.equal(checkUrl('https://example.com/a?b=1').ok, true)
  assert.equal(checkUrl('http://example.com').ok, true)
  assert.match(checkUrl('file:///etc/passwd').reason, /Only web pages/)
  assert.match(checkUrl('ftp://example.com').reason, /Only web pages/)
  assert.match(checkUrl('javascript:alert(1)').reason, /Only web pages/)
  assert.match(checkUrl('https://user:pw@example.com').reason, /user name/)
  assert.match(checkUrl('not a url').reason, /not a valid/)
  for (const url of [
    'http://localhost:8083/', 'http://api.localhost/', 'http://metadata.google.internal/', 'http://printer.local/',
    'http://127.0.0.1/', 'http://[::1]/', 'http://169.254.169.254/latest/meta-data/', 'http://2130706433/',
    'http://0x7f.1/', 'http://[::ffff:127.0.0.1]/',
  ]) {
    assert.equal(checkUrl(url).reason, PRIVATE_MESSAGE, url)
  }
})

test('a name is refused when any address it resolves to is private, and unknown names are reported', async () => {
  const lookup = async (host) => {
    if (host === 'rebind.example.com') return [{ address: '93.184.215.14', family: 4 }, { address: '10.0.0.5', family: 4 }]
    if (host === 'example.com') return [{ address: '93.184.215.14', family: 4 }]
    if (host === 'inside.example.com') return [{ address: '169.254.169.254', family: 4 }]
    throw Object.assign(new Error('ENOTFOUND'), { code: 'ENOTFOUND' })
  }
  assert.deepEqual(await resolvePublic('example.com', lookup), { ok: true, address: '93.184.215.14' })
  assert.equal((await resolvePublic('rebind.example.com', lookup)).reason, PRIVATE_MESSAGE)
  assert.equal((await resolvePublic('inside.example.com', lookup)).reason, PRIVATE_MESSAGE)
  assert.match((await resolvePublic('nowhere.example.com', lookup)).reason, /could not be found/)
  assert.equal((await checkAndResolve('https://inside.example.com/x', lookup)).reason, PRIVATE_MESSAGE)
  assert.equal((await checkAndResolve('https://example.com/x', lookup)).ok, true)
})
