// @find: payload view, exact payload, what will be sent, pretty printed JSON, email payload, approval card payload, PayloadView
// @what: Prints the exact payload an approval would release as JSON.
// @flow: Used by ApprovalDemo
import { Fragment } from 'react'
import type { ReactElement } from 'react'

/*
 * The exact payload an approval would release, pretty-printed as JSON.
 *
 * Keys and values go through JSON.stringify, so what is shown is what would be serialised, quotes
 * and escapes included. Braces are string expressions: written bare they would be JSX.
 */

/** Props: the payload (string keys and values) and the accessible label of the block. */
export type PayloadViewProps = { payload: Readonly<Record<string, string>>; label: string }

// @find: PayloadView component, payload JSON
/** Pretty-prints a payload as JSON inside a pre block with role "group" (so the label has something to name). Keys and values pass through JSON.stringify, so what is shown is what would be serialised, and the braces are string expressions because bare braces would be JSX. Static, no state. Used by ApprovalDemo for the exact payload an approval would release. */
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
