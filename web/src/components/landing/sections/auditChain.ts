// @find: audit chain model, hash chain, FNV-1a, record, tamper, verify, genesis, tamper evident log
// @what: Small synchronous hash-chained audit log model for the demo.
// @flow: Used by AuditChainDemo
/*
 * A small, synchronous model of a hash-chained audit log, for the audit demo.
 *
 * Each recorded entry stores the hash of the entry before it, and its own hash covers that
 * previous hash. Change the text of one entry and two things stop matching: that entry no longer
 * hashes to the value recorded for it, and the next entry's stored previous hash no longer equals
 * what the altered entry now hashes to.
 *
 * The real audit log uses a cryptographic hash. FNV-1a here is only illustrative: it is short
 * enough to read on screen and fast enough to compute during render. It is not a security
 * primitive, and the demo says so.
 */

export type AuditEntry = { seq: number; time: string; text: string }

export type Recorded = AuditEntry & { prev: string; hash: string }

export type Verification = { recomputed: string; altered: boolean; prevMismatch: boolean }

export const GENESIS = '00000000'

export const ENTRIES: ReadonlyArray<AuditEntry> = [
  { seq: 1041, time: '09:14:02', text: "HR agent's run completed: welcome email drafted" },
  { seq: 1042, time: '09:14:40', text: 'A manager approved gmail.send_message' },
  { seq: 1043, time: '09:14:41', text: "HR agent's run completed: welcome email sent" },
  { seq: 1044, time: '09:15:10', text: "Research agent's run failed: no supporting document" },
]

const FNV_OFFSET_BASIS = 0x811c9dc5
const FNV_PRIME = 0x01000193

function utf8(input: string): ArrayLike<number> {
  if (typeof TextEncoder === 'function') return new TextEncoder().encode(input)
  // Only reached without TextEncoder; every entry above is plain ASCII.
  return Array.from(input, (character) => character.charCodeAt(0) & 0xff)
}

/** 32-bit FNV-1a over the UTF-8 bytes of the input, as 8 lowercase hex characters. */
// @find: fnv1a32, illustrative hash
export function fnv1a32(input: string): string {
  const bytes = utf8(input)
  let hash = FNV_OFFSET_BASIS
  for (let index = 0; index < bytes.length; index += 1) {
    hash ^= bytes[index] ?? 0
    hash = Math.imul(hash, FNV_PRIME) >>> 0
  }
  return (hash >>> 0).toString(16).padStart(8, '0')
}

function entryHash(prev: string, entry: AuditEntry): string {
  return fnv1a32(`${prev}|${entry.seq}|${entry.text}`)
}

/** Writes the chain: each entry records the previous hash and its own hash over it. */
// @find: record, hash chain entries
export function record(entries: ReadonlyArray<AuditEntry>): Recorded[] {
  const chain: Recorded[] = []
  let prev = GENESIS
  for (const entry of entries) {
    const hash = entryHash(prev, entry)
    chain.push({ ...entry, prev, hash })
    prev = hash
  }
  return chain
}

/** The edit the demo makes: the second entry's approval is rewritten as a rejection. */
// @find: tamper, edit second audit entry
export function tamper(entries: ReadonlyArray<AuditEntry>): AuditEntry[] {
  return entries.map((entry, index) =>
    index === 1 ? { ...entry, text: entry.text.replace('approved', 'rejected') } : { ...entry },
  )
}

/**
 * Re-checks the current entries against what was recorded.
 *
 * altered: the entry, rehashed over its recorded previous hash, no longer matches its recorded
 * hash. prevMismatch: the entry's recorded previous hash no longer matches what the entry before
 * it now hashes to.
 */
// @find: verify, check hash chain
export function verify(recorded: ReadonlyArray<Recorded>, current: ReadonlyArray<AuditEntry>): Verification[] {
  const results: Verification[] = []
  recorded.forEach((stored, index) => {
    const entry = current[index] ?? stored
    const recomputed = entryHash(stored.prev, entry)
    const before = results[index - 1]
    results.push({
      recomputed,
      altered: recomputed !== stored.hash,
      prevMismatch: before !== undefined && stored.prev !== before.recomputed,
    })
  })
  return results
}
