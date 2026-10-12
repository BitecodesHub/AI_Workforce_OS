// @find: tests for workflow graph, add step, remove step, connect, refuse self duplicate loop, cycle detection, execution order, step numbers, compile to tasks, depends on, placeholders, validate workflow, problems, normalise graph, fit view, zoom, bezier, hit test, next position, tidy up
// @what: Tests for the pure workflow canvas model in lib/workflowGraph.
// @flow: Exercises lib/workflowGraph directly
import { describe, expect, it } from 'vitest'
import {
  NODE_HEIGHT,
  NODE_WIDTH,
  addConnector,
  addNode,
  bezierPoint,
  canConnect,
  canonical,
  compileToTasks,
  connect,
  emptyGraph,
  executionOrder,
  fillPlaceholders,
  findCycle,
  fitForBox,
  fitViewport,
  lacksConnector,
  moveNodes,
  nextPosition,
  nodeAt,
  normaliseGraph,
  placeholders,
  removeConnector,
  removeEdges,
  removeNodes,
  sameGraph,
  stepNumbers,
  taskTitle,
  tidyGraph,
  updateNode,
  validateGraph,
  zoomAround,
} from './workflowGraph'
import type { PaletteAgentInfo, WorkflowGraph, WorkflowNode } from './workflowGraph'

const node = (id: string, x: number, y: number, extra: Partial<WorkflowNode> = {}): WorkflowNode => ({
  id,
  type: 'agent',
  agentId: 'rhea',
  title: null,
  instruction: `Do ${id}.`,
  connectors: [],
  x,
  y,
  ...extra,
})

const graphOf = (nodes: WorkflowNode[], edges: Array<[string, string]> = []): WorkflowGraph => ({
  nodes,
  edges: edges.map(([from, to], index) => ({ id: `e${index}`, from, to })),
})

const AGENTS = new Map<string, PaletteAgentInfo>([
  ['rhea', { id: 'rhea', name: 'Rhea', status: 'active', connectors: ['github'] }],
  ['mo', { id: 'mo', name: 'Mo', status: 'active', connectors: [] }],
  ['sleepy', { id: 'sleepy', name: 'Sleepy', status: 'paused', connectors: [] }],
])
const CONNECTORS: Record<string, string> = { github: 'GitHub', gmail: 'Gmail' }
const connectorName = (id: string) => CONNECTORS[id] ?? null
const codes = (graph: WorkflowGraph) => validateGraph(graph, AGENTS, connectorName).map((problem) => problem.code)

describe('edits', () => {
  it('adds a step with a fresh id at a rounded position, and moves, updates and removes steps', () => {
    const first = addNode(emptyGraph(), { agentId: 'rhea', x: 10.4, y: 20.6 })
    expect(first.id).toBe('step-1')
    expect(first.graph.nodes[0]).toMatchObject({ agentId: 'rhea', x: 10, y: 21, instruction: '', connectors: [] })
    const second = addNode(first.graph, { agentId: 'mo', x: 300, y: 0 })
    expect(second.id).toBe('step-2')

    const moved = moveNodes(second.graph, ['step-1'], 5.5, -1)
    expect(moved.nodes[0]).toMatchObject({ x: 16, y: 20 })
    expect(moved.nodes[1]).toMatchObject({ x: 300, y: 0 })

    const updated = updateNode(moved, 'step-2', { instruction: 'Draft it.' })
    expect(updated.nodes[1]!.instruction).toBe('Draft it.')

    const linked = connect(updated, 'step-1', 'step-2').graph
    const removed = removeNodes(linked, ['step-1'])
    expect(removed.nodes.map((n) => n.id)).toEqual(['step-2'])
    expect(removed.edges).toEqual([])
  })

  it('never reuses an id that is still taken', () => {
    const graph = graphOf([node('step-2', 0, 0)])
    expect(addNode(graph, { agentId: null, x: 0, y: 0 }).id).toBe('step-3')
  })

  it('adds and removes required connectors once each, up to ten', () => {
    let graph = graphOf([node('a', 0, 0)])
    graph = addConnector(graph, 'a', 'gmail')
    graph = addConnector(graph, 'a', 'gmail')
    expect(graph.nodes[0]!.connectors).toEqual(['gmail'])
    for (let i = 0; i < 12; i += 1) graph = addConnector(graph, 'a', `c${i}`)
    expect(graph.nodes[0]!.connectors).toHaveLength(10)
    graph = removeConnector(graph, 'a', 'gmail')
    expect(graph.nodes[0]!.connectors).not.toContain('gmail')
  })
})

