// @find: workflow canvas, drag steps, pan, zoom, pinch, wheel zoom, fit view, draw connection, output handle, input handle, drop agent, drop connector, delete selection, keyboard move step, live status on nodes, hand-off token, WorkflowCanvas
// @what: The canvas that draws a workflow's steps and hand-offs and lets a person pan, zoom, move steps, draw connections and drop agents and connectors, or watch statuses and tokens when read-only.
// @flow: Rendered by routes/WorkflowEditor.tsx (edit) and routes/WorkflowRun.tsx (view); geometry and rules from lib/workflowGraph
import { useEffect, useId, useRef, useState } from 'react'
import type {
  CSSProperties,
  DragEvent as ReactDragEvent,
  KeyboardEvent as ReactKeyboardEvent,
  PointerEvent as ReactPointerEvent,
  ReactNode,
} from 'react'
import { AgentAvatar } from '../ui/AgentAvatar'
import {
  NODE_HEIGHT,
  NODE_WIDTH,
  bezierPoint,
  edgePath,
  fitForBox,
  inPort,
  moveNodes,
  nodeAt,
  outPort,
  toCanvas,
  zoomAround,
} from '../../lib/workflowGraph'
import type { Point, Viewport, WorkflowGraph, WorkflowNode } from '../../lib/workflowGraph'
import { STEP_STATUS_LABEL } from '../../lib/workflowReplay'
import type { StepStatus } from '../../lib/workflowReplay'

/*
 * Built on HTML for the steps and one SVG for the connections, inside a single transformed layer,
 * rather than on a canvas library. A workflow holds at most forty steps, so nothing needs
 * virtualising; every step stays a real focusable element a keyboard and a screen reader can
 * reach; the styling comes from the design tokens alone; and the bundle carries no third-party
 * drawing code. Geometry (ports, curves, hit tests, fit) lives in lib/workflowGraph so it is tested
 * without a DOM.
 *
 * Pointer gestures each have a keyboard or button alternative: Add step and the agent list place
 * steps, the step panel connects them, arrow keys move the focused step, Delete removes the
 * selection, and the corner buttons zoom and fit.
 *
 * On a touch screen one finger on the empty canvas scrolls the page up and down (CSS touch-action
 * pan-y) and pans the canvas sideways; two fingers pinch to zoom; a step or a port takes one
 * finger for itself. A fitted view on a narrow canvas never shrinks the steps past reading size.
 */

/** Drag-and-drop types the palette writes. */
export const AGENT_MIME = 'application/x-aiwos-agent'
export const CONNECTOR_MIME = 'application/x-aiwos-connector'

/** What is selected: any number of steps, or one connection. */
export type Selection = { nodes: string[]; edge: string | null }

export const NOTHING_SELECTED: Selection = { nodes: [], edge: null }

/** How one step is drawn: the words and marks the page decides, the canvas only places them. */
export type NodeFace = {
  agentName: string
  agentCategory: string | null
  fallback: boolean
  heading: string
  number: number
  connectors: Array<{ id: string; name: string; missing: boolean }>
  problem: 'error' | 'warning' | null
  problemText: string | null
  status?: StepStatus | undefined
}

/** A token travelling along a connection, a fraction of the way from its start. */
export type Token = { edgeId: string; progress: number }

type Drag =
  | { kind: 'pan'; pointerId: number; startX: number; startY: number; origin: Viewport; moved: boolean }
  | { kind: 'move'; pointerId: number; startX: number; startY: number; ids: string[]; dx: number; dy: number; moved: boolean; nodeId: string }
  | { kind: 'connect'; pointerId: number; from: string; point: Point }
  | { kind: 'pinch'; distance: number; origin: Viewport; centre: Point }

const ZOOM_STEP = 1.2
const MOVE_THRESHOLD = 4
const KEY_MOVE = 16

/** The element a keyboard event came from is a text field, whose keys are its own. */
function typingIn(target: EventTarget | null): boolean {
  return target instanceof HTMLElement && Boolean(target.closest('input, textarea, select, [contenteditable="true"]'))
}

