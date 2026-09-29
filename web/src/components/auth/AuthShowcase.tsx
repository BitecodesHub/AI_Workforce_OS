import { Eyebrow, Tag } from '../ui'
import { Icon } from '../landing/shared/Icon'
import type { IconName } from '../landing/shared/Icon'

/*
 * What this is, beside the sign-in form.
 *
 * Somebody arriving at a login screen cold needs to know what they are signing in to before the
 * form is useful to them. The glimpse is a picture of the one moment the product exists for - an
 * agent's outbound action parked until a person decides - drawn from tokens, and hidden from
 * assistive technology because the heading and points beside it already say it in words.
 *
 * Each point is one the evaluator can check after signing in. Agents do not read the knowledge
 * base (only Chat searches it), and the bundled tool servers run against a sandbox, so neither is
 * promised here.
 */

const POINTS: ReadonlyArray<{ icon: IconName; title: string; body: string }> = [
  { icon: 'lock', title: 'Every action is permission-checked', body: 'Roles are composed from individual permissions.' },
  { icon: 'gate', title: 'People decide what leaves', body: 'Outbound actions wait for an approval.' },
  { icon: 'document', title: 'Document search shows its sources', body: 'Each passage comes with the document it is from.' },
  { icon: 'route', title: 'Seven providers, in a chain', body: 'A failing model is skipped, and the trace says why.' },
]

export function AuthShowcase() {
  return (
    <aside className="auth-showcase" aria-labelledby="auth-showcase-title">
      <div className="auth-showcase-copy">
        <Eyebrow>A governed AI workforce</Eyebrow>
        <h2 id="auth-showcase-title" className="auth-showcase-title">
          Agents that do the work, and stop before the part you would want to check
        </h2>
        <p className="auth-showcase-lead">
          Configure agents for the roles you already have. They act through the tool servers you grant them, and
          wait for a person before anything leaves the workspace.
        </p>
      </div>

      <div className="auth-glimpse" aria-hidden="true">
        <div className="auth-glimpse-card">
          <div className="auth-glimpse-head">
            <span className="auth-glimpse-tool">
              <span className="auth-glimpse-dot" />
              gmail.send_message
            </span>
            <Tag tone="warning">Waiting for approval</Tag>
          </div>
          <p className="auth-glimpse-title">Send the welcome email to the new hire</p>
          <div className="auth-glimpse-meta">
            <Tag tone="operations" withDot>
              HR agent
            </Tag>
            <span>Requested just now</span>
          </div>
          <div className="auth-glimpse-actions">
            <span className="auth-glimpse-button auth-glimpse-button-primary">
              <Icon name="check" />
              Approve
            </span>
            <span className="auth-glimpse-button">Reject</span>
          </div>
        </div>
      </div>

      <ul className="auth-points">
        {POINTS.map((point) => (
          <li key={point.title} className="auth-point">
            <span className="auth-point-icon">
              <Icon name={point.icon} size={16} />
            </span>
            <span className="auth-point-text">
              <span className="auth-point-title">{point.title}</span>
              <span className="auth-point-body">{point.body}</span>
            </span>
          </li>
        ))}
      </ul>

      <a className="auth-showcase-link" href="/home">
        See how it works
        <Icon name="arrow-right" />
      </a>
    </aside>
  )
}