describe('connecting', () => {
  const graph = graphOf([node('a', 0, 0), node('b', 300, 0), node('c', 600, 0)], [['a', 'b'], ['b', 'c']])

  it('connects two steps and names the new connection', () => {
    const result = connect(graph, 'a', 'c')
    expect(result.refusal).toBeNull()
    expect(result.graph.edges).toHaveLength(3)
    expect(result.graph.edges[2]).toMatchObject({ from: 'a', to: 'c' })
  })

  it('refuses a step to itself, a repeat, a missing step and anything that would make a loop', () => {
    expect(canConnect(graph, 'a', 'a')).toBe('self')
    expect(canConnect(graph, 'a', 'b')).toBe('duplicate')
    expect(canConnect(graph, 'a', 'ghost')).toBe('missing')
    expect(canConnect(graph, 'c', 'a')).toBe('cycle')
    expect(canConnect(graph, 'b', 'a')).toBe('cycle')
    expect(connect(graph, 'c', 'a').graph).toBe(graph)
  })

  it('removes a connection by id', () => {
    expect(removeEdges(graph, ['e0']).edges.map((edge) => edge.id)).toEqual(['e1'])
  })
})

describe('graph questions', () => {
  it('finds a loop with the steps on it, and none in a diamond', () => {
    const loop = graphOf([node('a', 0, 0), node('b', 1, 0), node('c', 2, 0)], [['a', 'b'], ['b', 'c'], ['c', 'b']])
    expect(findCycle(loop)).toEqual(['b', 'c'])
    expect(executionOrder(loop)).toBeNull()

    const diamond = graphOf(
      [node('a', 0, 0), node('b', 1, 0), node('c', 1, 1), node('d', 2, 0)],
      [['a', 'b'], ['a', 'c'], ['b', 'd'], ['c', 'd']],
    )
    expect(findCycle(diamond)).toEqual([])
  })

  it('orders steps after what they wait for, then left to right and top to bottom, as the server does', () => {
    const graph = graphOf(
      [node('draft', 400, 0), node('research', 0, 100), node('facts', 0, 0), node('review', 800, 0)],
      [['research', 'draft'], ['facts', 'draft'], ['draft', 'review']],
    )
    expect(executionOrder(graph)!.map((n) => n.id)).toEqual(['facts', 'research', 'draft', 'review'])
    expect(stepNumbers(graph).get('draft')).toBe(3)
  })

  it('numbers steps in canvas order when they form a loop', () => {
    const loop = graphOf([node('a', 500, 0), node('b', 0, 0)], [['a', 'b'], ['b', 'a']])
    expect([...stepNumbers(loop).entries()]).toEqual([
      ['a', 1],
      ['b', 2],
    ])
  })
})

describe('compiling to tasks', () => {
  it('makes one task per step with dependsOn positions from the connections and the server titles', () => {
    const graph = graphOf(
      [
        node('draft', 400, 0, { agentId: 'mo', title: 'Draft the reply', instruction: 'Draft it for {{customer}}.' }),
        node('facts', 0, 0, { instruction: 'Find facts about {{ customer }}.\nThen stop.' }),
        node('research', 0, 100, { agentId: 'mo', instruction: 'Read the tickets.' }),
      ],
      [['research', 'draft'], ['facts', 'draft']],
    )
    const tasks = compileToTasks(graph, (id) => AGENTS.get(id ?? '')?.name ?? null, { customer: 'Acme' })!
    expect(tasks.map((task) => task.nodeId)).toEqual(['facts', 'research', 'draft'])
    expect(tasks.map((task) => task.dependsOn)).toEqual([[], [], [0, 1]])
    expect(tasks.map((task) => task.title)).toEqual(['Rhea: Find facts about Acme.', 'Mo: Read the tickets.', 'Draft the reply'])
    expect(compileToTasks(graphOf([node('a', 0, 0), node('b', 1, 0)], [['a', 'b'], ['b', 'a']]), () => null)).toBeNull()
  })

  it('shortens a long first line and falls back to the agent name alone', () => {
    const long = node('a', 0, 0, { instruction: 'x'.repeat(120) })
    expect(taskTitle(long, 'Rhea')).toHaveLength('Rhea: '.length + 80)
    expect(taskTitle(node('b', 0, 0, { instruction: '' }), null)).toBe('Step')
  })

  it('lists placeholders once each and fills only those with a value', () => {
    const graph = graphOf([
      node('a', 0, 0, { instruction: '{{customer}} for {{ month }}' }),
      node('b', 0, 0, { instruction: 'again {{customer}} and {{not closed' }),
    ])
    expect(placeholders(graph)).toEqual(['customer', 'month'])
    expect(fillPlaceholders('Hi {{ customer }}, {{month}}', { customer: 'Acme' })).toBe('Hi Acme, {{month}}')
  })
})

