// @find: sign in showcase, what is this product, login side panel, product glimpse, approval preview, evaluator points, AuthShowcase
// @what: Explains the product beside the sign-in form with a drawn glimpse of a parked agent action.
// @flow: Rendered next to the form on the sign in page
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
 * Each point is one the evaluator can check after signing in. Two things an evaluator might
 * over-read are not promised here. One is that agents know the company without being told: agents
 * and Chat search the documents a workspace has uploaded, as the person they work for, and say so
 * when none covers a request, but they know nothing that was not uploaded. The other is that
 * connectors reach real accounts: most of them, email and calendar among them, work with practice
 * data only - the few that can reach a real account (mcp-core's ConnectorCatalog lists them) do so
 * only after an administrator adds a token. The seven providers are the adapters in llm-core:
 * OpenRouter, Groq, NVIDIA NIM and OpenAI through the OpenAI-compatible one, plus Anthropic, Google
 * Gemini and AWS Bedrock.
 *
 * The glimpse's tool name is illustrative: in the demo workspace, that send runs on practice data.
 */

const POINTS: ReadonlyArray<{ icon: IconName; title: string; body: string }> = [
  { icon: 'lock', title: 'Every action is permission-checked', body: 'Roles are composed from individual permissions.' },
  { icon: 'gate', title: 'People decide what leaves', body: 'Outbound actions wait for an approval.' },
  { icon: 'document', title: 'Document search shows its sources', body: 'Each passage comes with the document it is from.' },
  { icon: 'route', title: 'Seven providers, in a chain', body: 'A failing model is skipped, and the trace says why.' },
]

// @find: AuthShowcase, sign in side panel, product explainer
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
