// @find: approval preview, what will be sent, email preview, message preview, payload, recipients, cc, bcc, action class, exactly what will be sent, approval details
// @what: Renders an approval's payload the way a person should read it: an email as an email, a message as a message.
// @flow: Used by ApprovalCard, InlineApproval and BulkDecision.
import { useState } from 'react'
import type { CSSProperties } from 'react'
import { Collapsible } from '../ui/Collapsible'
import { formatPayload, parsePayload, previewCaption } from '../../lib/approvals'
import type { PayloadRow } from '../../lib/approvals'

/*
 * A request as the person approving it should read it: the email as an email, the message as a
 * message, every field a labelled row.
 *
 * It never leaves a field out. The block promises "exactly what will be sent", and a preview that
 * shows only the fields it knows would hide a bcc or an attachment from the one person whose job
 * is to see them. So every top-level field is a row, the ones most requests carry first, and
 * whatever else there is comes under "Other details". Text is shown as text, with its line breaks
 * and never as markup: an email body may well contain HTML, and this never renders it. The request
 * exactly as the model wrote it stays one click away.
 */

const ROW: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'minmax(5rem, 9rem) minmax(0, 1fr)',
  gap: 'var(--space-1) var(--space-4)',
  margin: 0,
}

const VALUE: CSSProperties = {
  margin: 0,
  whiteSpace: 'pre-wrap',
  overflowWrap: 'anywhere',
}

function Rows({ rows }: { rows: PayloadRow[] }) {
  return (
    <dl className="stack" style={{ gap: 'var(--space-3)', margin: 0 }}>
      {rows.map((row) => (
        <div key={row.key} style={ROW}>
          <dt className="caption muted">{row.label}</dt>
          <dd style={VALUE}>{row.value === '' ? <span className="muted">(empty)</span> : row.value}</dd>
        </div>
      ))}
    </dl>
  )
}

// @find: PayloadPreview, payload preview, approval preview, what will be sent, email preview, message preview
export function PayloadPreview({ payload, actionClass }: { payload: string; actionClass: string | null | undefined }) {
  const [rawOpen, setRawOpen] = useState(false)
  const parsed = parsePayload(payload)
  const caption = previewCaption(actionClass)

  return (
    <div
      style={{
        background: 'var(--paper)',
        border: '1px solid var(--line)',
        borderRadius: 'var(--radius-control-lg)',
        padding: 'var(--space-5)',
        color: 'var(--ink)',
        lineHeight: 1.6,
      }}
    >
      <p className="caption muted" style={{ marginBottom: 'var(--space-3)' }}>
        {caption}
      </p>

      {parsed.kind === 'raw' ? (
        // Not a set of fields, so the text is all there is to show.
        <div className="approval-payload" role="group" aria-label={`${caption}, as written`} tabIndex={0}>
          <pre
            style={{
              margin: 0,
              whiteSpace: 'pre-wrap',
              overflowWrap: 'anywhere',
              fontFamily: 'var(--font-mono)',
              fontSize: 'var(--text-caption)',
            }}
          >
            {parsed.text}
          </pre>
        </div>
      ) : (
        <>
          {/* Scrolls in place once a request runs long, so the buttons below stay within reach. */}
          <div className="approval-payload" role="group" aria-label={`${caption}, field by field`} tabIndex={0}>
            <div className="stack" style={{ gap: 'var(--space-5)' }}>
              {parsed.known.length > 0 && <Rows rows={parsed.known} />}
              {parsed.other.length > 0 && (
                <div className="stack" style={{ gap: 'var(--space-3)' }}>
                  <p className="caption muted">Other details</p>
                  <Rows rows={parsed.other} />
                </div>
              )}
              {parsed.known.length === 0 && parsed.other.length === 0 && (
                <p className="muted">This action carries no further details.</p>
              )}
            </div>
          </div>

          <div style={{ marginTop: 'var(--space-4)' }}>
            <Collapsible title="Show the raw request" open={rawOpen} onToggle={setRawOpen} headingLevel="p">
              <pre className="inline-approval-payload">{formatPayload(payload)}</pre>
            </Collapsible>
          </div>
        </>
      )}
    </div>
  )
}
