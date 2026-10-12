// @find: step panel, step inspector, edit step, choose agent, step title, step instruction, required connectors, connect steps from a menu, hand over to, receives from, remove connection, delete step, workflow details, workflow name, problems list, StepInspector
// @what: The panel beside the canvas that edits the selected step (agent, title, instruction, connectors, connections) or connection, or the workflow's name, description and problems when nothing is selected.
// @flow: Rendered by routes/WorkflowEditor.tsx; every change goes back through its callbacks to lib/workflowGraph edits
import { useEffect, useRef } from 'react'
import { Button, Input, Select, Textarea } from '../ui'
import { MAX_INSTRUCTION, MAX_TITLE, canConnect, lacksConnector } from '../../lib/workflowGraph'
import type { Problem, WorkflowGraph, WorkflowNode } from '../../lib/workflowGraph'
import type { PaletteAgent, PaletteConnector } from '../../lib/workflowQueries'
import type { Selection } from './WorkflowCanvas'

/*
 * This panel is also how the canvas works without a pointer. Everything a drag does has a
 * control here: the agent list replaces dropping an agent, the connector list replaces dropping a
 * connector, and "Hand over to" replaces drawing a connection.
 *
 * A control that removes something removes itself, so focus is moved on deliberately rather than
 * left to fall to the page: to the next item in the same list, else the one before, else the
 * list's heading, or to the panel's heading when the whole panel changes.
 */

/** The panel heading every view of it shares, focusable so focus can land on it. */
const PANEL_HEADING = '#wf-inspector-heading'

/** A selector for an element by its data-focus-key, quoting the key safely. */
function focusKey(key: string): string {
  return `[data-focus-key="${key.replace(/["\\]/g, '\\$&')}"]`
}

/** Asks for focus to move, after the next render, to the first of these selectors that matches. */
type FocusLater = (...selectors: string[]) => void

// @find: focus after removing, keep keyboard focus in step panel
/** Focus that waits for the next render: the element to focus usually appears or survives only then. */
function useFocusLater(): FocusLater {
  const pending = useRef<string[] | null>(null)
  useEffect(() => {
    const selectors = pending.current
    if (!selectors) return
    pending.current = null
    for (const selector of selectors) {
      const element = document.querySelector<HTMLElement>(selector)
      if (element) {
        element.focus()
        return
      }
    }
  })
  return (...selectors: string[]) => {
    pending.current = selectors
  }
}

/** Where focus goes when item `index` of a list of keys is removed: the next, else the previous, else the fallback. */
function afterRemoving(keys: readonly string[], index: number, fallback: string): string[] {
  return [keys[index + 1], keys[index - 1]].filter((key): key is string => Boolean(key)).map(focusKey).concat(fallback)
}

type Props = {
  graph: WorkflowGraph
  selection: Selection
  agents: readonly PaletteAgent[]
  connectors: readonly PaletteConnector[]
  numbers: ReadonlyMap<string, number>
  problems: readonly Problem[]
  canEdit: boolean
  name: string
  description: string
  nameError: string | null
  onName: (name: string) => void
  onDescription: (description: string) => void
  onUpdateNode: (id: string, patch: Partial<Pick<WorkflowNode, 'agentId' | 'title' | 'instruction'>>) => void
  onAddConnector: (nodeId: string, server: string) => void
  onRemoveConnector: (nodeId: string, server: string) => void
  onConnect: (from: string, to: string) => void
  onRemoveEdge: (edgeId: string) => void
  onRemoveNodes: (ids: string[]) => void
  onSelect: (selection: Selection) => void
}

// @find: step inspector component
/**
 * The step panel. Shows, in order of precedence: one selected step's fields; several selected
 * steps with a Delete action; one selected connection with Remove; or the workflow's name,
 * description and every problem, each with a way to the step it concerns. Read-only roles see the
 * same facts without the controls.
 */
export function StepInspector(props: Props) {
  const { graph, selection } = props
  const focusLater = useFocusLater()
  if (selection.nodes.length === 1) {
    const node = graph.nodes.find((candidate) => candidate.id === selection.nodes[0])
    if (node) return <StepPanel {...props} node={node} focusLater={focusLater} />
  }
  if (selection.nodes.length > 1) {
    return (
      <section className="wf-inspector" aria-labelledby="wf-inspector-heading">
        <h2 id="wf-inspector-heading" className="section-heading" tabIndex={-1}>
          {selection.nodes.length} steps selected
        </h2>
        <p className="caption muted">Drag one of them to move them together.</p>
        {props.canEdit && (
          <Button variant="danger" onClick={() => props.onRemoveNodes(selection.nodes)}>
            Delete these steps
          </Button>
        )}
      </section>
    )
  }
  if (selection.edge) {
    const edge = graph.edges.find((candidate) => candidate.id === selection.edge)
    if (edge) {
      const from = props.numbers.get(edge.from)
      const to = props.numbers.get(edge.to)
      return (
        <section className="wf-inspector" aria-labelledby="wf-inspector-heading">
          <h2 id="wf-inspector-heading" className="section-heading" tabIndex={-1}>
            Connection
          </h2>
          <p>
            Step {from} hands its result to step {to}, which waits for it.
          </p>
          {props.canEdit && (
            <Button
              variant="outline"
              onClick={() => {
                focusLater(PANEL_HEADING)
                props.onRemoveEdge(edge.id)
              }}
            >
              Remove this connection
            </Button>
          )}
        </section>
      )
    }
  }
  return <WorkflowPanel {...props} focusLater={focusLater} />
}

