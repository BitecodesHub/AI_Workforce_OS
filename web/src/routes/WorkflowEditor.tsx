// @find: workflow editor, canvas editor, build workflow, new workflow, save workflow, run workflow, add step, tidy up, unsaved changes guard, restore draft, version conflict, run history, /workflows/:id, /workflows/new, WorkflowEditor
// @what: The workflow editor: palette, canvas and step panel to build a workflow, Save with a version check, Run with values, a local draft with an unsaved-changes guard, and the run history.
// @flow: Routed from App.tsx at /workflows/new and /workflows/:id; uses lib/workflowQueries, lib/workflowGraph, lib/workflowDraft and components/workflows; a run opens /workflows/run/:goalId
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Button, ConfirmDialog, Notice, PageHeader, Select, StatusTag, Tag, Time } from '../components/ui'
import { BackLink, QueryState } from '../components/ui/QueryState'
import { NOTHING_SELECTED, WorkflowCanvas } from '../components/workflows/WorkflowCanvas'
import type { NodeFace, Selection } from '../components/workflows/WorkflowCanvas'
import { WorkflowPalette } from '../components/workflows/WorkflowPalette'
import { StepInspector } from '../components/workflows/StepInspector'
import { RunWorkflowDialog } from '../components/workflows/RunWorkflowDialog'
import { ApiError, api, describeApiError } from '../lib/api'
import { plural } from '../lib/format'
import { useLeaveGuard, useRouter, useDocumentTitle } from '../lib/router'
import type { LeaveGuard } from '../lib/router'
import { can, profile } from '../lib/session'
import { useToast } from '../lib/toast'
import {
  CONNECT_REFUSAL,
  MAX_STEPS,
  addConnector,
  addNode,
  canonical,
  connect,
  emptyGraph,
  lacksConnector,
  nextPosition,
  normaliseGraph,
  placeholders,
  removeConnector,
  removeEdges,
  removeNodes,
  sameGraph,
  stepNumbers,
  tidyGraph,
  updateNode,
  validateGraph,
} from '../lib/workflowGraph'
import type { PaletteAgentInfo, Point, Problem, Viewport, WorkflowGraph } from '../lib/workflowGraph'
import { clearDraft, draftKey, readDraft, writeDraft } from '../lib/workflowDraft'
import type { WorkflowDraft } from '../lib/workflowDraft'
import {
  canEditWorkflows,
  useCreateWorkflow,
  useRunWorkflow,
  useUpdateWorkflow,
  useWorkflow,
  useWorkflowPalette,
  useWorkflowRuns,
} from '../lib/workflowQueries'
import type { Workflow, WorkflowPalette as Palette } from '../lib/workflowQueries'

/*
 * The editor keeps its own copy of the workflow while a person works, compares it with the last
 * saved copy to know whether anything is unsaved, and writes it to this browser a moment after each
 * change (and at once when the page goes away), so a reload never loses work. Save sends the
 * version the edits started from - for restored edits, the version they were kept at: if someone
 * else saved in between, the server refuses with 409 and the person chooses whose version to keep.
 *
 * Run saves first when there is something unsaved, refuses on the spot when a step could not run
 * (the same rules the server applies), asks for a value for each {{placeholder}}, and then opens
 * the run's live view. It names the version on screen, so a workflow someone else saved since is
 * never run unseen. Leaving with unsaved changes - a link, Back, signing out - asks first.
 *
 * Only the workflow's maker, or someone who can cancel work, may change it (the server says which
 * in canChange); anyone who can start work may run it.
 */

/** The saved copy the editor compares its own against. */
type Base = { id: string; name: string; description: string; graph: WorkflowGraph; version: number; updatedAt: string }

const DRAFT_DELAY_MS = 600

/** An attribute selector for a step on the canvas. */
function stepSelector(nodeId: string): string {
  return `.wf-canvas [data-node-id="${nodeId.replace(/["\\]/g, '\\$&')}"]`
}

/** The saved copy, from what the server returned. */
function baseOf(workflow: Workflow): Base {
  return {
    id: workflow.id,
    name: workflow.name,
    description: workflow.description,
    graph: normaliseGraph(workflow.graph),
    version: workflow.version,
    updatedAt: workflow.updatedAt,
  }
}

// @find: workflow editor page component
/**
 * The /workflows/:id and /workflows/new screen. Loads the workflow (an existing one) and the
 * palette, then hands both to the editor itself, keyed so "Load the latest version" starts it
 * afresh from the server's copy.
 */