describe('validation', () => {
  it('passes a complete workflow and refuses an empty canvas', () => {
    expect(codes(graphOf([node('a', 0, 0), node('b', 1, 0, { agentId: 'mo' })], [['a', 'b']]))).toEqual([])
    expect(codes(emptyGraph())).toEqual(['empty'])
  })

  it('reports a missing agent, an unknown or retired agent, a blank instruction and an unknown connector as errors', () => {
    const graph = graphOf([
      node('a', 0, 0, { agentId: null, title: 'Unassigned' }),
      node('b', 0, 0, { agentId: 'gone' }),
      node('c', 0, 0, { instruction: '  ' }),
      node('d', 0, 0, { agentId: 'mo', connectors: ['fax'] }),
    ])
    const problems = validateGraph(graph, AGENTS, connectorName)
    expect(problems.map((p) => [p.code, p.severity, p.nodeId])).toEqual([
      ['agent_missing', 'error', 'a'],
      ['agent_missing', 'error', 'b'],
      ['instruction_missing', 'error', 'c'],
      ['connector_unknown', 'error', 'd'],
    ])
    expect(problems[0]!.message).toBe('The step "Unassigned" has no agent. Choose who does this step.')
    expect(problems[2]!.message).toBe('The step for Rhea has no instruction. Write what Rhea should do.')
  })

  it('refuses a paused agent once per agent, and warns about each connector the agent is not granted', () => {
    const graph = graphOf([
      node('a', 0, 0, { agentId: 'sleepy' }),
      node('b', 1, 0, { agentId: 'sleepy' }),
      node('c', 2, 0, { connectors: ['github', 'gmail'] }),
    ])
    const problems = validateGraph(graph, AGENTS, connectorName)
    expect(problems.map((p) => [p.code, p.severity])).toEqual([
      ['agent_paused', 'error'],
      ['grant_missing', 'warning'],
    ])
    expect(problems[0]!.message).toBe('Sleepy is paused. Resume the agent on the Agents page, or choose another agent for this step.')
    expect(problems[1]!.message).toBe(
      'Rhea is not allowed to use Gmail. Give Rhea access to Gmail on the Connectors page before running this workflow.',
    )
  })

  it('does not check grants it cannot see: a role without agent configuration gets no grant warning', () => {
    const unknown = new Map<string, PaletteAgentInfo>([['rhea', { id: 'rhea', name: 'Rhea', status: 'active', connectors: null }]])
    const graph = graphOf([node('a', 0, 0, { connectors: ['gmail'] })])
    expect(validateGraph(graph, unknown, connectorName)).toEqual([])
    expect(lacksConnector({ connectors: null }, 'gmail')).toBe(false)
    expect(lacksConnector({ connectors: ['github'] }, 'gmail')).toBe(true)
    expect(lacksConnector(undefined, 'gmail')).toBe(false)
  })

  it('refuses more different placeholders than a run can fill in, since such a workflow could never run', () => {
    const twenty = Array.from({ length: 20 }, (_, index) => `{{value ${index}}}`).join(' ')
    expect(codes(graphOf([node('a', 0, 0, { instruction: twenty })]))).toEqual([])
    const over = graphOf([node('a', 0, 0, { instruction: twenty }), node('b', 1, 0, { agentId: 'mo', instruction: 'And {{value 20}} and {{value 1}}.' })])
    const problems = validateGraph(over, AGENTS, connectorName)
    expect(problems.map((p) => [p.code, p.severity])).toEqual([['placeholders_limit', 'error']])
    expect(problems[0]!.message).toBe('The steps ask for 21 different values in double braces. A run can fill in at most 20; use fewer names.')
  })

  it('refuses an agent on more than two steps and a loop, naming the steps', () => {
    const repeats = graphOf([node('a', 0, 0), node('b', 1, 0), node('c', 2, 0)])
    expect(codes(repeats)).toEqual(['agent_repeats'])
    const loop = graphOf(
      [node('a', 0, 0, { title: 'Research' }), node('b', 1, 0, { agentId: 'mo', title: 'Draft' })],
      [['a', 'b'], ['b', 'a']],
    )
    const problems = validateGraph(loop, AGENTS, connectorName)
    expect(problems.map((p) => p.code)).toEqual(['cycle'])
    expect(problems[0]!.message).toContain('the step "Research" to the step "Draft" to the step "Research"')
  })
})

describe('reading and comparing', () => {
  it('reads a graph defensively, dropping steps without ids and connections to nowhere', () => {
    const graph = normaliseGraph({
      nodes: [{ id: 'a', agentId: 'rhea', x: 'left', connectors: ['github', 3] }, { agentId: 'mo' }, 'junk'],
      edges: [{ from: 'a', to: 'ghost' }, { from: 'a', to: 'a', id: 'self' }],
    })
    expect(graph.nodes).toHaveLength(1)
    expect(graph.nodes[0]).toMatchObject({ id: 'a', x: 0, title: null, instruction: '', connectors: ['github'] })
    expect(graph.edges.map((edge) => edge.id)).toEqual(['self'])
    expect(normaliseGraph('nonsense')).toEqual(emptyGraph())
  })

  it('treats trimming, rounding and an empty title as no change', () => {
    const saved = graphOf([node('a', 10, 20, { instruction: 'Go.' })])
    const edited = graphOf([node('a', 10.2, 19.8, { instruction: ' Go. ', title: '  ' })])
    expect(sameGraph(saved, edited)).toBe(true)
    expect(canonical(edited).nodes[0]).toMatchObject({ x: 10, y: 20, instruction: 'Go.', title: null })
    expect(sameGraph(saved, graphOf([node('a', 10, 20, { instruction: 'Stop.' })]))).toBe(false)
  })
})

