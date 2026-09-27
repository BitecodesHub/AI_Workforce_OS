import { Fragment } from 'react'
import type { ReactElement } from 'react'

/*
 * The exact payload an approval would release, pretty-printed as JSON.
 *
 * Keys and values go through JSON.stringify, so what is shown is what would be serialised, quotes
 * and escapes included. Braces are string expressions: written bare they would be JSX.
 */

export type PayloadViewProps = { payload: Readonly<Record<string, string>>; label: string }

export function PayloadView({ payload, label }: PayloadViewProps): ReactElement {
  const entries = Object.entries(payload)
  const last = entries.length - 1
  return (
    // role="group" gives the label something it is allowed to name; a bare pre is generic.
    <pre className="lp-code lp-run-payload" role="group" aria-label={label}>
      <span className="lp-run-json-punc">{'{'}</span>
      {'\n'}
      {entries.map(([key, value], index) => (
        <Fragment key={key}>
          {'  '}
          <span className="lp-run-json-key">{JSON.stringify(key)}</span>
          <span className="lp-run-json-punc">{': '}</span>
          <span className="lp-run-json-str">{JSON.stringify(value)}</span>
          {index < last && <span className="lp-run-json-punc">{','}</span>}
          {'\n'}
        </Fragment>
      ))}
      <span className="lp-run-json-punc">{'}'}</span>
    </pre>
  )
}