// @find: workflow canvas component
/**
 * The workflow canvas.
 *
 * Props: graph and the viewport (both controlled); mode 'edit' (moves, connections, drops,
 * deletion) or 'view' (read-only, a step opens on activation); selection and onSelect; face, which
 * says how each step is drawn; tokens and activeEdges for hand-offs; fitKey, which fits the view
 * whenever it changes; and the edit callbacks. Fits the view on first draw. Side effects: a
 * non-passive wheel listener for zoom, pointer capture during gestures.
 */
export function WorkflowCanvas({
  graph,
  label,
  mode,
  viewport,
  onViewport,
  selection,
  onSelect,
  face,
  fitKey = 0,
  tokens = [],
  activeEdges,
  reduceMotion = false,
  onChange,
  onConnect,
  onDropAgent,
  onDropConnector,
  onDelete,
  onOpenNode,
  emptyHint,
  overlay,
}: {
  graph: WorkflowGraph
  label: string
  /** 'edit' changes the graph; 'inspect' only selects (a role that cannot edit); 'view' opens a step's run. */
  mode: 'edit' | 'inspect' | 'view'
  viewport: Viewport
  onViewport: (viewport: Viewport) => void
  selection: Selection
  onSelect: (selection: Selection) => void
  face: (node: WorkflowNode) => NodeFace
  fitKey?: number
  tokens?: readonly Token[]
  activeEdges?: ReadonlySet<string>
  reduceMotion?: boolean
  onChange?: (graph: WorkflowGraph) => void
  onConnect?: (from: string, to: string) => void
  onDropAgent?: (agentId: string, at: Point) => void
  onDropConnector?: (server: string, nodeId: string | null) => void
  onDelete?: () => void
  onOpenNode?: (nodeId: string) => void
  emptyHint?: string
  /** Controls drawn over the canvas's bottom-left corner, such as a way to the selected step's panel. */
  overlay?: ReactNode
}) {
  const boxRef = useRef<HTMLDivElement>(null)
  const [drag, setDrag] = useState<Drag | null>(null)
  const [dropTarget, setDropTarget] = useState<string | null>(null)
  const pointers = useRef(new Map<number, Point>())
  const markerId = `wf-arrow-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
  const helpId = `${markerId}-help`
  const editing = mode === 'edit'
  const selecting = mode !== 'view'

  // The latest viewport and callback for the wheel listener, which is attached once.
  const latest = useRef({ viewport, onViewport })
  useEffect(() => {
    latest.current = { viewport, onViewport }
  })

  // Wheel and trackpad pinch zoom around the pointer. Non-passive, so the page does not scroll
  // while the pointer is over the canvas.
  useEffect(() => {
    const box = boxRef.current
    if (!box) return
    const onWheel = (event: WheelEvent) => {
      event.preventDefault()
      const rect = box.getBoundingClientRect()
      const factor = Math.exp(-event.deltaY * (event.ctrlKey ? 0.01 : 0.0015))
      const { viewport: current, onViewport: update } = latest.current
      update(zoomAround(current, factor, { x: event.clientX - rect.left, y: event.clientY - rect.top }))
    }
    box.addEventListener('wheel', onWheel, { passive: false })
    return () => box.removeEventListener('wheel', onWheel)
  }, [])

  // Fit the view on first draw and whenever the page asks (a different graph, Tidy up).
  const hasNodes = graph.nodes.length > 0
  const fitted = useRef<number | null>(null)
  useEffect(() => {
    if (!hasNodes || fitted.current === fitKey) return
    fitted.current = fitKey
    const rect = boxRef.current?.getBoundingClientRect()
    latest.current.onViewport(fitForBox(graph, rect?.width ?? 0, rect?.height ?? 0))
  }, [fitKey, hasNodes, graph])

  const boxPoint = (clientX: number, clientY: number): Point => {
    const rect = boxRef.current?.getBoundingClientRect()
    return { x: clientX - (rect?.left ?? 0), y: clientY - (rect?.top ?? 0) }
  }
  const canvasPoint = (clientX: number, clientY: number) => toCanvas(viewport, boxPoint(clientX, clientY))

  const fit = () => {
    const rect = boxRef.current?.getBoundingClientRect()
    onViewport(fitForBox(graph, rect?.width ?? 0, rect?.height ?? 0))
  }
  const zoomBy = (factor: number) => {
    const rect = boxRef.current?.getBoundingClientRect()
    onViewport(zoomAround(viewport, factor, { x: (rect?.width ?? 0) / 2, y: (rect?.height ?? 0) / 2 }))
  }

  /* ---- Pointer gestures ------------------------------------------------------------------------ */

  const capture = (event: ReactPointerEvent) => {
    try {
      ;(event.currentTarget as Element).setPointerCapture?.(event.pointerId)
    } catch {
      // A pointer that already ended cannot be captured; the gesture still works without it.
    }
  }

  const onBackgroundPointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (event.button !== 0 && event.pointerType === 'mouse') return
    pointers.current.set(event.pointerId, { x: event.clientX, y: event.clientY })
    if (pointers.current.size === 2) {
      const [a, b] = [...pointers.current.values()] as [Point, Point]
      setDrag({
        kind: 'pinch',
        distance: Math.hypot(a.x - b.x, a.y - b.y) || 1,
        origin: viewport,
        centre: boxPoint((a.x + b.x) / 2, (a.y + b.y) / 2),
      })
      return
    }
    capture(event)
    setDrag({ kind: 'pan', pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, origin: viewport, moved: false })
  }

  const onNodePointerDown = (event: ReactPointerEvent<HTMLDivElement>, node: WorkflowNode) => {
    if (event.button !== 0 && event.pointerType === 'mouse') return
    event.stopPropagation()
    if (!editing) {
      if (selecting) onSelect({ nodes: [node.id], edge: null })
      capture(event)
      setDrag({ kind: 'move', pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, ids: [], dx: 0, dy: 0, moved: false, nodeId: node.id })
      return
    }
    const already = selection.nodes.includes(node.id)
    let ids = selection.nodes
    if (event.shiftKey) {
      ids = already ? selection.nodes.filter((id) => id !== node.id) : [...selection.nodes, node.id]
      onSelect({ nodes: ids, edge: null })
    } else if (!already) {
      ids = [node.id]
      onSelect({ nodes: ids, edge: null })
    }
    capture(event)
    setDrag({
      kind: 'move',
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      ids: ids.includes(node.id) ? ids : [node.id],
      dx: 0,
      dy: 0,
      moved: false,
      nodeId: node.id,
    })
  }

  const onPortPointerDown = (event: ReactPointerEvent<HTMLSpanElement>, node: WorkflowNode) => {
    event.stopPropagation()
    if (!editing) return
    capture(event)
    setDrag({ kind: 'connect', pointerId: event.pointerId, from: node.id, point: canvasPoint(event.clientX, event.clientY) })
  }

  const onPointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (pointers.current.has(event.pointerId)) pointers.current.set(event.pointerId, { x: event.clientX, y: event.clientY })
    if (!drag) return
    if (drag.kind === 'pinch') {
      const values = [...pointers.current.values()]
      if (values.length < 2) return
      const [a, b] = values as [Point, Point]
      const distance = Math.hypot(a.x - b.x, a.y - b.y) || 1
      onViewport(zoomAround(drag.origin, distance / drag.distance, drag.centre))
      return
    }
    if (event.pointerId !== drag.pointerId) return
    if (drag.kind === 'pan') {
      const dx = event.clientX - drag.startX
      const dy = event.clientY - drag.startY
      if (!drag.moved && Math.hypot(dx, dy) < MOVE_THRESHOLD) return
      if (!drag.moved) setDrag({ ...drag, moved: true })
      onViewport({ ...drag.origin, x: drag.origin.x + dx, y: drag.origin.y + dy })
    } else if (drag.kind === 'move') {
      const dx = (event.clientX - drag.startX) / viewport.zoom
      const dy = (event.clientY - drag.startY) / viewport.zoom
      const moved = drag.moved || Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY) >= MOVE_THRESHOLD
      if (editing && moved) setDrag({ ...drag, dx, dy, moved })
      else if (moved !== drag.moved) setDrag({ ...drag, moved })
    } else {
      setDrag({ ...drag, point: canvasPoint(event.clientX, event.clientY) })
    }
  }

  const endGesture = (event: ReactPointerEvent<HTMLDivElement>, cancelled: boolean) => {
    pointers.current.delete(event.pointerId)
    if (!drag) return
    if (drag.kind === 'pinch') {
      if (pointers.current.size < 2) setDrag(null)
      return
    }
    if (event.pointerId !== drag.pointerId) return
    setDrag(null)
    if (cancelled) return
    if (drag.kind === 'pan' && !drag.moved) {
      onSelect(NOTHING_SELECTED)
    } else if (drag.kind === 'move') {
      if (mode === 'view') {
        if (!drag.moved) onOpenNode?.(drag.nodeId)
      } else if (editing && drag.moved) {
        onChange?.(moveNodes(graph, drag.ids, drag.dx, drag.dy))
      }
    } else if (drag.kind === 'connect') {
      const target = nodeAt(graph, canvasPoint(event.clientX, event.clientY))
      if (target && target.id !== drag.from) onConnect?.(drag.from, target.id)
    }
  }

  /* ---- Keyboard -------------------------------------------------------------------------------- */

  const onKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (typingIn(event.target)) return
    const nodeId = (event.target as HTMLElement).closest<HTMLElement>('[data-node-id]')?.dataset.nodeId ?? null
    if ((event.key === 'Delete' || event.key === 'Backspace') && editing) {
      if (selection.nodes.length > 0 || selection.edge) {
        event.preventDefault()
        onDelete?.()
        // The focused step is gone; keep the keyboard on the canvas rather than the page top.
        boxRef.current?.focus()
      }
      return
    }
    if (event.key === 'Escape') {
      if (selection.nodes.length > 0 || selection.edge) {
        event.preventDefault()
        onSelect(NOTHING_SELECTED)
      }
      return
    }
    if (event.key === '+' || event.key === '=') {
      event.preventDefault()
      zoomBy(ZOOM_STEP)
      return
    }
    if (event.key === '-' || event.key === '_') {
      event.preventDefault()
      zoomBy(1 / ZOOM_STEP)
      return
    }
    if (event.key === '0') {
      event.preventDefault()
      fit()
      return
    }
    const arrows: Record<string, Point> = { ArrowLeft: { x: -1, y: 0 }, ArrowRight: { x: 1, y: 0 }, ArrowUp: { x: 0, y: -1 }, ArrowDown: { x: 0, y: 1 } }
    const direction = arrows[event.key]
    if (!direction) return
    event.preventDefault()
    if (nodeId && editing) {
      const step = event.shiftKey ? KEY_MOVE * 4 : KEY_MOVE
      const ids = selection.nodes.includes(nodeId) ? selection.nodes : [nodeId]
      if (!selection.nodes.includes(nodeId)) onSelect({ nodes: [nodeId], edge: null })
      onChange?.(moveNodes(graph, ids, direction.x * step, direction.y * step))
    } else if (!nodeId) {
      onViewport({ ...viewport, x: viewport.x - direction.x * 40, y: viewport.y - direction.y * 40 })
    }
  }

  const onNodeKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>, node: WorkflowNode) => {
    if (event.key !== 'Enter' && event.key !== ' ') return
    event.preventDefault()
    if (selecting) onSelect({ nodes: [node.id], edge: null })
    else onOpenNode?.(node.id)
  }

  /* ---- Drag and drop from the palette ---------------------------------------------------------- */

  const dropKind = (event: ReactDragEvent): 'agent' | 'connector' | null => {
    const types = Array.from(event.dataTransfer?.types ?? [])
    if (types.includes(AGENT_MIME)) return 'agent'
    if (types.includes(CONNECTOR_MIME)) return 'connector'
    return null
  }

  const onDragOver = (event: ReactDragEvent<HTMLDivElement>) => {
    const kind = editing ? dropKind(event) : null
    if (!kind) return
    event.preventDefault()
    event.dataTransfer.dropEffect = 'copy'
    if (kind === 'connector') {
      const target = nodeAt(graph, canvasPoint(event.clientX, event.clientY))?.id ?? null
      if (target !== dropTarget) setDropTarget(target)
    }
  }

  const onDrop = (event: ReactDragEvent<HTMLDivElement>) => {
    const kind = editing ? dropKind(event) : null
    setDropTarget(null)
    if (!kind) return
    event.preventDefault()
    const point = canvasPoint(event.clientX, event.clientY)
    if (kind === 'agent') {
      const agentId = event.dataTransfer.getData(AGENT_MIME)
      if (agentId) onDropAgent?.(agentId, { x: point.x - NODE_WIDTH / 2, y: point.y - NODE_HEIGHT / 2 })
    } else {
      const server = event.dataTransfer.getData(CONNECTOR_MIME)
      if (server) onDropConnector?.(server, nodeAt(graph, point)?.id ?? null)
    }
  }

  /* ---- Drawing --------------------------------------------------------------------------------- */

  const offsetOf = (node: WorkflowNode): Point =>
    drag?.kind === 'move' && drag.moved && editing && drag.ids.includes(node.id) ? { x: drag.dx, y: drag.dy } : { x: 0, y: 0 }
  const placed = new Map(
    graph.nodes.map((node) => {
      const offset = offsetOf(node)
      return [node.id, { ...node, x: node.x + offset.x, y: node.y + offset.y }]
    }),
  )
  const selectedNodes = new Set(selection.nodes)
  const pending = drag?.kind === 'connect' ? placed.get(drag.from) : undefined

  return (
    <div
      ref={boxRef}
      className="wf-canvas"
      data-mode={mode}
      data-gesture={drag?.kind ?? undefined}
      role="group"
      aria-roledescription="canvas"
      aria-label={label}
      aria-describedby={helpId}
      tabIndex={0}
      onPointerDown={onBackgroundPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={(event) => endGesture(event, false)}
      onPointerCancel={(event) => endGesture(event, true)}
      onKeyDown={onKeyDown}
      onDragOver={onDragOver}
      onDragLeave={() => setDropTarget(null)}
      onDrop={onDrop}
    >
      <p id={helpId} className="visually-hidden">
        {editing
          ? 'Use Tab to move between steps. Press Enter to select a step, the arrow keys to move it and Delete to remove it. Connect steps from the step panel. Plus and minus zoom, zero fits the view.'
          : selecting
            ? 'Use Tab to move between steps and press Enter to see a step in the step panel. Plus and minus zoom, zero fits the view.'
            : 'Use Tab to move between steps and press Enter to open the run of a step. Plus and minus zoom, zero fits the view.'}
      </p>
      <div
        className="wf-canvas-layer"
        style={{ transform: `translate(${viewport.x}px, ${viewport.y}px) scale(${viewport.zoom})`, '--wf-zoom': viewport.zoom } as CSSProperties}
      >
        <svg className="wf-edges" width="1" height="1" aria-hidden="true" focusable="false">
          <defs>
            <marker id={markerId} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
              <path d="M 0 0 L 10 5 L 0 10 z" className="wf-arrow" />
            </marker>
          </defs>
          {graph.edges.map((edge) => {
            const from = placed.get(edge.from)
            const to = placed.get(edge.to)
            if (!from || !to) return null
            const d = edgePath(outPort(from), inPort(to))
            const selected = selection.edge === edge.id
            return (
              <g key={edge.id} data-edge-id={edge.id} className="wf-edge-group" data-selected={selected || undefined} data-active={activeEdges?.has(edge.id) || undefined}>
                {editing && (
                  <path
                    className="wf-edge-hit"
                    d={d}
                    onPointerDown={(event) => {
                      event.stopPropagation()
                      onSelect({ nodes: [], edge: edge.id })
                    }}
                  />
                )}
                <path className="wf-edge" d={d} markerEnd={`url(#${markerId})`} />
              </g>
            )
          })}
          {pending && drag?.kind === 'connect' && (
            <path className="wf-edge wf-edge-draft" d={edgePath(outPort(pending), drag.point)} />
          )}
          {!reduceMotion &&
            tokens.map((token, index) => {
              const edge = graph.edges.find((candidate) => candidate.id === token.edgeId)
              const from = edge ? placed.get(edge.from) : undefined
              const to = edge ? placed.get(edge.to) : undefined
              if (!from || !to) return null
              const point = bezierPoint(outPort(from), inPort(to), token.progress)
              return <circle key={`${token.edgeId}-${index}`} className="wf-token" cx={point.x} cy={point.y} r={7} />
            })}
        </svg>
        {graph.nodes.map((node) => {
          const at = placed.get(node.id)!
          const look = face(node)
          const selected = selectedNodes.has(node.id)
          const status = look.status
          const statusText = status ? STEP_STATUS_LABEL[status] : null
          // One sentence per fact, each ending in a single full stop however its own text ends.
          const ariaParts = [
            `Step ${look.number}: ${look.heading}`,
            look.agentName,
            statusText,
            look.connectors.length > 0 ? `Needs ${look.connectors.map((c) => c.name).join(', ')}` : null,
            look.problemText,
          ]
            .filter((part): part is string => Boolean(part))
            .map((part) => `${part.trim().replace(/[.\s]+$/, '')}.`)
          return (
            <div
              key={node.id}
              className="wf-node"
              data-node-id={node.id}
              data-status={status}
              data-selected={selected || undefined}
              data-problem={look.problem ?? undefined}
              data-drop-target={dropTarget === node.id || undefined}
              style={{ left: at.x, top: at.y, width: NODE_WIDTH, height: NODE_HEIGHT }}
              role="button"
              tabIndex={0}
              aria-pressed={selecting ? selected : undefined}
              aria-label={ariaParts.join(' ')}
              onPointerDown={(event) => onNodePointerDown(event, node)}
              onKeyDown={(event) => onNodeKeyDown(event, node)}
            >
              <span className="wf-port wf-port-in" aria-hidden="true" />
              <div className="wf-node-head" aria-hidden="true">
                <AgentAvatar name={look.agentName} category={look.agentCategory} fallback={look.fallback} size="sm" />
                <span className="wf-node-agent">{look.agentName}</span>
                <span className="wf-node-number">{look.number}</span>
              </div>
              <p className="wf-node-title" aria-hidden="true">
                {look.heading}
              </p>
              <div className="wf-node-foot" aria-hidden="true">
                {statusText && (
                  <span className="wf-status" data-status={status}>
                    <span className="wf-status-dot" />
                    {statusText}
                  </span>
                )}
                {!statusText &&
                  look.connectors.slice(0, 3).map((connector) => (
                    <span key={connector.id} className="wf-chip" data-missing={connector.missing || undefined}>
                      {connector.name}
                    </span>
                  ))}
                {!statusText && look.connectors.length > 3 && <span className="wf-chip">+{look.connectors.length - 3}</span>}
                {look.problem && <span className="wf-problem-mark" data-problem={look.problem} title={look.problemText ?? undefined} />}
              </div>
              {editing && (
                <span
                  className="wf-port wf-port-out"
                  aria-hidden="true"
                  title="Drag to the next step"
                  onPointerDown={(event) => onPortPointerDown(event, node)}
                />
              )}
            </div>
          )
        })}
      </div>

      {!hasNodes && emptyHint && <p className="wf-canvas-empty">{emptyHint}</p>}

      {overlay && (
        <div className="wf-canvas-overlay" onPointerDown={(event) => event.stopPropagation()}>
          {overlay}
        </div>
      )}

      <div className="wf-zoom" onPointerDown={(event) => event.stopPropagation()}>
        <button type="button" className="icon-button" aria-label="Zoom out" title="Zoom out" onClick={() => zoomBy(1 / ZOOM_STEP)}>
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M3.5 8h9" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          </svg>
        </button>
        <span className="wf-zoom-level" aria-live="polite">
          {Math.round(viewport.zoom * 100)}%
        </span>
        <button type="button" className="icon-button" aria-label="Zoom in" title="Zoom in" onClick={() => zoomBy(ZOOM_STEP)}>
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M3.5 8h9M8 3.5v9" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          </svg>
        </button>
        <button type="button" className="icon-button" aria-label="Fit the view" title="Fit the view" onClick={fit}>
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path
              d="M2.5 6V2.5H6M10 2.5h3.5V6M13.5 10v3.5H10M6 13.5H2.5V10"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </button>
      </div>
    </div>
  )
}
