// @find: audit chain demo, audit log, tamper evident, hash chain, tamper toggle, verify chain, record of every decision, AuditChainDemo, #audit
// @what: The demo where the visitor edits one audit entry and watches the hash chain expose it.
// @flow: Used by DemoStage; model in auditChain.ts
import { useMemo, useState } from 'react'
import type { ReactElement, ReactNode } from 'react'
import { Button, Tag } from '../../ui'
import { DemoFrame } from '../shared/DemoFrame'
import { Icon } from '../shared/Icon'
import { ENTRIES, record, tamper, verify } from './auditChain'
import type { AuditEntry, Recorded, Verification } from './auditChain'

/*
 * The audit chain demo: four recorded entries, each carrying the hash of the one before it, and
 * one toggle that rewrites the second entry so the visitor can watch the chain expose the edit.
 *
 * There is no sequence and no effect. The recorded chain is computed once; the current entries,
 * their verification and the status sentence are all derived during render from one boolean.
 * The status stays empty until the visitor first uses the toggle, so the live region only ever
 * speaks in answer to them.
 */

/** Status sentence while the chain verifies. */
const UNTAMPERED_STATUS = 'Every entry records the hash of the entry before it. The chain verifies.'

/** The status sentence for the current check. When an entry's stored previous hash no longer equals what the entry before it hashes to now, it names both entries and the new hash; otherwise, when an entry no longer matches its own recorded hash, it names that entry; otherwise the verified sentence. Each ends by saying the alteration is detectable. */
function statusFor(recorded: ReadonlyArray<Recorded>, results: ReadonlyArray<Verification>): string {
  const breakIndex = results.findIndex((result) => result.prevMismatch)
  const stored = recorded[breakIndex]
  const before = results[breakIndex - 1]
  if (breakIndex > 0 && stored && before) {
    return (
      `Entry ${breakIndex + 1} records the previous hash ${stored.prev}, ` +
      `but entry ${breakIndex} now hashes to ${before.recomputed}. The alteration is detectable.`
    )
  }
  const alteredIndex = results.findIndex((result) => result.altered)
  if (alteredIndex >= 0) {
    return `Entry ${alteredIndex + 1} no longer matches its recorded hash. The alteration is detectable.`
  }
  return UNTAMPERED_STATUS
}

/** Shows a one-word rewrite as a deletion and an insertion, with the rest of the text as it was. */
/** Shows an edit as a deletion and an insertion. A one-word change keeps the rest of the text as it was; any other change shows the whole original struck through and the whole new text inserted. Hidden text ("originally", "now") makes the change readable by assistive technology, and the insertion is keyed so it animates on each change. */
function diffText(original: string, current: string): ReactNode {
  const before = original.split(' ')
  const after = current.split(' ')
  const sameShape = before.length === after.length
  const changed = sameShape ? before.findIndex((word, index) => word !== after[index]) : -1
  const onlyOne = changed >= 0 && before.every((word, index) => index === changed || word === after[index])

  if (!onlyOne) {
    return (
      <>
        <del>
          <span className="visually-hidden">originally </span>
          {original}
        </del>{' '}
        <ins key={current} className="lp-anim-tick">
          <span className="visually-hidden">, now </span>
          {current}
        </ins>
      </>
    )
  }

  const head = before.slice(0, changed).join(' ')
  const tail = before.slice(changed + 1).join(' ')
  return (
    <>
      {head && `${head} `}
      <del>
        <span className="visually-hidden">originally </span>
        {before[changed]}
      </del>{' '}
      <ins key={after[changed]} className="lp-anim-tick">
        <span className="visually-hidden">, now </span>
        {after[changed]}
      </ins>
      {tail && ` ${tail}`}
    </>
  )
}

/** The text of one audit entry: the plain text, or the diff against the stored text when it was altered. */
function EntryText({ stored, entry, altered }: { stored: AuditEntry; entry: AuditEntry; altered: boolean }) {
  return <p className="lp-audit-text">{altered ? diffText(stored.text, entry.text) : entry.text}</p>
}

