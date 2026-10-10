// @find: tools shortlist, connect popular tools, Slack Gmail Google Drive, connect integration during setup, connectors suggestions, ConnectDialog, ToolsShortlist, live account, setup wizard
// @what: Setup list of the most common connectors, each with the Connect dialog, limited to those that can reach a live account.
// @flow: Used by the Setup page; opens connectors/ConnectDialog; full catalogue is the Connectors page.
import { useState } from 'react'
import { Button, Tag } from '../ui'
import { ConnectDialog } from '../connectors/ConnectDialog'
import { connectorState } from '../../lib/connectors'
import { useIntegrations } from '../../lib/queries'
import type { Integration } from '../../lib/queries'

/*
 * The tools most workspaces connect first, each with the same Connect dialog the Connectors page
 * opens. Only connectors this installation can connect to a live account are offered; the whole
 * catalogue stays on Connectors.
 */

/** Tried first, in this order, when the installation offers them. */
const POPULAR = ['slack', 'gmail', 'google-drive', 'google-calendar', 'github', 'notion', 'jira', 'hubspot', 'microsoft-teams', 'outlook', 'linear', 'salesforce']

// @find: pick suggested connectors, popular connectors first
/** Up to `limit` connectors to suggest: popular ones first, those already connected included so their state shows. */
export function shortlist(integrations: readonly Integration[], limit = 6): Integration[] {
  const offered = integrations.filter((item) => item.liveAvailable && connectorState(item) !== 'builtin')
  const rank = (item: Integration) => {
    const index = POPULAR.indexOf(item.server.toLowerCase())
    return index < 0 ? POPULAR.length : index
  }
  return [...offered].sort((a, b) => rank(a) - rank(b) || a.displayName.localeCompare(b.displayName)).slice(0, limit)
}

// @find: tools shortlist, connect tool in setup, Connect button, popular connectors
export function ToolsShortlist() {
  const integrations = useIntegrations()
  const [connecting, setConnecting] = useState<{ session: number; integration: Integration | null; open: boolean }>({
    session: 0,
    integration: null,
    open: false,
  })

  if (integrations.isLoading) return <p className="muted">Loading the tools…</p>
  const picks = shortlist(integrations.data ?? [])

  return (
    <div className="stack" style={{ gap: 'var(--space-4)' }}>
      {picks.length === 0 ? (
        <p className="muted">No tool can be connected to a live account on this installation yet. Agents use practice data.</p>
      ) : (
        <ul className="setup-tools">
          {picks.map((item) => {
            const state = connectorState(item)
            return (
              <li key={item.server} className="setup-tool">
                <div style={{ minWidth: 0 }}>
                  <p className="section-heading" style={{ margin: 0 }}>
                    {item.displayName}
                  </p>
                  {item.description && <p className="caption setup-tool-description">{item.description}</p>}
                </div>
                {state === 'connected' ? (
                  <Tag tone="success">Connected</Tag>
                ) : (
                  <Button
                    variant="outline"
                    className="button-sm"
                    aria-label={`${state === 'attention' ? 'Reconnect' : 'Connect'} ${item.displayName}`}
                    onClick={() => setConnecting((current) => ({ session: current.session + 1, integration: item, open: true }))}
                  >
                    {state === 'attention' ? 'Reconnect' : 'Connect'}
                  </Button>
                )}
              </li>
            )
          })}
        </ul>
      )}
      <p className="caption" style={{ margin: 0 }}>
        <a className="link" href="/connectors">
          See all connectors
        </a>
      </p>
      {connecting.integration && (
        <ConnectDialog
          key={connecting.session}
          open={connecting.open}
          integration={connecting.integration}
          onClose={() => setConnecting((current) => ({ ...current, open: false }))}
        />
      )}
    </div>
  )
}