/** How a step is named in a list: its number and its title, or its agent. */
function stepName(node: WorkflowNode, numbers: ReadonlyMap<string, number>, agents: readonly PaletteAgent[]): string {
  const who = node.title?.trim() || agents.find((agent) => agent.id === node.agentId)?.name || 'No agent yet'
  return `Step ${numbers.get(node.id) ?? '?'}: ${who}`
}

/** The fields of one step. */
function StepPanel({ node, focusLater, ...props }: Props & { node: WorkflowNode; focusLater: FocusLater }) {
  const { graph, numbers, agents, connectors, canEdit } = props
  const number = numbers.get(node.id) ?? 0
  const agentKnown = agents.some((agent) => agent.id === node.agentId)
  const incoming = graph.edges.filter((edge) => edge.to === node.id)
  const outgoing = graph.edges.filter((edge) => edge.from === node.id)
  const targets = graph.nodes.filter((other) => canConnect(graph, node.id, other.id) === null)
  const connectorName = (id: string) => connectors.find((connector) => connector.id === id)?.name ?? id
  const agent = agents.find((candidate) => candidate.id === node.agentId)
  const addable = connectors.filter((connector) => !node.connectors.includes(connector.id))
  const own = props.problems.filter((problem) => problem.nodeId === node.id)
  const nodeById = new Map(graph.nodes.map((candidate) => [candidate.id, candidate]))

  const chipKeys = node.connectors.map((server) => `chip-${server}`)
  const incomingKeys = incoming.map((edge) => `in-${edge.id}`)
  const outgoingKeys = outgoing.map((edge) => `out-${edge.id}`)

  return (
    <section className="wf-inspector" aria-labelledby="wf-inspector-heading">
      <h2 id="wf-inspector-heading" className="section-heading" tabIndex={-1}>
        Step {number}
      </h2>

      {own.length > 0 && (
        <ul className="wf-problems" aria-label="Problems with this step">
          {own.map((problem, index) => (
            <li key={`${problem.code}-${index}`} data-severity={problem.severity}>
              {problem.message}
            </li>
          ))}
        </ul>
      )}

      <Select
        label="Agent"
        value={node.agentId ?? ''}
        disabled={!canEdit}
        onChange={(event) => props.onUpdateNode(node.id, { agentId: event.target.value || null })}
      >
        <option value="">Choose an agent</option>
        {!agentKnown && node.agentId && <option value={node.agentId}>An agent that is no longer available</option>}
        {agents.map((candidate) => (
          <option key={candidate.id} value={candidate.id}>
            {candidate.status === 'active' ? candidate.name : `${candidate.name} (paused)`}
          </option>
        ))}
      </Select>

      <Input
        label="Title"
        optional
        value={node.title ?? ''}
        maxLength={MAX_TITLE}
        disabled={!canEdit}
        hint="A short name for the step. Without one, the task is named after the agent and the instruction."
        onChange={(event) => props.onUpdateNode(node.id, { title: event.target.value })}
      />

      <Textarea
        id={`wf-instruction-${node.id}`}
        label="Instruction"
        rows={6}
        value={node.instruction}
        maxLength={MAX_INSTRUCTION}
        disabled={!canEdit}
        data-step-instruction={node.id}
        hint={`Write what ${agent?.name ?? 'the agent'} should do. Put a name in double braces, such as {{customer}}, for a value you fill in each time the workflow runs.`}
        onChange={(event) => props.onUpdateNode(node.id, { instruction: event.target.value })}
      />

      <div className="wf-inspector-group">
        <h3 id="wf-connectors-heading" className="wf-inspector-subheading" tabIndex={-1}>
          Connectors this step needs
        </h3>
        {node.connectors.length === 0 ? (
          <p className="caption muted">None. Add one when the step must use a particular tool.</p>
        ) : (
          <ul className="wf-chip-list">
            {node.connectors.map((server, index) => {
              const granted = !lacksConnector(agent, server)
              return (
                <li key={server} className="wf-chip" data-missing={!granted || undefined}>
                  {connectorName(server)}
                  {!granted && <span className="visually-hidden">, not granted to {agent?.name}</span>}
                  {canEdit && (
                    <button
                      type="button"
                      className="wf-chip-remove"
                      data-focus-key={`chip-${server}`}
                      aria-label={`Remove ${connectorName(server)} from this step`}
                      onClick={() => {
                        focusLater(...afterRemoving(chipKeys, index, '#wf-connectors-heading'))
                        props.onRemoveConnector(node.id, server)
                      }}
                    >
                      <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">
                        <path d="M2 2l6 6M8 2l-6 6" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
                      </svg>
                    </button>
                  )}
                </li>
              )
            })}
          </ul>
        )}
        {canEdit && addable.length > 0 && (
          <Select
            label="Add a connector"
            value=""
            onChange={(event) => {
              if (event.target.value) props.onAddConnector(node.id, event.target.value)
            }}
          >
            <option value="">Choose a connector</option>
            {addable.map((connector) => (
              <option key={connector.id} value={connector.id}>
                {connector.name}
              </option>
            ))}
          </Select>
        )}
      </div>

      <div className="wf-inspector-group">
        <h3 id="wf-receives-heading" className="wf-inspector-subheading" tabIndex={-1}>
          Receives from
        </h3>
        {incoming.length === 0 ? (
          <p className="caption muted">Nothing. This step starts as soon as the workflow runs, beside any other such step.</p>
        ) : (
          <ul className="wf-link-list">
            {incoming.map((edge, index) => (
              <li key={edge.id}>
                <span>{stepName(nodeById.get(edge.from)!, numbers, agents)}</span>
                {canEdit && (
                  <Button
                    variant="quiet"
                    data-focus-key={`in-${edge.id}`}
                    aria-label={`Remove the connection from ${stepName(nodeById.get(edge.from)!, numbers, agents)}`}
                    onClick={() => {
                      focusLater(...afterRemoving(incomingKeys, index, '#wf-receives-heading'))
                      props.onRemoveEdge(edge.id)
                    }}
                  >
                    Remove
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="wf-inspector-group">
        <h3 id="wf-hands-heading" className="wf-inspector-subheading" tabIndex={-1}>
          Hands over to
        </h3>
        {outgoing.length === 0 ? (
          <p className="caption muted">Nothing yet.</p>
        ) : (
          <ul className="wf-link-list">
            {outgoing.map((edge, index) => (
              <li key={edge.id}>
                <span>{stepName(nodeById.get(edge.to)!, numbers, agents)}</span>
                {canEdit && (
                  <Button
                    variant="quiet"
                    data-focus-key={`out-${edge.id}`}
                    aria-label={`Remove the connection to ${stepName(nodeById.get(edge.to)!, numbers, agents)}`}
                    onClick={() => {
                      focusLater(...afterRemoving(outgoingKeys, index, '#wf-hands-heading'))
                      props.onRemoveEdge(edge.id)
                    }}
                  >
                    Remove
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
        {canEdit && targets.length > 0 && (
          <Select
            label="Hand over to another step"
            value=""
            hint="The chosen step waits for this one and receives its result."
            onChange={(event) => {
              if (event.target.value) props.onConnect(node.id, event.target.value)
            }}
          >
            <option value="">Choose a step</option>
            {targets.map((target) => (
              <option key={target.id} value={target.id}>
                {stepName(target, numbers, agents)}
              </option>
            ))}
          </Select>
        )}
      </div>

      {canEdit && (
        <Button variant="outline" onClick={() => props.onRemoveNodes([node.id])}>
          Delete this step
        </Button>
      )}
    </section>
  )
}

/** The workflow's own fields and every problem, shown when nothing is selected. */
function WorkflowPanel({ focusLater, ...props }: Props & { focusLater: FocusLater }) {
  const errors = props.problems.filter((problem) => problem.severity === 'error')
  const warnings = props.problems.filter((problem) => problem.severity === 'warning')
  const nodeIds = new Set(props.graph.nodes.map((node) => node.id))
  return (
    <section className="wf-inspector" aria-labelledby="wf-inspector-heading">
      <h2 id="wf-inspector-heading" className="section-heading" tabIndex={-1}>
        Workflow
      </h2>
      <Input
        label="Name"
        value={props.name}
        maxLength={120}
        required
        disabled={!props.canEdit}
        error={props.nameError}
        onChange={(event) => props.onName(event.target.value)}
      />
      <Textarea
        label="Description"
        optional
        rows={3}
        maxLength={1000}
        value={props.description}
        disabled={!props.canEdit}
        onChange={(event) => props.onDescription(event.target.value)}
      />
      <div className="wf-inspector-group">
        <h3 className="wf-inspector-subheading">Before it can run</h3>
        {errors.length === 0 && warnings.length === 0 ? (
          <p className="caption muted">Nothing is missing.</p>
        ) : (
          <ul className="wf-problems">
            {[...errors, ...warnings].map((problem, index) => (
              <li key={`${problem.code}-${index}`} data-severity={problem.severity}>
                <span>{problem.message}</span>
                {problem.nodeId && nodeIds.has(problem.nodeId) && (
                  <Button
                    variant="quiet"
                    onClick={() => {
                      focusLater(PANEL_HEADING)
                      props.onSelect({ nodes: [problem.nodeId!], edge: null })
                    }}
                  >
                    Show the step
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
      <p className="caption muted">Select a step on the canvas to edit it.</p>
    </section>
  )
}
