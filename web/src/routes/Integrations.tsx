import { Card, Eyebrow, Notice, PageHeader, StatusTag, Tag, Time } from '../components/ui'
import type { TagTone } from '../components/ui'
import { QueryState } from '../components/ui/QueryState'
import { formatCount } from '../lib/format'
import { actionClassLabel, serverLabel } from '../lib/labels'
import { useIntegrations } from '../lib/queries'
import type { Integration, Tool } from '../lib/queries'

const EFFECT_TONE: Record<Tool['sideEffect'], TagTone> = {
  READ: 'neutral',
  WRITE: 'blue',
  OUTBOUND: 'warning',
  DESTRUCTIVE: 'danger',
}

/*
 * Tool servers and what each one offers.
 *
 * Connecting an account (OAuth) is not built in this version, so the page offers no button for it
 * and says so, rather than a button that opens a dialog explaining it does nothing.
 */

function sandboxNotice(integrations: Integration[]): string {
  const sandboxed = integrations.filter((integration) => integration.sandbox).length
  if (sandboxed === integrations.length) {
    return 'These servers run against a sandbox with seeded data, so nothing an agent does leaves this machine. Live account connections are not available in this version.'
  }
  if (sandboxed > 0) {
    return 'Servers marked Sandbox run against seeded data, so nothing an agent does through them leaves this machine. Connecting another account from the console is not available in this version.'
  }
  return 'Connecting another account from the console is not available in this version.'
}

export function Integrations() {
  const integrationsQuery = useIntegrations()

  return (
    <div className="page">
      <PageHeader
        eyebrow="What the workforce can reach"
        title="Integrations"
        description="Tool servers agents act through. An agent only sees the tools it has been granted."
      />

      <QueryState
        query={integrationsQuery}
        permission="integration:read"
        what="the integrations list"
        isEmpty={(integrations) => integrations.length === 0}
        empty={
          <Card as="section">
            <Eyebrow>Integrations</Eyebrow>
            <p className="muted">No tool servers are available in this workspace.</p>
          </Card>
        }
        rows={4}
      >
        {(integrations) => (
          <>
            <Notice tone="info">{sandboxNotice(integrations)}</Notice>
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fill, minmax(min(340px, 100%), 1fr))',
                gap: 'var(--space-5)',
                marginTop: 'var(--space-7)',
              }}
            >
              {integrations.map((integration) => (
                <IntegrationCard key={integration.server} integration={integration} />
              ))}
            </div>
          </>
        )}
      </QueryState>
    </div>
  )
}

function IntegrationCard({ integration }: { integration: Integration }) {
  // Reconnect is a flag beside the stored status, not a status of its own, and it is the one
  // thing about a connection somebody has to act on. The sandbox flag decides what the rest of
  // the card and the notice say, so the tag follows it too rather than a stored status that
  // could read "Connected" for a server that only reaches seeded data.
  const status = integration.reconnectRequired
    ? 'reconnect_required'
    : integration.sandbox
      ? 'sandbox'
      : integration.status
  const toolCount = integration.tools.length

  return (
    <Card as="article">
      <Eyebrow>{toolCount === 1 ? '1 tool' : `${formatCount(toolCount)} tools`}</Eyebrow>

      <div
        className="row"
        style={{ justifyContent: 'space-between', flexWrap: 'wrap', gap: 'var(--space-3)', marginBottom: 'var(--space-5)' }}
      >
        <h2 className="section-heading" style={{ fontSize: '15px' }}>
          {serverLabel(integration.server, integration.displayName)}
        </h2>
        <StatusTag kind="integration" status={status} />
      </div>

      {/* A sandbox server has no account, no granted permissions and was never connected, so
          those rows would only ever say "none". */}
      {!integration.sandbox && (
        <div className="stack" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-5)', fontSize: '13px' }}>
          {integration.accountLabel && (
            <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-3)' }}>
              <span className="muted">Account</span>
              <span>{integration.accountLabel}</span>
            </div>
          )}
          <div className="stack" style={{ gap: 'var(--space-1)' }}>
            <span className="caption">Permissions granted</span>
            <span className="mono" style={{ overflowWrap: 'anywhere' }}>
              {integration.grantedScopes.length > 0 ? integration.grantedScopes.join(', ') : 'None'}
            </span>
          </div>
          {integration.missingScopes.length > 0 && (
            <div className="stack" style={{ gap: 'var(--space-1)' }}>
              <span className="caption">Permissions missing</span>
              <div className="row" style={{ flexWrap: 'wrap', gap: 'var(--space-2)' }}>
                {integration.missingScopes.map((scope) => (
                  <Tag key={scope} tone="warning">
                    {scope}
                  </Tag>
                ))}
              </div>
            </div>
          )}
          <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-3)' }}>
            <span className="muted">Connected</span>
            {integration.connectedAt ? (
              <Time iso={integration.connectedAt} className="muted" />
            ) : (
              <span className="muted">Never</span>
            )}
          </div>
        </div>
      )}

      <ul className="stack" style={{ gap: 'var(--space-4)', margin: 0, padding: 0, listStyle: 'none' }}>
        {integration.tools.map((tool) => (
          <li key={tool.name} className="stack" style={{ gap: 'var(--space-1)' }}>
            <span className="mono" title={tool.qualifiedName}>
              {tool.name}
            </span>
            {tool.description && <p className="caption">{tool.description}</p>}
            <div className="row" style={{ flexWrap: 'wrap', gap: 'var(--space-2)', marginTop: 'var(--space-1)' }}>
              <Tag tone={EFFECT_TONE[tool.sideEffect]}>{actionClassLabel(tool.sideEffect)}</Tag>
              {tool.alwaysRequiresApproval && (
                <Tag tone="warning" withDot>
                  Approval required
                </Tag>
              )}
            </div>
          </li>
        ))}
      </ul>
    </Card>
  )
}
