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

const UNTAMPERED_STATUS = 'Every entry records the hash of the entry before it. The chain verifies.'

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

function EntryText({ stored, entry, altered }: { stored: AuditEntry; entry: AuditEntry; altered: boolean }) {
  return <p className="lp-audit-text">{altered ? diffText(stored.text, entry.text) : entry.text}</p>
}

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
