// @find: workflow graph, canvas model, add step, remove step, move step, connect steps, hand-off, disconnect, required connectors, cycle detection, loop, would create cycle, execution order, step numbers, compile to tasks, placeholders, validate workflow, problems, fit view, edge path, bezier, auto layout, tidy up, workflowGraph
// @what: Pure functions for the workflow canvas: the graph shape, every edit, loop detection, the order steps run in, the tasks a run creates, the problems a save or run would meet, and canvas geometry.
// @flow: Used by routes/WorkflowEditor.tsx, routes/WorkflowRun.tsx and components/workflows; mirrors the server's WorkflowGraph and WorkflowValidator
/*
 * The canvas, as data. Every edit the editor makes goes through a function here that returns a
 * new graph, so undo-free React state stays simple and every rule can be tested without a DOM.
 *
 * The rules mirror the orchestrator's WorkflowGraph and WorkflowValidator: at most 40 steps, an
 * agent on at most two of them and every agent active, no loops, an instruction on every step, at
 * most 20 different {{placeholders}}, and the connectors a step needs granted to its agent. The server is the authority; this copy lets the canvas mark a
 * step the moment it goes wrong instead of after a save.
 */

/** One step: an agent and what it should do, at a place on the canvas. */
export type WorkflowNode = {
  id: string
  type: 'agent'
  agentId: string | null
  title: string | null
  instruction: string
  /** Connector server ids the step needs, such as "gmail". */
  connectors: string[]
  x: number
  y: number
}

/** One hand-off: when `from` completes, its result goes to `to`, which waits for it. */
export type WorkflowEdge = { id: string; from: string; to: string }

export type WorkflowGraph = { nodes: WorkflowNode[]; edges: WorkflowEdge[] }

/** Canvas geometry: every step is drawn at this size, so hit tests and edges need no DOM. */
export const NODE_WIDTH = 232
export const NODE_HEIGHT = 116
export const MAX_STEPS = 40
export const MAX_STEPS_PER_AGENT = 2
export const MAX_TITLE = 200
export const MAX_INSTRUCTION = 10_000
export const MAX_CONNECTORS = 10
export const MIN_ZOOM = 0.3
export const MAX_ZOOM = 2
/** The most different {{placeholders}} a workflow may use: a run carries at most this many values. */
export const MAX_INPUTS = 20

/** The same placeholder rule as the server: {{name}}, a letter first, up to 40 characters. */
const PLACEHOLDER = /\{\{\s*([A-Za-z][A-Za-z0-9 _-]{0,39}?)\s*\}\}/g

