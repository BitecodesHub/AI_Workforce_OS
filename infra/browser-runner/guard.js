// @find: browser runner address guard, SSRF, private network block, loopback, link-local, cloud metadata address, ip ranges, dns check, http https only, refused address
// @what: Decides whether the browser may reach an address: http or https only, and never a private, loopback, link-local, metadata or otherwise non-public IP, checked on every resolved address.
// @flow: Used by server.js for each page the agent opens and by the filtering proxy every Chromium request goes through; mirrors WebhookAdapter and BrowserAddresses in mcp-core
'use strict'

const net = require('node:net')
const dns = require('node:dns').promises

// Shown to the agent for every refusal caused by a non-public address, so a refusal never
// reveals which check failed or what the name resolved to.
const PRIVATE_MESSAGE =
  'That address points inside a private network or at this server, which the browser may not open.'

/**
 * Splits a dotted IPv4 address into numbers.
 * @param {string} ip dotted address, for example "10.0.0.1".
 * @returns {number[]} the four parts; a malformed input yields NaN entries. Pure; never throws.
 */
function v4Parts(ip) {
  return ip.split('.').map((part) => Number(part))
}

/**
 * Whether an IPv4 address (dotted) is outside the public internet.
 * @param {string} ip a dotted IPv4 address already known to be valid.
 * @returns {boolean} true when the address is in a private, loopback, link-local, shared,
 *   documentation, benchmarking, multicast or reserved range. Pure; never throws.
 */
function refusedV4(ip) {
  const [a, b, c] = v4Parts(ip)
  return (
    a === 0 || // this network
    a === 10 ||
    a === 127 ||
    (a === 100 && b >= 64 && b <= 127) || // carrier-grade NAT
    (a === 169 && b === 254) || // link-local, cloud metadata
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    (a === 192 && b === 0 && c === 0) || // protocol assignments
    (a === 192 && b === 0 && c === 2) || // documentation
    (a === 198 && (b === 18 || b === 19)) || // benchmarking
    (a === 198 && b === 51 && c === 100) ||
    (a === 203 && b === 0 && c === 113) ||
    a >= 224 // multicast, reserved, broadcast
  )
}

/**
 * Expands an IPv6 address to eight 16-bit groups.
 * @param {string} ip an IPv6 address, possibly compressed with "::", with a zone suffix
 *   (removed) or with a trailing dotted IPv4 part (converted to two groups).
 * @returns {number[]} the groups; a malformed input can give fewer than eight or NaN entries,
 *   which refusedV6 treats as refused. Pure; never throws.
 */
function v6Groups(ip) {
  let address = ip.toLowerCase()
  const zone = address.indexOf('%')
  if (zone >= 0) address = address.slice(0, zone)
  let tail = []
  const lastColon = address.lastIndexOf(':')
  const last = address.slice(lastColon + 1)
  if (last.includes('.')) {
    const [a, b, c, d] = v4Parts(last)
    tail = [(a << 8) | b, (c << 8) | d]
    address = address.slice(0, lastColon + 1) + '0'
  }
  const [head, rest] = address.includes('::') ? address.split('::') : [address, null]
  const left = head ? head.split(':').filter((g) => g !== '') : []
  const right = rest ? rest.split(':').filter((g) => g !== '') : []
  const groups = [...left, ...right].map((g) => parseInt(g, 16))
  if (tail.length) groups.splice(groups.length - 1, 1, ...tail)
  if (rest !== null) {
    const missing = 8 - groups.length
    const zeros = new Array(Math.max(missing, 0)).fill(0)
    const leftCount = left.length
    return [...groups.slice(0, leftCount), ...zeros, ...groups.slice(leftCount)]
  }
  return groups
}

/**
 * Whether an IPv6 address is outside the public internet, including IPv4 hidden inside one.
 * @param {string} ip an IPv6 address.
 * @returns {boolean} true for unparsable input, for IPv4-mapped addresses whose IPv4 part is
 *   refused, and for loopback, NAT64, unique-local, link-local, site-local, multicast and
 *   documentation ranges. Pure; never throws.
 */
function refusedV6(ip) {
  const g = v6Groups(ip)
  if (g.length !== 8 || g.some((x) => Number.isNaN(x))) return true
  const allZeroPrefix = g.slice(0, 5).every((x) => x === 0)
  if (allZeroPrefix && g[5] === 0xffff) {
    // IPv4-mapped ::ffff:a.b.c.d
    return refusedV4(`${g[6] >> 8}.${g[6] & 255}.${g[7] >> 8}.${g[7] & 255}`)
  }
  if (g.slice(0, 6).every((x) => x === 0)) return true // ::, ::1 and IPv4-compatible
  if (g[0] === 0x64 && g[1] === 0xff9b) return true // NAT64
  if ((g[0] & 0xfe00) === 0xfc00) return true // unique local fc00::/7 (fd00:ec2::254 metadata)
  if ((g[0] & 0xffc0) === 0xfe80) return true // link-local
  if ((g[0] & 0xffc0) === 0xfec0) return true // site-local
  if ((g[0] & 0xff00) === 0xff00) return true // multicast
  if (g[0] === 0x2001 && g[1] === 0x0db8) return true // documentation
  return false
}