export function WorkflowEditor({ id }: { id: string | null }) {
  const query = useWorkflow(id)
  const palette = useWorkflowPalette()
  const [reload, setReload] = useState(0)

  // keepData on both: a background refetch that fails (a focus after the palette went stale, a
  // service restart) shows a notice over the editor instead of unmounting it and losing the edits.
  if (!id) {
    return (
      <QueryState query={palette} permission="task:read" what="the agents and connectors" keepData>
        {(data) => <Editor key="new" initial={null} palette={data} onReloadLatest={() => {}} />}
      </QueryState>
    )
  }
  return (
    <QueryState
      query={query}
      permission="task:read"
      what="this workflow"
      keepData
      notFound={<BackLink href="/workflows" label="Back to Workflows" />}
    >
      {(workflow) => (
        <QueryState query={palette} permission="task:read" what="the agents and connectors" keepData>
          {(data) => (
            <Editor
              key={`${workflow.id}:${reload}`}
              initial={workflow}
              palette={data}
              onReloadLatest={async () => {
                await query.refetch()
                setReload((value) => value + 1)
              }}
            />
          )}
        </QueryState>
      )}
    </QueryState>
  )
}

/** The editor proper, for one loaded workflow (or a new one when `initial` is null). */
function Editor({
  initial,
  palette,
  onReloadLatest,
}: {
  initial: Workflow | null
  palette: Palette
  onReloadLatest: () => void | Promise<void>
}) {
  /** May start work (task:create): run, and build a new workflow. */
  const canRun = canEditWorkflows(can)
  /** May change this workflow: a new one, or one the server says this person may change. */
  const canEdit = canRun && (initial ? initial.canChange : true)
  const toast = useToast()
  const { navigate, search, path } = useRouter()
  const who = profile()
  const key = draftKey(initial?.id ?? null, who?.userId ?? null, who?.workspaceId ?? null)

  const [base, setBase] = useState<Base | null>(() => (initial ? baseOf(initial) : null))
  /**
   * The server version the edits on screen started from, which a save names. It is the loaded
   * version, unless restored edits were kept at an older one: then a save is refused with 409 and
   * the person chooses, rather than silently overwriting what someone else saved since.
   */
  const [editVersion, setEditVersion] = useState<number | null>(() => initial?.version ?? null)
  const [name, setName] = useState(() => initial?.name ?? '')
  const [description, setDescription] = useState(() => initial?.description ?? '')
  const [graph, setGraph] = useState<WorkflowGraph>(() => (initial ? normaliseGraph(initial.graph) : emptyGraph()))
  const [selection, setSelection] = useState<Selection>(NOTHING_SELECTED)
  const [viewport, setViewport] = useState<Viewport>({ x: 32, y: 32, zoom: 1 })
  const [fitKey, setFitKey] = useState(0)
  const [nameError, setNameError] = useState<string | null>(null)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [conflict, setConflict] = useState(false)
  const [runBlocked, setRunBlocked] = useState<string | null>(null)
  const [runOpen, setRunOpen] = useState(() => search.get('run') === '1')
  const [runError, setRunError] = useState<string | null>(null)
  const [leaveOpen, setLeaveOpen] = useState(false)
  /** What leaving does once the person confirms it, as the router's leave guard handed it over. */
  const leaveProceed = useRef<(() => void) | null>(null)
  const [announcement, setAnnouncement] = useState('')
  /** A step just added, whose instruction takes focus once it is drawn. */
  const focusStep = useRef<string | null>(null)
  /** Where focus goes after the next render, first match wins: steps deleted from the panel leave focus on the canvas. */
  const focusAfter = useRef<string[] | null>(null)
  /** Set while a save or a run is starting, so a second click or key press cannot start another. */
  const saving = useRef(false)
  const starting = useRef(false)
  const runStarting = useRef(false)
  /** The draft waiting for its moment to be written, so leaving the page can write it at once. */
  const pendingDraft = useRef<{ key: string; draft: WorkflowDraft } | null>(null)
  /** Set while "Save and run" saves a new workflow, so the saved one opens with the run dialog. */
  const runOpenAfterCreate = useRef(false)
  const [draftOffer, setDraftOffer] = useState<WorkflowDraft | null>(() => {
    const draft = readDraft(key)
    if (!draft) return null
    const savedGraph = initial ? normaliseGraph(initial.graph) : emptyGraph()
    const differs =
      !sameGraph(draft.graph, savedGraph) ||
      draft.name.trim() !== (initial?.name ?? '').trim() ||
      draft.description.trim() !== (initial?.description ?? '').trim()
    return differs ? draft : null
  })

  const create = useCreateWorkflow()
  const update = useUpdateWorkflow(base?.id ?? '')
  const run = useRunWorkflow(base?.id ?? '')
  const runs = useWorkflowRuns(base?.id ?? null)

  useDocumentTitle(name.trim() || (base ? null : 'New workflow'))

  /* ---- What the canvas knows ------------------------------------------------------------------ */

  const agents = useMemo(
    () => new Map<string, PaletteAgentInfo>(palette.agents.map((agent) => [agent.id, agent])),
    [palette.agents],
  )
  const connectorNames = useMemo(() => new Map(palette.connectors.map((c) => [c.id, c.name])), [palette.connectors])
  const connectorName = (server: string) => connectorNames.get(server) ?? null
  const problems = useMemo(() => validateGraph(graph, agents, (server) => connectorNames.get(server) ?? null), [graph, agents, connectorNames])
  const numbers = useMemo(() => stepNumbers(graph), [graph])
  const needs = useMemo(() => placeholders(graph), [graph])

  const dirty = base
    ? name.trim() !== base.name || description.trim() !== base.description || !sameGraph(graph, base.graph)
    : graph.nodes.length > 0 || name.trim() !== '' || description.trim() !== ''

  /* ---- Draft and leaving ---------------------------------------------------------------------- */

  // Writes the waiting draft now: when the page goes away, the editor closes, or the person leaves.
  // Not once the person has signed out or another person signed in, since drafts leave with them.
  const flushDraft = useCallback(() => {
    const pending = pendingDraft.current
    pendingDraft.current = null
    const now = profile()
    if (!pending || !now) return
    if (pending.key !== draftKey(initial?.id ?? null, now.userId, now.workspaceId ?? null)) return
    writeDraft(pending.key, pending.draft)
  }, [initial?.id])

  // Kept on this device a moment after each change while there is something unsaved. While a
  // restore is on offer, a new edit is kept over it (the offer stays until the page is left), and
  // having nothing unsaved does not throw the offered draft away.
  useEffect(() => {
    if (!canEdit) return
    if (!dirty) {
      pendingDraft.current = null
      if (!draftOffer) clearDraft(key)
      return
    }
    const draft: WorkflowDraft = {
      name,
      description,
      graph: canonical(graph),
      baseVersion: base ? editVersion : null,
      savedAt: new Date().toISOString(),
    }
    pendingDraft.current = { key, draft }
    const timer = window.setTimeout(() => {
      pendingDraft.current = null
      writeDraft(key, draft)
    }, DRAFT_DELAY_MS)
    return () => window.clearTimeout(timer)
  }, [canEdit, dirty, draftOffer, key, name, description, graph, base, editVersion])

  // Leaving the page, or this screen, writes the draft that was still waiting for its moment.
  useEffect(() => {
    window.addEventListener('pagehide', flushDraft)
    return () => {
      window.removeEventListener('pagehide', flushDraft)
      flushDraft()
    }
  }, [flushDraft])

  // Closing or reloading the tab with something unsaved asks the browser's own question.
  useEffect(() => {
    if (!dirty || !canEdit) return
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      flushDraft()
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => window.removeEventListener('beforeunload', onBeforeUnload)
  }, [dirty, canEdit, flushDraft])

  // Any other way of leaving with something unsaved - a link, Back or Forward, signing out - asks
  // first, through the router's leave guard.
  const askToLeave = useCallback<LeaveGuard>((proceed) => {
    leaveProceed.current = proceed
    setLeaveOpen(true)
  }, [])
  useLeaveGuard(dirty && canEdit ? askToLeave : null)

  // Focus the instruction of a step just added, so a keyboard user can type straight away; or
  // whatever a deletion asked for, so focus never falls to the page.
  useEffect(() => {
    const id = focusStep.current
    if (id) {
      const field = document.querySelector<HTMLTextAreaElement>(`[data-step-instruction="${id}"]`)
      if (field) {
        field.focus()
        focusStep.current = null
      }
    }
    const targets = focusAfter.current
    if (targets) {
      focusAfter.current = null
      for (const selector of targets) {
        const element = document.querySelector<HTMLElement>(selector)
        if (element) {
          element.focus()
          break
        }
      }
    }
  })

  /* ---- Edits ---------------------------------------------------------------------------------- */

  const announce = (text: string) => setAnnouncement(text)

  const addStep = (agentId: string, at?: Point) => {
    if (!canEdit) return
    if (graph.nodes.length >= MAX_STEPS) {
      toast.error(`A workflow can hold at most ${MAX_STEPS} steps.`)
      return
    }
    const added = addNode(graph, { agentId, ...(at ?? nextPosition(graph)) })
    setGraph(added.graph)
    setSelection({ nodes: [added.id], edge: null })
    focusStep.current = added.id
    announce(`Added a step for ${agents.get(agentId)?.name ?? 'the agent'}.`)
  }

  const addConnectorTo = (nodeId: string | null, server: string) => {
    if (!canEdit) return
    if (!nodeId) {
      toast.info('Drop a connector onto a step, or select a step first.')
      return
    }
    setGraph((current) => addConnector(current, nodeId, server))
    setSelection({ nodes: [nodeId], edge: null })
    announce(`${connectorName(server) ?? server} is now required by step ${numbers.get(nodeId) ?? ''}.`)
  }

  const connectSteps = (from: string, to: string) => {
    const result = connect(graph, from, to)
    if (result.refusal) {
      toast.error(CONNECT_REFUSAL[result.refusal])
      return
    }
    setGraph(result.graph)
    announce(`Step ${numbers.get(from) ?? ''} now hands over to step ${numbers.get(to) ?? ''}.`)
  }

  const deleteNodes = (ids: string[]) => {
    // Focus moves to the step after the deleted ones in running order (else the one before), or
    // to the canvas itself when none is left, wherever the delete came from.
    const gone = new Set(ids)
    const order = [...graph.nodes].sort((a, b) => (numbers.get(a.id) ?? 0) - (numbers.get(b.id) ?? 0))
    const last = Math.max(...order.map((node, index) => (gone.has(node.id) ? index : -1)))
    const after = order.slice(last + 1).find((node) => !gone.has(node.id))
    const before = [...order.slice(0, last)].reverse().find((node) => !gone.has(node.id))
    focusAfter.current = [after, before].filter((node): node is WorkflowGraph['nodes'][number] => Boolean(node)).map((node) => stepSelector(node.id)).concat('.wf-canvas')
    setGraph((current) => removeNodes(current, ids))
    setSelection(NOTHING_SELECTED)
    announce(ids.length === 1 ? 'Step deleted.' : `${ids.length} steps deleted.`)
  }

  const deleteSelection = () => {
    if (selection.nodes.length > 0) deleteNodes(selection.nodes)
    else if (selection.edge) {
      const edgeId = selection.edge
      setGraph((current) => removeEdges(current, [edgeId]))
      setSelection(NOTHING_SELECTED)
      announce('Connection removed.')
    }
  }

  /* ---- Save and run --------------------------------------------------------------------------- */

  const save = async (options: { overwrite?: boolean } = {}): Promise<Base | null> => {
    if (saving.current) return null
    setSaveError(null)
    setRunBlocked(null)
    const trimmed = name.trim()
    if (!trimmed) {
      setNameError('Give the workflow a name.')
      setSelection(NOTHING_SELECTED)
      setSaveError('Give the workflow a name before saving it.')
      return null
    }
    setNameError(null)
    const errors = problems.filter((problem) => problem.severity === 'error')
    if (errors.length > 0) {
      setSaveError(`The workflow was not saved. ${errors.map((problem) => problem.message).join(' ')}`)
      return null
    }
    saving.current = true
    try {
      let version = editVersion ?? base?.version
      if (options.overwrite && base) {
        // The person chose to keep their version: save it over the newest one on the server.
        version = (await latestVersion(base.id)) ?? version
      }
      const body = { name: trimmed, description: description.trim(), graph: canonical(graph) }
      const saved = base ? await update.mutateAsync({ ...body, version: version ?? 0 }) : await create.mutateAsync(body)
      const next = baseOf(saved)
      setConflict(false)
      pendingDraft.current = null
      clearDraft(key)
      if (!base) {
        toast.success(`${saved.name} was saved`)
        navigate(`/workflows/${saved.id}${runOpenAfterCreate.current ? '?run=1' : ''}`, { replace: true, force: true })
        return next
      }
      // Only the saved copy moves on: the canvas keeps whatever the person typed while it saved.
      setBase(next)
      setEditVersion(next.version)
      toast.success('Saved')
      return next
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) setConflict(true)
      else setSaveError(describeApiError(error))
      return null
    } finally {
      saving.current = false
    }
  }

  const startRun = async () => {
    if (starting.current || saving.current) return
    setRunBlocked(null)
    setRunError(null)
    const blocking = problems
    if (blocking.length > 0) {
      setRunBlocked(`This workflow cannot run yet. ${blocking.map((problem) => problem.message).join(' ')}`)
      return
    }
    starting.current = true
    try {
      if (!base) {
        runOpenAfterCreate.current = true
        await save()
        runOpenAfterCreate.current = false
        return
      }
      if (dirty && canEdit) {
        const saved = await save()
        if (!saved) return
      }
      setRunOpen(true)
    } finally {
      starting.current = false
    }
  }

  const submitRun = async (inputs: Record<string, string>) => {
    if (!base || runStarting.current) return
    runStarting.current = true
    setRunError(null)
    try {
      // The version on screen: a workflow someone else saved since is refused, not run unseen.
      const started = await run.mutateAsync({ inputs, version: base.version })
      setRunOpen(false)
      navigate(`/workflows/run/${started.goalId}`, { force: true })
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setRunOpen(false)
        setConflict(true)
      } else {
        setRunError(describeApiError(error))
      }
    } finally {
      runStarting.current = false
    }
  }

  // Opened with ?run=1 (a new workflow saved by Save and run): drop the flag from the address.
  useEffect(() => {
    if (search.get('run') === '1') navigate(path, { replace: true })
  }, [search, path, navigate])

  /* ---- Drawing -------------------------------------------------------------------------------- */

  const problemOf = (nodeId: string): { level: 'error' | 'warning' | null; text: string | null } => {
    const own = problems.filter((problem) => problem.nodeId === nodeId)
    if (own.length === 0) return { level: null, text: null }
    const level = own.some((problem) => problem.severity === 'error') ? 'error' : 'warning'
    return { level, text: own.map((problem) => problem.message).join(' ') }
  }

  const face = (node: WorkflowGraph['nodes'][number]): NodeFace => {
    const agent = node.agentId ? palette.agents.find((candidate) => candidate.id === node.agentId) : undefined
    const firstLine = node.instruction.trim().split('\n')[0]?.trim() ?? ''
    const problem = problemOf(node.id)
    return {
      agentName: agent?.name ?? (node.agentId ? 'Agent not available' : 'Choose an agent'),
      agentCategory: agent?.category ?? null,
      fallback: agent?.fallback ?? false,
      heading: node.title?.trim() || firstLine || 'No instruction yet',
      number: numbers.get(node.id) ?? 0,
      connectors: node.connectors.map((server) => ({
        id: server,
        name: connectorName(server) ?? server,
        missing: lacksConnector(agent, server),
      })),
      problem: problem.level,
      problemText: problem.text,
    }
  }

  const selectedNodeId = selection.nodes.length === 1 ? selection.nodes[0]! : null
  const errorsCount = problems.filter((problem: Problem) => problem.severity === 'error').length
  const savePending = create.isPending || update.isPending
  /** A save or a run is on its way: both buttons wait for it. */
  const busy = savePending || run.isPending
  /** The draft on offer was kept at an older version than the one now saved. */
  const staleDraft = Boolean(draftOffer && base && draftOffer.baseVersion !== null && draftOffer.baseVersion !== base.version)

  // On a narrow screen the step panel sits under the canvas: this takes the person to it.
  const showStepPanel = () => {
    const heading = document.getElementById('wf-inspector-heading')
    if (!heading) return
    const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
    heading.scrollIntoView?.({ block: 'start', behavior: reduce ? 'auto' : 'smooth' })
    heading.focus({ preventScroll: true })
  }

  return (
    <div className="page wf-editor">
      <BackLink href="/workflows" label="Workflows" />
      <PageHeader
        eyebrow={base ? 'Workflow' : 'New workflow'}
        title={name.trim() || (base ? base.name : 'Untitled workflow')}
        description={description.trim() || undefined}
        meta={
          <>
            {dirty && canEdit ? (
              <Tag tone="warning">Unsaved changes</Tag>
            ) : base ? (
              <span className="caption muted">
                Version {base.version + 1}, saved <Time iso={base.updatedAt} />
              </span>
            ) : null}
            <span className="caption muted">
              {plural(graph.nodes.length, 'step', 'steps')}, {plural(graph.edges.length, 'connection', 'connections')}
            </span>
          </>
        }
        action={
          canEdit ? (
            <>
              <Button
                variant={dirty ? 'primary' : 'outline'}
                onClick={() => void save()}
                loading={savePending && !run.isPending}
                disabled={(!dirty && Boolean(base)) || busy}
              >
                Save
              </Button>
              <Button variant={dirty ? 'outline' : 'primary'} onClick={() => void startRun()} disabled={graph.nodes.length === 0 || busy}>
                {dirty || !base ? 'Save and run' : 'Run'}
              </Button>
            </>
          ) : canRun && base ? (
            <Button onClick={() => void startRun()} disabled={graph.nodes.length === 0 || busy}>
              Run
            </Button>
          ) : undefined
        }
      />

      {!canRun && (
        <Notice tone="info">Your role can see this workflow and watch its runs, but not change or run it.</Notice>
      )}
      {canRun && !canEdit && (
        <Notice tone="info">
          Only the person who made this workflow, or someone who can cancel work, can change it. You can still run it.
        </Notice>
      )}

      {draftOffer && canEdit && (
        <Notice tone={staleDraft ? 'warning' : 'info'}>
          You have changes from <Time iso={draftOffer.savedAt} /> that were not saved, kept on this device.
          {staleDraft
            ? ' Someone has saved this workflow since then, so if you restore them, saving asks whose version to keep.'
            : ''}{' '}
          <Button
            variant="quiet"
            onClick={() => {
              setName(draftOffer.name)
              setDescription(draftOffer.description)
              setGraph(draftOffer.graph)
              // A save names the version the restored edits were kept at, so a newer one is never overwritten unseen.
              if (base) setEditVersion(draftOffer.baseVersion ?? base.version)
              setFitKey((value) => value + 1)
              setDraftOffer(null)
            }}
          >
            Restore them
          </Button>{' '}
          <Button
            variant="quiet"
            onClick={() => {
              clearDraft(key)
              setDraftOffer(null)
            }}
          >
            Discard them
          </Button>
        </Notice>
      )}

      {conflict && (
        <Notice tone="warning" live>
          Someone else saved this workflow while you were editing it. Load their version to see it (your changes here are
          lost), or save yours over it.{' '}
          <Button
            variant="quiet"
            onClick={() => {
              pendingDraft.current = null
              clearDraft(key)
              void onReloadLatest()
            }}
          >
            Load the latest version
          </Button>{' '}
          {canEdit && (
            <Button variant="quiet" onClick={() => void save({ overwrite: true })} disabled={busy}>
              Save mine over it
            </Button>
          )}
        </Notice>
      )}
      {saveError && (
        <Notice tone="warning" live>
          {saveError}
        </Notice>
      )}
      {runBlocked && (
        <Notice tone="warning" live>
          {runBlocked}
        </Notice>
      )}

      {canEdit && (
        <div className="wf-toolbar">
          <div className="wf-toolbar-add">
            <Select
              label="Add a step"
              value=""
              onChange={(event) => {
                if (event.target.value) addStep(event.target.value)
              }}
            >
              <option value="">Choose an agent</option>
              {palette.agents.map((agent) => (
                <option key={agent.id} value={agent.id}>
                  {agent.status === 'active' ? agent.name : `${agent.name} (paused)`}
                </option>
              ))}
            </Select>
          </div>
          <Button
            variant="outline"
            disabled={graph.nodes.length < 2}
            onClick={() => {
              setGraph(tidyGraph(graph))
              setFitKey((value) => value + 1)
              announce('Steps arranged in columns, in the order they run.')
            }}
          >
            Tidy up
          </Button>
          <span className="caption muted wf-toolbar-note">
            {errorsCount > 0 ? plural(errorsCount, 'thing to fix before saving', 'things to fix before saving') : 'Ready to save'}
          </span>
        </div>
      )}

      <div className="wf-workspace" data-editable={canEdit || undefined}>
        {canEdit && (
          <WorkflowPalette
            agents={palette.agents}
            connectors={palette.connectors}
            stepSelected={selectedNodeId !== null}
            onAddAgent={(agentId) => addStep(agentId)}
            onAddConnector={(server) => addConnectorTo(selectedNodeId, server)}
          />
        )}
        <WorkflowCanvas
          graph={graph}
          label={`Canvas for ${name.trim() || 'this workflow'}`}
          mode={canEdit ? 'edit' : 'inspect'}
          viewport={viewport}
          onViewport={setViewport}
          selection={selection}
          onSelect={setSelection}
          face={face}
          fitKey={fitKey}
          onChange={setGraph}
          onConnect={connectSteps}
          onDropAgent={(agentId, at) => addStep(agentId, at)}
          onDropConnector={(server, nodeId) => addConnectorTo(nodeId, server)}
          onDelete={deleteSelection}
          emptyHint={canEdit ? 'Drag an agent here from the list, or choose one under Add a step.' : 'This workflow has no steps.'}
          overlay={
            selectedNodeId ? (
              <Button variant="outline" className="wf-step-jump" onClick={showStepPanel}>
                {canEdit ? `Edit step ${numbers.get(selectedNodeId) ?? ''}` : `See step ${numbers.get(selectedNodeId) ?? ''}`}
              </Button>
            ) : undefined
          }
        />
        <StepInspector
          graph={graph}
          selection={selection}
          agents={palette.agents}
          connectors={palette.connectors}
          numbers={numbers}
          problems={problems}
          canEdit={canEdit}
          name={name}
          description={description}
          nameError={nameError}
          onName={(value) => {
            setName(value)
            if (value.trim()) setNameError(null)
          }}
          onDescription={setDescription}
          onUpdateNode={(nodeId, patch) => setGraph((current) => updateNode(current, nodeId, patch))}
          onAddConnector={(nodeId, server) => addConnectorTo(nodeId, server)}
          onRemoveConnector={(nodeId, server) => setGraph((current) => removeConnector(current, nodeId, server))}
          onConnect={connectSteps}
          onRemoveEdge={(edgeId) => {
            setGraph((current) => removeEdges(current, [edgeId]))
            if (selection.edge === edgeId) setSelection(NOTHING_SELECTED)
            announce('Connection removed.')
          }}
          onRemoveNodes={deleteNodes}
          onSelect={setSelection}
        />
      </div>

      {base && (
        <section className="wf-section" aria-labelledby="wf-runs-heading">
          <h2 id="wf-runs-heading" className="section-heading">
            Runs
          </h2>
          {runs.isLoading ? (
            <p className="caption muted">Loading the runs</p>
          ) : runs.error ? (
            <p className="caption muted">{describeApiError(runs.error)}</p>
          ) : (runs.data ?? []).length === 0 ? (
            <p className="caption muted">Not run yet.</p>
          ) : (
            <ul className="wf-replay-list">
              {(runs.data ?? []).map((item) => (
                <li key={item.id}>
                  <StatusTag kind="goal" status={item.status} withDot />
                  <span className="caption muted">
                    Version {item.workflowVersion + 1}, started <Time iso={item.startedAt} />
                  </span>
                  <a className="link" href={`/workflows/run/${item.goalId}`}>
                    {item.status === 'completed' || item.status === 'failed' || item.status === 'cancelled' ? 'Replay' : 'Watch'}
                  </a>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      {base && (
        <RunWorkflowDialog
          open={runOpen}
          name={base.name}
          placeholders={needs}
          steps={graph.nodes.length}
          running={run.isPending}
          error={runError}
          onRun={(inputs) => void submitRun(inputs)}
          onClose={() => setRunOpen(false)}
        />
      )}

      <ConfirmDialog
        open={leaveOpen}
        onClose={() => {
          leaveProceed.current = null
          setLeaveOpen(false)
        }}
        onConfirm={() => {
          const proceed = leaveProceed.current
          leaveProceed.current = null
          setLeaveOpen(false)
          // The changes stay on this device, as the question promises.
          flushDraft()
          proceed?.()
        }}
        eyebrow="Before you go"
        title="Leave without saving?"
        description="Your changes stay on this device as a draft, so you can restore them when you come back, but nobody else sees them until you save."
        confirmLabel="Leave"
        cancelLabel="Stay"
        tone="primary"
        startOnCancel
      />

      <p className="visually-hidden" aria-live="polite">
        {announcement}
      </p>
    </div>
  )
}

/** The newest version number of a workflow, read straight from the server; null when it cannot be read. */
async function latestVersion(workflowId: string): Promise<number | null> {
  try {
    return (await api<Workflow>(`/api/workflows/${workflowId}`)).version
  } catch {
    return null
  }
}