// @find: AuditChainDemo component, audit chain demo
/**
 * Landing demo 03: the visitor presses one toggle ("Alter entry 2") to rewrite the second audit entry, and the hash chain exposes the edit.
 * There is no sequence and no effect. The recorded chain is computed once (record), and the current entries, the verification (verify) and the status sentence are all derived during render from one boolean (altered). The status stays empty until the toggle is first used, so the live region only speaks in answer to the visitor. An altered entry shows its recomputed hash and an Altered tag; the next entry shows a broken link and a "Chain broken here" tag. A caveat explains that chaining makes an edit detectable but does not stop someone who can rewrite every later entry. All hashes are illustrative and computed in the browser. Rendered by DemoStage inside DemoFrame.
 */
export function AuditChainDemo(): ReactElement {
  const [altered, setAltered] = useState(false)
  const [touched, setTouched] = useState(false)

  const recorded = useMemo(() => record(ENTRIES), [])
  const tampered = useMemo(() => tamper(ENTRIES), [])
  const current = altered ? tampered : ENTRIES
  const results = useMemo(() => verify(recorded, current), [recorded, current])
  const status = touched ? statusFor(recorded, results) : ''

  function toggle() {
    setAltered((value) => !value)
    setTouched(true)
  }

  return (
    <DemoFrame
      area="audit"
      index="03"
      name="Audit chain"
      title="It records decisions and outcomes"
      lead="Every approval decision and how a run finished is recorded against the person accountable. Each entry carries the previous entry hash, so an altered entry is detectable."
      status={status}
      footnote="Illustrative short hashes computed in your browser, to show how chaining exposes an edit."
      steps={[
        'Read how each entry carries the hash of the one before it.',
        'Press Alter entry 2.',
        'Watch the chain break at the entry that changed.',
      ]}
    >
      <ol className="lp-audit-list">
        {recorded.map((stored, index) => {
          const check = results[index]
          const entry = current[index] ?? stored
          const isAltered = check?.altered ?? false
          const isBroken = check?.prevMismatch ?? false
          const shownHash = isAltered && check ? check.recomputed : stored.hash
          return (
            <li
              key={stored.seq}
              className="lp-audit-entry lp-inset"
              {...(isAltered ? { 'data-altered': 'true' } : {})}
              {...(isBroken ? { 'data-broken': 'true' } : {})}
            >
              {index > 0 && (
                <span
                  className="lp-audit-link"
                  aria-hidden="true"
                  {...(isBroken ? { 'data-broken': 'true' } : {})}
                >
                  <Icon name={isBroken ? 'link-broken' : 'link'} />
                </span>
              )}
              <div className="lp-audit-top">
                <p className="lp-audit-meta">
                  <span>seq {stored.seq}</span>
                  <span>{stored.time}</span>
                </p>
                <p className="lp-audit-chips">
                  <span className="lp-audit-chip" {...(isBroken ? { 'data-bad': 'true' } : {})}>
                    prev {stored.prev}
                  </span>
                  <span className="lp-audit-chip" {...(isAltered ? { 'data-bad': 'true' } : {})}>
                    hash{' '}
                    <span key={shownHash} className="lp-anim-tick">
                      {shownHash}
                    </span>
                  </span>
                </p>
              </div>
              <div className="lp-audit-body">
                <EntryText stored={stored} entry={entry} altered={isAltered} />
                {isAltered && <Tag tone="warning">Altered</Tag>}
                {isBroken && <Tag tone="danger">Chain broken here</Tag>}
              </div>
            </li>
          )
        })}
      </ol>

      <div className="lp-demo-actions">
        <Button
          variant="outline"
          className="lp-audit-toggle"
          icon={<Icon name="pencil" />}
          aria-pressed={altered}
          onClick={toggle}
        >
          Alter entry 2
        </Button>
      </div>
      <p className="caption lp-audit-caveat" data-shown={altered}>
        Chaining makes an edit detectable. It does not by itself stop someone who can rewrite every later entry.
      </p>
    </DemoFrame>
  )
}