/**
 * Whether the browser must not reach this IP address.
 * @param {string} ip the text to test.
 * @returns {boolean} the IPv4 or IPv6 verdict; text that is not an IP address counts as
 *   refused, so the check fails closed. Pure; never throws.
 */
function isRefusedAddress(ip) {
  const family = net.isIP(ip)
  if (family === 4) return refusedV4(ip)
  if (family === 6) return refusedV6(ip)
  return true
}

// Host names that mean the local machine or a private network, refused before any lookup.
const REFUSED_NAMES = /(^|\.)(localhost|local|internal|home\.arpa)$/i

/**
 * Checks an address without looking anything up. Answers {ok: true, url, host} or {ok: false, reason}.
 *
 * Flow: parse, then require http or https, no embedded user name or password, a non-empty host,
 * a host name that is not a local or internal one, and an IP literal that is public.
 * @param {*} raw the address as given by the agent; converted to text and trimmed.
 * @returns {{ok: boolean, url?: URL, host?: string, reason?: string}} the parsed address, or a
 *   reason written for the agent. Pure; never throws.
 */
function checkUrl(raw) {
  let url
  try {
    url = new URL(String(raw || '').trim())
  } catch {
    return { ok: false, reason: 'That is not a valid web address.' }
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return { ok: false, reason: 'Only web pages (http or https addresses) can be opened.' }
  }
  if (url.username || url.password) {
    return { ok: false, reason: 'Addresses with a user name or password in them are not opened.' }
  }
  const host = url.hostname.replace(/^\[|\]$/g, '')
  if (!host) return { ok: false, reason: 'Give the full web address of the page to open, starting with https://.' }
  if (REFUSED_NAMES.test(host)) return { ok: false, reason: PRIVATE_MESSAGE }
  if (net.isIP(host) && isRefusedAddress(host)) return { ok: false, reason: PRIVATE_MESSAGE }
  return { ok: true, url, host }
}

/**
 * Resolves a host name and answers the first acceptable address, or a refusal. Every address the
 * name resolves to must be public, so a name with one private record is refused outright.
 *
 * Flow: refuse local names, test an IP literal directly, otherwise look the name up and test every
 * answer. An IPv4 answer is preferred, because the proxy and Chromium both dial that address.
 * @param {string} host a host name or IP literal without brackets.
 * @param {Function} [lookup] a dns.lookup compatible function, replaceable in tests.
 * @returns {Promise<{ok: boolean, address?: string, reason?: string}>} the address to dial, or a
 *   refusal. A failed or empty lookup is returned as a refusal, never thrown.
 * Side effects: a DNS query.
 */
async function resolvePublic(host, lookup = dns.lookup) {
  if (REFUSED_NAMES.test(host)) return { ok: false, reason: PRIVATE_MESSAGE }
  if (net.isIP(host)) {
    return isRefusedAddress(host) ? { ok: false, reason: PRIVATE_MESSAGE } : { ok: true, address: host }
  }
  let answers
  try {
    answers = await lookup(host, { all: true, verbatim: true })
  } catch {
    return { ok: false, reason: 'The address could not be found. Check that it is spelled correctly.' }
  }
  if (!answers || answers.length === 0) {
    return { ok: false, reason: 'The address could not be found. Check that it is spelled correctly.' }
  }
  if (answers.some((answer) => isRefusedAddress(answer.address))) {
    return { ok: false, reason: PRIVATE_MESSAGE }
  }
  const v4 = answers.find((answer) => answer.family === 4)
  return { ok: true, address: (v4 || answers[0]).address }
}

/**
 * The whole check for an address the agent asked for: syntax, then every resolved address.
 * @param {*} raw the address as given by the agent.
 * @param {Function} [lookup] a dns.lookup compatible function, replaceable in tests.
 * @returns {Promise<{ok: boolean, url?: URL, address?: string, reason?: string}>} the parsed
 *   address and the checked IP to dial, or the first refusal. Never throws.
 * Side effects: a DNS query.
 */
async function checkAndResolve(raw, lookup = dns.lookup) {
  const checked = checkUrl(raw)
  if (!checked.ok) return checked
  const resolved = await resolvePublic(checked.host, lookup)
  if (!resolved.ok) return resolved
  return { ok: true, url: checked.url, address: resolved.address }
}

module.exports = { PRIVATE_MESSAGE, isRefusedAddress, checkUrl, resolvePublic, checkAndResolve }