describe('geometry', () => {
  it('fits every step inside the box without zooming past 100 percent', () => {
    const graph = graphOf([node('a', 0, 0), node('b', 2000, 1000)])
    const fitted = fitViewport(graph, 800, 600)
    expect(fitted.zoom).toBeLessThan(1)
    expect(fitted.zoom).toBeGreaterThanOrEqual(0.3)
    const small = fitViewport(graphOf([node('a', 100, 100)]), 800, 600)
    expect(small.zoom).toBe(1)
    expect(small.x).toBe(Math.round((800 - NODE_WIDTH) / 2 - 100))
    expect(fitViewport(emptyGraph(), 800, 600)).toEqual({ x: 32, y: 32, zoom: 1 })
  })

  it('keeps steps readable on a narrow canvas: never below the reading zoom, starting at the first steps', () => {
    const wide = graphOf([node('a', 40, 40), node('b', 368, 40), node('c', 696, 40)])
    const phone = fitForBox(wide, 343, 455)
    expect(phone.zoom).toBe(0.8)
    // The first step sits at the left edge, inside the padding, rather than off-centre and tiny.
    expect(phone.x).toBe(Math.round(16 - 40 * 0.8))
    expect(phone.y).toBe(Math.round((455 - NODE_HEIGHT * 0.8) / 2 - 40 * 0.8))
    const desk = fitForBox(wide, 1100, 600)
    expect(desk.zoom).toBe(1)
    // A box of unknown size (not yet laid out) fits as before.
    expect(fitForBox(emptyGraph(), 0, 0)).toEqual({ x: 32, y: 32, zoom: 1 })
    expect(fitViewport(wide, 343, 455, 16, 0.8).zoom).toBe(0.8)
  })

  it('zooms around an anchor point and stays within the zoom range', () => {
    const zoomed = zoomAround({ x: 0, y: 0, zoom: 1 }, 2, { x: 100, y: 100 })
    expect(zoomed).toEqual({ zoom: 2, x: -100, y: -100 })
    expect(zoomAround({ x: 0, y: 0, zoom: 1 }, 100, { x: 0, y: 0 }).zoom).toBe(2)
    expect(zoomAround({ x: 0, y: 0, zoom: 1 }, 0.01, { x: 0, y: 0 }).zoom).toBe(0.3)
  })

  it('puts a token at the ends of a connection at 0 and 1', () => {
    expect(bezierPoint({ x: 0, y: 0 }, { x: 100, y: 50 }, 0)).toEqual({ x: 0, y: 0 })
    const end = bezierPoint({ x: 0, y: 0 }, { x: 100, y: 50 }, 1)
    expect(end.x).toBeCloseTo(100)
    expect(end.y).toBeCloseTo(50)
  })

  it('finds the topmost step under a point', () => {
    const graph = graphOf([node('a', 0, 0), node('b', 100, 50)])
    expect(nodeAt(graph, { x: 120, y: 60 })?.id).toBe('b')
    expect(nodeAt(graph, { x: 10, y: 10 })?.id).toBe('a')
    expect(nodeAt(graph, { x: 1000, y: 1000 })).toBeUndefined()
  })

  it('places a new step where it overlaps nothing', () => {
    const graph = graphOf([node('a', 40, 40), node('b', 40 + NODE_WIDTH + 96, 40)])
    const spot = nextPosition(graph)
    for (const existing of graph.nodes) {
      const overlaps = Math.abs(existing.x - spot.x) < NODE_WIDTH && Math.abs(existing.y - spot.y) < NODE_HEIGHT
      expect(overlaps).toBe(false)
    }
    expect(nextPosition(emptyGraph())).toEqual({ x: 40, y: 40 })
  })

  it('tidies steps into columns by how many steps come before them', () => {
    const graph = graphOf(
      [node('c', 5, 5), node('a', 900, 900), node('b', 0, 0)],
      [['a', 'b'], ['b', 'c']],
    )
    const tidy = tidyGraph(graph)
    const x = (id: string) => tidy.nodes.find((n) => n.id === id)!.x
    expect(x('a')).toBeLessThan(x('b'))
    expect(x('b')).toBeLessThan(x('c'))
  })
})