/** A canvas with nothing on it. */
export function emptyGraph(): WorkflowGraph {
  return { nodes: [], edges: [] }
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

const finite = (value: unknown): number => (typeof value === 'number' && Number.isFinite(value) ? value : 0)

// @find: read workflow graph from server or draft, normalise graph
/**
 * Reads a graph from the server or from a stored draft, defensively: anything missing or of the
 * wrong type becomes its empty value, and steps without an id are dropped. Never throws.
 */
export function normaliseGraph(raw: unknown): WorkflowGraph {
  if (!isRecord(raw)) return emptyGraph()
  const nodes: WorkflowNode[] = []
  for (const item of Array.isArray(raw.nodes) ? raw.nodes : []) {
    if (!isRecord(item) || typeof item.id !== 'string' || !item.id) continue
    nodes.push({
      id: item.id,
      type: 'agent',
      agentId: typeof item.agentId === 'string' && item.agentId ? item.agentId : null,
      title: typeof item.title === 'string' && item.title.trim() ? item.title : null,
      instruction: typeof item.instruction === 'string' ? item.instruction : '',
      connectors: Array.isArray(item.connectors) ? item.connectors.filter((c): c is string => typeof c === 'string') : [],
      x: finite(item.x),
      y: finite(item.y),
    })
  }
  const ids = new Set(nodes.map((node) => node.id))
  const edges: WorkflowEdge[] = []
  for (const item of Array.isArray(raw.edges) ? raw.edges : []) {
    if (!isRecord(item) || typeof item.from !== 'string' || typeof item.to !== 'string') continue
    if (!ids.has(item.from) || !ids.has(item.to)) continue
    const id = typeof item.id === 'string' && item.id ? item.id : `e-${item.from}-${item.to}`
    edges.push({ id, from: item.from, to: item.to })
  }
  return { nodes, edges }
}

/** Whether two graphs say the same thing, for the unsaved-changes check. */
export function sameGraph(a: WorkflowGraph, b: WorkflowGraph): boolean {
  return JSON.stringify(canonical(a)) === JSON.stringify(canonical(b))
}

/** The graph with every field in a fixed order and texts trimmed, as the server stores it. */
export function canonical(graph: WorkflowGraph): WorkflowGraph {
  return {
    nodes: graph.nodes.map((node) => ({
      id: node.id,
      type: 'agent',
      agentId: node.agentId,
      title: node.title?.trim() ? node.title.trim() : null,
      instruction: node.instruction.trim(),
      connectors: [...node.connectors],
      x: Math.round(node.x),
      y: Math.round(node.y),
    })),
    edges: graph.edges.map((edge) => ({ id: edge.id, from: edge.from, to: edge.to })),
  }
}

/** An id not yet used in the graph, such as "step-3" or "link-7". */
export function freshId(prefix: string, taken: Iterable<string>): string {
  const used = new Set(taken)
  for (let n = used.size + 1; ; n += 1) {
    const id = `${prefix}-${n}`
    if (!used.has(id)) return id
  }
}

/* ---- Edits ------------------------------------------------------------------------------------ */

// @find: add step to canvas, new node
/** Adds a step for an agent at a position (snapped to whole pixels). Returns the new graph and the step's id. */
export function addNode(
  graph: WorkflowGraph,
  step: { agentId: string | null; x: number; y: number; instruction?: string; title?: string | null },
): { graph: WorkflowGraph; id: string } {
  const id = freshId('step', graph.nodes.map((node) => node.id))
  const node: WorkflowNode = {
    id,
    type: 'agent',
    agentId: step.agentId,
    title: step.title ?? null,
    instruction: step.instruction ?? '',
    connectors: [],
    x: Math.round(step.x),
    y: Math.round(step.y),
  }
  return { graph: { ...graph, nodes: [...graph.nodes, node] }, id }
}

/** Changes some fields of one step. */
export function updateNode(
  graph: WorkflowGraph,
  id: string,
  patch: Partial<Pick<WorkflowNode, 'agentId' | 'title' | 'instruction' | 'connectors' | 'x' | 'y'>>,
): WorkflowGraph {
  return { ...graph, nodes: graph.nodes.map((node) => (node.id === id ? { ...node, ...patch } : node)) }
}

// @find: move steps, drag nodes
/** Moves the given steps by an offset, rounded to whole pixels. */
export function moveNodes(graph: WorkflowGraph, ids: Iterable<string>, dx: number, dy: number): WorkflowGraph {
  const moving = new Set(ids)
  if (moving.size === 0 || (dx === 0 && dy === 0)) return graph
  return {
    ...graph,
    nodes: graph.nodes.map((node) =>
      moving.has(node.id) ? { ...node, x: Math.round(node.x + dx), y: Math.round(node.y + dy) } : node,
    ),
  }
}

// @find: remove steps, delete node, delete selection
/** Removes steps and every connection that touches them. */
export function removeNodes(graph: WorkflowGraph, ids: Iterable<string>): WorkflowGraph {
  const gone = new Set(ids)
  if (gone.size === 0) return graph
  return {
    nodes: graph.nodes.filter((node) => !gone.has(node.id)),
    edges: graph.edges.filter((edge) => !gone.has(edge.from) && !gone.has(edge.to)),
  }
}

/** Removes connections by id. */
export function removeEdges(graph: WorkflowGraph, ids: Iterable<string>): WorkflowGraph {
  const gone = new Set(ids)
  if (gone.size === 0) return graph
  return { ...graph, edges: graph.edges.filter((edge) => !gone.has(edge.id)) }
}

/** Why a connection cannot be drawn. */
export type ConnectRefusal = 'missing' | 'self' | 'duplicate' | 'cycle'

/** Plain words for each refusal, for a toast or a status line. */
export const CONNECT_REFUSAL: Record<ConnectRefusal, string> = {
  missing: 'That step is no longer on the canvas.',
  self: 'A step cannot hand over to itself.',
  duplicate: 'These two steps are already connected.',
  cycle: 'That connection would make the steps wait for each other in a loop.',
}

// @find: can connect steps, would create loop
/** Whether `from` may hand over to `to`: both exist, they differ, they are not joined yet, and no loop would form. */
export function canConnect(graph: WorkflowGraph, from: string, to: string): ConnectRefusal | null {
  const ids = new Set(graph.nodes.map((node) => node.id))
  if (!ids.has(from) || !ids.has(to)) return 'missing'
  if (from === to) return 'self'
  if (graph.edges.some((edge) => edge.from === from && edge.to === to)) return 'duplicate'
  if (reaches(graph, to, from)) return 'cycle'
  return null
}

// @find: connect two steps, draw edge
/** Adds the connection `from` to `to` when allowed; otherwise returns the graph unchanged with the reason. */
export function connect(
  graph: WorkflowGraph,
  from: string,
  to: string,
): { graph: WorkflowGraph; edgeId: string | null; refusal: ConnectRefusal | null } {
  const refusal = canConnect(graph, from, to)
  if (refusal) return { graph, edgeId: null, refusal }
  const edgeId = freshId('link', graph.edges.map((edge) => edge.id))
  return { graph: { ...graph, edges: [...graph.edges, { id: edgeId, from, to }] }, edgeId, refusal: null }
}

/** Marks a connector as required by a step (once; at most MAX_CONNECTORS). */
export function addConnector(graph: WorkflowGraph, nodeId: string, server: string): WorkflowGraph {
  const node = graph.nodes.find((candidate) => candidate.id === nodeId)
  if (!node || node.connectors.includes(server) || node.connectors.length >= MAX_CONNECTORS) return graph
  return updateNode(graph, nodeId, { connectors: [...node.connectors, server] })
}

/** Removes a required connector from a step. */
export function removeConnector(graph: WorkflowGraph, nodeId: string, server: string): WorkflowGraph {
  const node = graph.nodes.find((candidate) => candidate.id === nodeId)
  if (!node) return graph
  return updateNode(graph, nodeId, { connectors: node.connectors.filter((id) => id !== server) })
}

/* ---- Graph questions -------------------------------------------------------------------------- */

/** Steps that hand over to each step, in canvas order. Connections to missing steps are ignored. */
export function predecessors(graph: WorkflowGraph): Map<string, string[]> {
  const before = new Map(graph.nodes.map((node) => [node.id, [] as string[]]))
  for (const edge of usableEdges(graph)) before.get(edge.to)!.push(edge.from)
  return before
}

/** Steps each step hands over to, in canvas order. */
export function successors(graph: WorkflowGraph): Map<string, string[]> {
  const after = new Map(graph.nodes.map((node) => [node.id, [] as string[]]))
  for (const edge of usableEdges(graph)) after.get(edge.from)!.push(edge.to)
  return after
}

/** Connections between two different steps both on the canvas, each pair once. */
function usableEdges(graph: WorkflowGraph): WorkflowEdge[] {
  const ids = new Set(graph.nodes.map((node) => node.id))
  const seen = new Set<string>()
  return graph.edges.filter((edge) => {
    if (!ids.has(edge.from) || !ids.has(edge.to) || edge.from === edge.to) return false
    const key = `${edge.from}\u0000${edge.to}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

/** Whether following hand-offs from `start` ever reaches `target`. */
function reaches(graph: WorkflowGraph, start: string, target: string): boolean {
  const after = successors(graph)
  const stack = [start]
  const seen = new Set<string>()
  while (stack.length > 0) {
    const id = stack.pop()!
    if (id === target) return true
    if (seen.has(id)) continue
    seen.add(id)
    stack.push(...(after.get(id) ?? []))
  }
  return false
}

// @find: find loop, cycle detection, steps waiting for each other
/** One loop in the hand-offs as the step ids on it in order, or an empty list when there is none. */
export function findCycle(graph: WorkflowGraph): string[] {
  const after = successors(graph)
  const state = new Map<string, 1 | 2>()
  const path: string[] = []
  const visit = (id: string): string[] => {
    const seen = state.get(id)
    if (seen === 2) return []
    if (seen === 1) return path.slice(path.indexOf(id))
    state.set(id, 1)
    path.push(id)
    for (const next of after.get(id) ?? []) {
      const found = visit(next)
      if (found.length > 0) return found
    }
    path.pop()
    state.set(id, 2)
    return []
  }
  for (const node of graph.nodes) {
    const found = visit(node.id)
    if (found.length > 0) return found
  }
  return []
}

// @find: execution order, topological order, reading order
/**
 * The order steps become tasks, exactly as the server numbers them: every step after the steps
 * that hand over to it, and among steps that could go next, the one further left, then higher up,
 * then earlier on the canvas. Null when the steps form a loop.
 */
export function executionOrder(graph: WorkflowGraph): WorkflowNode[] | null {
  const index = new Map(graph.nodes.map((node, i) => [node.id, i]))
  const waiting = new Map<string, number>()
  for (const [id, before] of predecessors(graph)) waiting.set(id, before.length)
  const after = successors(graph)
  const byReading = (a: WorkflowNode, b: WorkflowNode) => a.x - b.x || a.y - b.y || index.get(a.id)! - index.get(b.id)!
  const ready = graph.nodes.filter((node) => waiting.get(node.id) === 0)
  const order: WorkflowNode[] = []
  const byId = new Map(graph.nodes.map((node) => [node.id, node]))
  while (ready.length > 0) {
    ready.sort(byReading)
    const next = ready.shift()!
    order.push(next)
    for (const successor of after.get(next.id) ?? []) {
      const remaining = (waiting.get(successor) ?? 0) - 1
      waiting.set(successor, remaining)
      if (remaining === 0) ready.push(byId.get(successor)!)
    }
  }
  return order.length === graph.nodes.length ? order : null
}

/** Each step's number as a person reads it (1, 2, 3...), in execution order, or canvas order when there is a loop. */
export function stepNumbers(graph: WorkflowGraph): Map<string, number> {
  const order = executionOrder(graph) ?? graph.nodes
  return new Map(order.map((node, i) => [node.id, i + 1]))
}

/* ---- Placeholders and compiling --------------------------------------------------------------- */

// @find: placeholders in instructions, run values needed
/** Every {{placeholder}} the steps use, once each, in canvas order. */
export function placeholders(graph: WorkflowGraph): string[] {
  const names: string[] = []
  for (const node of graph.nodes) {
    for (const match of node.instruction.matchAll(PLACEHOLDER)) {
      const name = match[1]!.trim()
      if (!names.includes(name)) names.push(name)
    }
  }
  return names
}

/** The text with every placeholder that has a value filled in; one without a value is left as written. */
export function fillPlaceholders(text: string, values: Record<string, string>): string {
  return text.replace(PLACEHOLDER, (whole, name: string) => values[name.trim()] ?? whole)
}

/** One task a run will create, as the server compiles it. */
export type CompiledTask = {
  nodeId: string
  agentId: string | null
  title: string
  /** Positions of the tasks this one waits for. */
  dependsOn: number[]
}

// @find: compile workflow to tasks, preview of the goal a run creates
/**
 * The tasks a run creates, in the server's order: one per step, waiting for the steps that hand
 * over to it. Null when the steps form a loop. The title follows the server's rule: the step's own,
 * or the agent's name and the first line of the instruction.
 */
export function compileToTasks(
  graph: WorkflowGraph,
  agentName: (agentId: string | null) => string | null,
  values: Record<string, string> = {},
): CompiledTask[] | null {
  const order = executionOrder(graph)
  if (!order) return null
  const position = new Map(order.map((node, i) => [node.id, i]))
  const before = predecessors(graph)
  return order.map((node) => ({
    nodeId: node.id,
    agentId: node.agentId,
    title: taskTitle(node, agentName(node.agentId), values),
    dependsOn: (before.get(node.id) ?? []).map((id) => position.get(id)!).sort((a, b) => a - b),
  }))
}

/** A task's title as the server writes it. */
export function taskTitle(node: WorkflowNode, agentName: string | null, values: Record<string, string> = {}): string {
  const own = fillPlaceholders(node.title?.trim() ?? '', values)
  if (own) return own.slice(0, MAX_TITLE)
  let line = fillPlaceholders(node.instruction, values).trim().split('\n')[0]?.trim() ?? ''
  if (line.length > 80) line = `${line.slice(0, 79).trim()}…`
  const who = agentName ?? 'Step'
  return (line ? `${who}: ${line}` : who).slice(0, MAX_TITLE)
}

/* ---- Validation ------------------------------------------------------------------------------- */

/**
 * What the canvas knows about an agent: its name, whether it can take work, and its granted
 * connectors (null when the role cannot read agent configuration, so grants are not checked here).
 */
export type PaletteAgentInfo = { id: string; name: string; status: string; connectors: string[] | null }

/** Whether an agent is known to lack a connector; false when its grants are not known. */
export function lacksConnector(agent: Pick<PaletteAgentInfo, 'connectors'> | undefined, server: string): boolean {
  return Boolean(agent?.connectors) && !agent!.connectors!.includes(server)
}

/** One problem with the workflow. `error` stops a save; `warning` (a missing grant) only stops a run. */
export type Problem = {
  code: string
  message: string
  severity: 'error' | 'warning'
  nodeId?: string | undefined
  edgeId?: string | undefined
}

/** How a message names a step: its title, else "the step for" its agent, else "a step". */
function stepLabel(node: WorkflowNode, agent: PaletteAgentInfo | undefined): string {
  if (node.title?.trim()) return `the step "${node.title.trim()}"`
  if (agent) return `the step for ${agent.name}`
  return 'a step'
}

const capitalise = (text: string) => (text ? text.charAt(0).toUpperCase() + text.slice(1) : text)

// @find: validate workflow on the canvas, problems list, warnings
/**
 * Every problem the server would report, worded the same way. Agents missing from `agents` are
 * treated as removed or retired (the palette leaves retired agents out). Connectors missing from
 * `connectorName` are unknown. Pure.
 */
export function validateGraph(
  graph: WorkflowGraph,
  agents: ReadonlyMap<string, PaletteAgentInfo>,
  connectorName: (server: string) => string | null,
): Problem[] {
  const problems: Problem[] = []
  if (graph.nodes.length === 0) {
    return [{ code: 'empty', message: 'Add at least one step to the workflow.', severity: 'error' }]
  }
  if (graph.nodes.length > MAX_STEPS) {
    problems.push({ code: 'too_many', message: `A workflow can hold at most ${MAX_STEPS} steps.`, severity: 'error' })
  }
  const pausedReported = new Set<string>()
  for (const node of graph.nodes) {
    const agent = node.agentId ? agents.get(node.agentId) : undefined
    const label = stepLabel(node, agent)
    if (!node.agentId) {
      problems.push({ code: 'agent_missing', message: `${capitalise(label)} has no agent. Choose who does this step.`, severity: 'error', nodeId: node.id })
    } else if (!agent) {
      problems.push({
        code: 'agent_missing',
        message: `${capitalise(label)} is assigned to an agent that is retired or no longer exists. Choose another agent.`,
        severity: 'error',
        nodeId: node.id,
      })
    } else if (agent.status !== 'active' && !pausedReported.has(agent.id)) {
      pausedReported.add(agent.id)
      problems.push({
        code: 'agent_paused',
        message: `${agent.name} is paused. Resume the agent on the Agents page, or choose another agent for this step.`,
        severity: 'error',
        nodeId: node.id,
      })
    }
    if (!node.instruction.trim()) {
      problems.push({
        code: 'instruction_missing',
        message: `${capitalise(label)} has no instruction. Write what ${agent?.name ?? 'the agent'} should do.`,
        severity: 'error',
        nodeId: node.id,
      })
    } else if (node.instruction.trim().length > MAX_INSTRUCTION) {
      problems.push({
        code: 'instruction_long',
        message: `${capitalise(label)} has an instruction over ${MAX_INSTRUCTION.toLocaleString('en-AU')} characters.`,
        severity: 'error',
        nodeId: node.id,
      })
    }
    if ((node.title?.trim().length ?? 0) > MAX_TITLE) {
      problems.push({ code: 'title_long', message: `A step title can be at most ${MAX_TITLE} characters.`, severity: 'error', nodeId: node.id })
    }
    for (const server of node.connectors) {
      const name = connectorName(server)
      if (!name) {
        problems.push({ code: 'connector_unknown', message: `There is no connector called "${server}". Remove it from ${label}.`, severity: 'error', nodeId: node.id })
      } else if (agent && lacksConnector(agent, server)) {
        problems.push({
          code: 'grant_missing',
          message: `${agent.name} is not allowed to use ${name}. Give ${agent.name} access to ${name} on the Connectors page before running this workflow.`,
          severity: 'warning',
          nodeId: node.id,
        })
      }
    }
  }

  const byAgent = new Map<string, WorkflowNode[]>()
  for (const node of graph.nodes) {
    if (node.agentId) byAgent.set(node.agentId, [...(byAgent.get(node.agentId) ?? []), node])
  }
  for (const [agentId, nodes] of byAgent) {
    if (nodes.length > MAX_STEPS_PER_AGENT) {
      const name = agents.get(agentId)?.name ?? 'One agent'
      problems.push({
        code: 'agent_repeats',
        message: `${name} does ${nodes.length} steps. An agent can take at most ${MAX_STEPS_PER_AGENT} steps in one workflow; give the others to another agent.`,
        severity: 'error',
        nodeId: nodes[MAX_STEPS_PER_AGENT]!.id,
      })
    }
  }

  const names = placeholders(graph)
  if (names.length > MAX_INPUTS) {
    problems.push({
      code: 'placeholders_limit',
      message: `The steps ask for ${names.length} different values in double braces. A run can fill in at most ${MAX_INPUTS}; use fewer names.`,
      severity: 'error',
    })
  }

  const loop = findCycle(graph)
  if (loop.length > 0) {
    const byId = new Map(graph.nodes.map((node) => [node.id, node]))
    const names = loop.map((id) => {
      const node = byId.get(id)!
      return stepLabel(node, node.agentId ? agents.get(node.agentId) : undefined)
    })
    problems.push({
      code: 'cycle',
      message: `These steps wait for each other in a loop, so none of them could ever start: ${[...names, names[0]].join(' to ')}. Remove one of those connections.`,
      severity: 'error',
      nodeId: loop[0],
    })
  }
  return problems
}

/* ---- Geometry --------------------------------------------------------------------------------- */

export type Point = { x: number; y: number }
export type Viewport = { x: number; y: number; zoom: number }

/** Keeps a zoom level within the canvas's range. */
export function clampZoom(zoom: number): number {
  return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom))
}

/** The smallest box around every step, or null for an empty canvas. */
export function graphBounds(graph: WorkflowGraph): { minX: number; minY: number; maxX: number; maxY: number } | null {
  if (graph.nodes.length === 0) return null
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const node of graph.nodes) {
    minX = Math.min(minX, node.x)
    minY = Math.min(minY, node.y)
    maxX = Math.max(maxX, node.x + NODE_WIDTH)
    maxY = Math.max(maxY, node.y + NODE_HEIGHT)
  }
  return { minX, minY, maxX, maxY }
}

/** The smallest zoom a fitted view uses on a narrow canvas, where anything smaller cannot be read. */
export const READABLE_ZOOM = 0.8
/** Below this canvas width, a fitted view keeps the steps readable rather than showing them all. */
export const NARROW_CANVAS = 640

// @find: fit view, zoom to fit, centre the canvas, readable zoom on a phone
/**
 * The viewport that shows every step inside a box of the given size, never zoomed in past 100%
 * and never out past `minZoom`. When the steps do not fit at `minZoom`, the view starts at the
 * first steps (top left of the drawing) instead of centring, so the start of the work is readable
 * and the rest is a pan away. Each axis that does fit is centred.
 */
export function fitViewport(graph: WorkflowGraph, width: number, height: number, padding = 32, minZoom = MIN_ZOOM): Viewport {
  const bounds = graphBounds(graph)
  if (!bounds || width <= 0 || height <= 0) return { x: padding, y: padding, zoom: 1 }
  const contentWidth = bounds.maxX - bounds.minX
  const contentHeight = bounds.maxY - bounds.minY
  const floor = clampZoom(Math.min(1, minZoom))
  const zoom = Math.max(floor, clampZoom(Math.min(1, (width - padding * 2) / contentWidth, (height - padding * 2) / contentHeight)))
  const place = (box: number, content: number, min: number) =>
    content * zoom <= box - padding * 2 ? Math.round((box - content * zoom) / 2 - min * zoom) : Math.round(padding - min * zoom)
  return { x: place(width, contentWidth, bounds.minX), y: place(height, contentHeight, bounds.minY), zoom }
}

/** The fitted view for a canvas box: readable on a narrow box (a phone), everything in view otherwise. */
export function fitForBox(graph: WorkflowGraph, width: number, height: number): Viewport {
  const narrow = width > 0 && width < NARROW_CANVAS
  return fitViewport(graph, width, height, narrow ? 16 : 32, narrow ? READABLE_ZOOM : MIN_ZOOM)
}

/** Zooms by a factor keeping one screen point still (the pointer, or the centre). */
export function zoomAround(viewport: Viewport, factor: number, anchor: Point): Viewport {
  const zoom = clampZoom(viewport.zoom * factor)
  const scale = zoom / viewport.zoom
  return { zoom, x: anchor.x - (anchor.x - viewport.x) * scale, y: anchor.y - (anchor.y - viewport.y) * scale }
}

/** A screen point (relative to the canvas box) in canvas coordinates. */
export function toCanvas(viewport: Viewport, point: Point): Point {
  return { x: (point.x - viewport.x) / viewport.zoom, y: (point.y - viewport.y) / viewport.zoom }
}

/** Where a connection leaves a step: the middle of its right side. */
export function outPort(node: Pick<WorkflowNode, 'x' | 'y'>): Point {
  return { x: node.x + NODE_WIDTH, y: node.y + NODE_HEIGHT / 2 }
}

/** Where a connection enters a step: the middle of its left side. */
export function inPort(node: Pick<WorkflowNode, 'x' | 'y'>): Point {
  return { x: node.x, y: node.y + NODE_HEIGHT / 2 }
}

/** The two control points of the curve between two ports. */
function controls(from: Point, to: Point): [Point, Point] {
  const reach = Math.max(48, Math.abs(to.x - from.x) / 2)
  return [
    { x: from.x + reach, y: from.y },
    { x: to.x - reach, y: to.y },
  ]
}

// @find: edge path, bezier curve between steps
/** The SVG path of a connection drawn from one port to another. */
export function edgePath(from: Point, to: Point): string {
  const [c1, c2] = controls(from, to)
  return `M ${from.x} ${from.y} C ${c1.x} ${c1.y}, ${c2.x} ${c2.y}, ${to.x} ${to.y}`
}

/** The point a fraction `t` (0 to 1) of the way along that curve, for a token travelling on it. */
export function bezierPoint(from: Point, to: Point, t: number): Point {
  const [c1, c2] = controls(from, to)
  const u = 1 - Math.min(1, Math.max(0, t))
  const s = 1 - u
  return {
    x: u * u * u * from.x + 3 * u * u * s * c1.x + 3 * u * s * s * c2.x + s * s * s * to.x,
    y: u * u * u * from.y + 3 * u * u * s * c1.y + 3 * u * s * s * c2.y + s * s * s * to.y,
  }
}

// @find: hit test step under pointer, node at point
/** The topmost step under a canvas point, if any. */
export function nodeAt(graph: WorkflowGraph, point: Point): WorkflowNode | undefined {
  for (let i = graph.nodes.length - 1; i >= 0; i -= 1) {
    const node = graph.nodes[i]!
    if (point.x >= node.x && point.x <= node.x + NODE_WIDTH && point.y >= node.y && point.y <= node.y + NODE_HEIGHT) return node
  }
  return undefined
}

const COLUMN_GAP = 96
const ROW_GAP = 40

// @find: place a new step, next free position
/** A free spot for a new step: to the right of the right-most step on its row, or the origin on an empty canvas. */
export function nextPosition(graph: WorkflowGraph, near?: Point): Point {
  if (graph.nodes.length === 0) return near ? { x: Math.round(near.x), y: Math.round(near.y) } : { x: 40, y: 40 }
  const start = near ?? { x: Math.max(...graph.nodes.map((node) => node.x)) + NODE_WIDTH + COLUMN_GAP, y: graph.nodes[graph.nodes.length - 1]!.y }
  let candidate = { x: Math.round(start.x), y: Math.round(start.y) }
  for (let attempt = 0; attempt < 60; attempt += 1) {
    const overlaps = graph.nodes.some(
      (node) => Math.abs(node.x - candidate.x) < NODE_WIDTH + 16 && Math.abs(node.y - candidate.y) < NODE_HEIGHT + 16,
    )
    if (!overlaps) return candidate
    candidate = { x: candidate.x, y: candidate.y + NODE_HEIGHT + ROW_GAP }
  }
  return candidate
}

// @find: auto layout, tidy up, layered layout of steps
/**
 * Columns by depth: a step sits one column right of the deepest step that hands over to it, and
 * steps in a column stack top to bottom in their current order. Used by "Tidy up" and to draw a
 * goal that no canvas created. Loops are broken by leaving the remaining steps in the last column.
 */
export function layeredLayout(ids: readonly string[], before: ReadonlyMap<string, readonly string[]>): Map<string, Point> {
  const depth = new Map<string, number>()
  const visiting = new Set<string>()
  const depthOf = (id: string): number => {
    const known = depth.get(id)
    if (known !== undefined) return known
    if (visiting.has(id)) return 0
    visiting.add(id)
    const parents = (before.get(id) ?? []).filter((parent) => ids.includes(parent))
    const value = parents.length === 0 ? 0 : Math.max(...parents.map(depthOf)) + 1
    visiting.delete(id)
    depth.set(id, value)
    return value
  }
  const rows = new Map<number, number>()
  const positions = new Map<string, Point>()
  for (const id of ids) {
    const column = depthOf(id)
    const row = rows.get(column) ?? 0
    rows.set(column, row + 1)
    positions.set(id, { x: 40 + column * (NODE_WIDTH + COLUMN_GAP), y: 40 + row * (NODE_HEIGHT + ROW_GAP) })
  }
  return positions
}

/** The graph with every step moved to its place in the layered layout. */
export function tidyGraph(graph: WorkflowGraph): WorkflowGraph {
  const order = executionOrder(graph) ?? graph.nodes
  const positions = layeredLayout(
    order.map((node) => node.id),
    predecessors(graph),
  )
  return { ...graph, nodes: graph.nodes.map((node) => ({ ...node, ...positions.get(node.id)! })) }
}
