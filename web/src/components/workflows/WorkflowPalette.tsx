// @find: workflow palette, agents list, connectors list, drag agent onto canvas, drag connector onto step, add step from palette, filter palette, WorkflowPalette
// @what: The list beside the canvas of agents (with avatars) and connectors a person drags onto the canvas, or presses to add, as a keyboard and touch alternative.
// @flow: Rendered by routes/WorkflowEditor.tsx; writes AGENT_MIME and CONNECTOR_MIME drag data read by WorkflowCanvas
import { useState } from 'react'
import type { DragEvent as ReactDragEvent } from 'react'
import { AgentAvatar } from '../ui/AgentAvatar'
import { AGENT_MIME, CONNECTOR_MIME } from './WorkflowCanvas'
import { connectorCategoryLabel } from '../../lib/labels'
import type { PaletteAgent, PaletteConnector } from '../../lib/workflowQueries'

/*
 * Every item is a real button. Dragging it onto the canvas is the quick way; pressing it is the
 * way that works with a keyboard, a screen reader and a phone: an agent is added as a new step,
 * a connector is added to the selected step.
 */

// @find: workflow palette component
/**
 * The palette. Agents first (paused ones marked, still placeable so a draft can be built before
 * the agent is resumed), then connectors grouped by category. A filter box narrows both lists.
 * Props: the palette data, whether a step is selected (connectors need one), and the two add
 * callbacks.
 */
export function WorkflowPalette({
  agents,
  connectors,
  stepSelected,
  onAddAgent,
  onAddConnector,
}: {
  agents: readonly PaletteAgent[]
  connectors: readonly PaletteConnector[]
  stepSelected: boolean
  onAddAgent: (agentId: string) => void
  onAddConnector: (server: string) => void
}) {
  const [filter, setFilter] = useState('')
  const needle = filter.trim().toLowerCase()
  const shownAgents = agents.filter((agent) => !needle || agent.name.toLowerCase().includes(needle))
  const shownConnectors = connectors.filter(
    (connector) => !needle || connector.name.toLowerCase().includes(needle) || connector.id.includes(needle),
  )

  const startDrag = (event: ReactDragEvent<HTMLButtonElement>, type: string, value: string) => {
    event.dataTransfer.setData(type, value)
    event.dataTransfer.setData('text/plain', value)
    event.dataTransfer.effectAllowed = 'copy'
  }

  return (
    <aside className="wf-palette" aria-label="Agents and connectors">
      <label className="field-label" htmlFor="wf-palette-filter">
        Find an agent or connector
      </label>
      <input
        id="wf-palette-filter"
        className="input"
        type="search"
        value={filter}
        onChange={(event) => setFilter(event.target.value)}
        placeholder="Name"
        autoComplete="off"
      />

      <h2 className="wf-palette-heading" id="wf-palette-agents">
        Agents
      </h2>
      <p className="caption muted">Drag one onto the canvas, or press it to add a step.</p>
      {shownAgents.length === 0 ? (
        <p className="caption muted">{agents.length === 0 ? 'This workspace has no agents yet.' : 'No agent matches.'}</p>
      ) : (
        <ul className="wf-palette-list" aria-labelledby="wf-palette-agents">
          {shownAgents.map((agent) => (
            <li key={agent.id}>
              <button
                type="button"
                className="wf-palette-item"
                draggable
                onDragStart={(event) => startDrag(event, AGENT_MIME, agent.id)}
                onClick={() => onAddAgent(agent.id)}
                aria-label={`Add a step for ${agent.name}${agent.status === 'active' ? '' : ', paused'}`}
              >
                <AgentAvatar name={agent.name} category={agent.category} fallback={agent.fallback} size="sm" />
                <span className="wf-palette-name">{agent.name}</span>
                {agent.status !== 'active' && <span className="wf-chip">Paused</span>}
              </button>
            </li>
          ))}
        </ul>
      )}

      <h2 className="wf-palette-heading" id="wf-palette-connectors">
        Connectors
      </h2>
      <p className="caption muted">
        {stepSelected ? 'Drag one onto a step, or press it to add it to the selected step.' : 'Drag one onto a step, or select a step first and press it.'}
      </p>
      {shownConnectors.length === 0 ? (
        <p className="caption muted">No connector matches.</p>
      ) : (
        <ul className="wf-palette-list" aria-labelledby="wf-palette-connectors">
          {shownConnectors.map((connector) => (
            <li key={connector.id}>
              <button
                type="button"
                className="wf-palette-item wf-palette-connector"
                draggable
                onDragStart={(event) => startDrag(event, CONNECTOR_MIME, connector.id)}
                onClick={() => onAddConnector(connector.id)}
                aria-disabled={!stepSelected || undefined}
                aria-label={`Require ${connector.name} on the selected step`}
              >
                <span className="wf-palette-name">{connector.name}</span>
                <span className="caption muted">{connectorCategoryLabel(connector.category)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </aside>
  )
}
