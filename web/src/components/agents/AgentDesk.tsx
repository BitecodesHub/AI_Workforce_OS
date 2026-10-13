// @find: agent desk, watch agent live, live view of agent work, take over browser, stop run, open full trace, who has control, Priya has control, Watch live, return control, waiting for the agent to finish its step
// @what: The Agent desk: a large live view of one run (the web browser frame, or its steps as they happen), with Stop, Take over and Open full trace, and what happens to control while it is open.
// @flow: Opened from WorkingNow tiles on the Agents page and from Watch live on the agent page; uses useRun, useRunSteps, useBrowserLive, BrowserPreview, RunTraceCompact, TakeoverControls and agentDeskQueries
import { useEffect, useRef, useState } from 'react'
import type { MouseEvent } from 'react'
import { Button, ConfirmDialog, Notice, StatusTag } from '../ui'
import { Sheet } from '../ui/Sheet'
import { BrowserPreview } from '../run/BrowserShot'
import { RunTraceCompact } from '../run/RunTraceCompact'
import { isInstruction, usesBrowser } from '../run/traceModel'
import { TakeoverControls } from './TakeoverControls'
import { describeApiError } from '../../lib/api'
import { controlEndedMessage, finishedNotice, holderName } from '../../lib/agentDesk'
import { useReleaseTakeover, useStartTakeover, useTakeover } from '../../lib/agentDeskQueries'
import { formatRunElapsed } from '../../lib/format'
import { isRunActive, useBrowserLive, useCancelRun, useMemberNames, useRun, useRunSteps } from '../../lib/queries'
import { useRouter } from '../../lib/router'
import { can, profile } from '../../lib/session'
import { useToast } from '../../lib/toast'
import { useNow } from '../../lib/useNow'

// @find: AgentDesk, agent desk component, watch live, take over
/**
 * Full-screen sheet for watching one run and stepping in.
 * Data: the run (useRun) and its steps (useRunSteps) refresh while it works; the browser's live view
 * (useBrowserLive, about once a second) is read while the run works and uses the browser, or while
 * you have or are getting control; the takeover state (useTakeover) every two seconds while it works.
 * Layout: when the run uses the browser, the large live frame (BrowserPreview, or TakeoverControls
 * when you have control) beside the steps as they happen (RunTraceCompact); otherwise the steps fill
 * the desk. States: loading, a run that could not be read (with the reason), a run whose agent has
 * not taken its first step yet (said, rather than an empty desk while the model thinks), finished, someone else
 * holding or getting control ("Priya has control", with Return control for people who can stop
 * runs), your request waiting for the agent to finish its step (with Cancel), and control that left
 * you without your hand back (said in a live notice). Footer: Stop (run:cancel, confirmed), Take
 * over (when the server says you may, the run is working and a page is open), and Open full trace.
 * Closing the desk, or opening the full trace, while you hold or are getting control first asks
 * whether to hand it back, so the agent is never left waiting on a closed desk.
 * Props: open, onClose, runId, agentName.
 */
export function AgentDesk({
  open,
  onClose,
  runId,
  agentName,
}: {
  open: boolean
  onClose: () => void
  runId: string
  agentName: string
}) {
  // Nothing is read while the desk is closed.
  if (!open) return null
  return <OpenDesk onClose={onClose} runId={runId} agentName={agentName} />
}

/**
 * The open desk: reads the run once and hands what it knows to the body and the footer. While you
 * hold or are getting control, closing it (Escape, Close, the backdrop) or opening the full trace
 * asks first: hand control back and go, or keep control and stay.
 */
function OpenDesk({ onClose, runId, agentName }: { onClose: () => void; runId: string; agentName: string }) {
  const desk = useDesk(runId)
  const release = useReleaseTakeover(runId)
  const toast = useToast()
  const { navigate } = useRouter()
  // Where the person was going when the desk asked them to hand control back first.
  const [leaving, setLeaving] = useState<'close' | 'trace' | null>(null)
  const [leaveError, setLeaveError] = useState<string | null>(null)
  const holding = desk.controlling || desk.waitingForControl

  const beforeClose = () => {
    if (!holding) return true
    setLeaveError(null)
    setLeaving('close')
    return false
  }

  const onOpenTrace = (event: MouseEvent<HTMLAnchorElement>) => {
    if (!holding) return
    event.preventDefault()
    setLeaveError(null)
    setLeaving('trace')
  }

  const handBackAndLeave = async () => {
    setLeaveError(null)
    try {
      await release.mutateAsync({})
      toast.success(`Control handed back to ${agentName}.`)
      const where = leaving
      setLeaving(null)
      if (where === 'trace') navigate(`/runs/${runId}`)
      else onClose()
    } catch (thrown) {
      setLeaveError(describeApiError(thrown))
    }
  }

  return (
    <Sheet
      open
      onClose={onClose}
      beforeClose={beforeClose}
      width="full"
      eyebrow="Agent desk"
      title={`${agentName} at work`}
      footer={<DeskFooter runId={runId} desk={desk} onOpenTrace={onOpenTrace} />}
    >
      <DeskBody runId={runId} agentName={agentName} desk={desk} />
      <ConfirmDialog
        open={leaving !== null}
        onClose={() => setLeaving(null)}
        onConfirm={handBackAndLeave}
        eyebrow="Agent desk"
        title={`Hand control back to ${agentName}?`}
        description={
          desk.controlling
            ? `You have control of the web browser, and ${agentName} is waiting for you. It carries on once you hand control back.`
            : `You asked for control of the web browser. ${agentName} stops for you after the step it is on unless you hand control back.`
        }
        confirmLabel={leaving === 'trace' ? 'Hand back and open the trace' : 'Hand back and close'}
        cancelLabel="Keep control"
        tone="primary"
        startOnCancel
        loading={release.isPending}
        error={leaveError}
      />
    </Sheet>
  )
}

/** What the desk knows about a run, read once for the body and the footer. */
type Desk = ReturnType<typeof useDesk>

/** Reads the run, its steps, its takeover state and (while it browses) its live view. */
function useDesk(runId: string) {
  const runQuery = useRun(runId)
  const run = runQuery.data
  const active = isRunActive(run)
  const steps = useRunSteps(runId, { active }).data
  const takeover = useTakeover(runId, active).data ?? null
  const controlling = Boolean(takeover?.active && takeover.mine)
  const waitingForControl = Boolean(takeover?.starting && takeover.mine)
  const browsing = usesBrowser(steps) || controlling || waitingForControl
  const live = useBrowserLive(runId, active && browsing).data ?? null
  return { run, runError: runQuery.error, active, steps, takeover, controlling, waitingForControl, browsing, live }
}

/** The desk's content: status line, notices, the live view and the steps. */
function DeskBody({ runId, agentName, desk }: { runId: string; agentName: string; desk: Desk }) {
  const { run, runError, active, steps, takeover, controlling, waitingForControl, browsing } = desk
  const toast = useToast()
  const release = useReleaseTakeover(runId)
  const othersHold = Boolean(takeover && (takeover.active || takeover.starting) && !takeover.mine)
  const mineNow = controlling || waitingForControl
  const me = profile()?.userId ?? null
  const names = useMemberNames({ enabled: othersHold || Boolean(takeover?.releasedBy) })
  const now = useNow(1000)
  const statusRef = useRef<HTMLDivElement>(null)
  const endedRef = useRef<HTMLDivElement>(null)

  // Control that left this person without their own hand back is said once, in a live notice. The
  // "previous value" pattern: compared during render rather than mirrored by an effect.
  const [heldBefore, setHeldBefore] = useState(false)
  const [ended, setEnded] = useState<string | null>(null)
  if (takeover && mineNow !== heldBefore) {
    setHeldBefore(mineNow)
    setEnded(mineNow ? null : controlEndedMessage(takeover, me, agentName, names))
  }
  // Whether someone else held control when the desk first read it: a holder who appears later is announced.
  const [othersAtFirst, setOthersAtFirst] = useState<boolean | null>(null)
  if (takeover && othersAtFirst === null) setOthersAtFirst(othersHold)

  const [confirmReturn, setConfirmReturn] = useState(false)
  const [releaseError, setReleaseError] = useState<string | null>(null)

  // The notice takes focus, so a keyboard or screen reader user is not left on controls that vanished.
  useEffect(() => {
    if (ended) endedRef.current?.focus()
  }, [ended])

  if (!run) {
    if (runError) {
      return (
        <Notice tone="warning">{`This run could not be loaded. ${describeApiError(runError)}`}</Notice>
      )
    }
    return <p className="caption muted">Loading the run…</p>
  }

  const returnControl = async () => {
    setReleaseError(null)
    try {
      await release.mutateAsync({})
      toast.success(`Control returned to ${agentName}.`)
      setConfirmReturn(false)
    } catch (thrown) {
      setReleaseError(describeApiError(thrown))
    }
  }

  const cancelRequest = async () => {
    setReleaseError(null)
    try {
      await release.mutateAsync({})
      toast.success(`${agentName} carries on without stopping for you.`)
      statusRef.current?.focus()
    } catch (thrown) {
      setReleaseError(describeApiError(thrown))
    }
  }

  const holder = holderName(takeover?.takenBy, names)
  // The first model call can take a minute when providers are slow; until a step lands, the desk
  // says the agent is starting rather than showing nothing (seen 13 Oct 2026).
  const notStarted = active && Boolean(steps) && !(steps ?? []).some((step) => !isInstruction(step))

  return (
    <div className="agent-desk">
      <div className="agent-desk-status" ref={statusRef} tabIndex={-1}>
        <StatusTag kind="run" status={run.status} withDot />
        <span className="caption muted tabular">{formatRunElapsed(run, now)}</span>
      </div>

      {ended && (
        <div ref={endedRef} tabIndex={-1} className="agent-desk-notice">
          <Notice tone="warning" live>
            {ended}
          </Notice>
        </div>
      )}
      {othersHold && (
        <div className="agent-desk-holder">
          <Notice tone="warning" live={othersAtFirst === false}>
            {takeover?.active
              ? `${holder} has control of the web browser. ${agentName} is waiting.`
              : `${holder} is taking control of the web browser. ${agentName} stops after the step it is on.`}
          </Notice>
          {takeover?.canRelease && (
            <Button variant="outline" onClick={() => setConfirmReturn(true)}>
              {`Return control to ${agentName}`}
            </Button>
          )}
        </div>
      )}
      {releaseError && !confirmReturn && (
        <Notice tone="warning" live>
          {releaseError}
        </Notice>
      )}
      {!active && <Notice>{finishedNotice(run)}</Notice>}

      <div className={browsing ? 'agent-desk-grid' : 'agent-desk-single'}>
        {browsing && (
          <section className="agent-desk-live" aria-label="Live view">
            {waitingForControl && (
              <div className="agent-desk-waiting">
                <Notice live>{`Waiting for ${agentName} to finish the step it is on. You get control as soon as it stops.`}</Notice>
                <Button variant="quiet" onClick={() => void cancelRequest()} loading={release.isPending}>
                  Cancel
                </Button>
              </div>
            )}
            {controlling && takeover ? (
              <TakeoverControls
                runId={runId}
                agentName={agentName}
                live={desk.live}
                view={takeover}
                onHandedBack={() => statusRef.current?.focus()}
              />
            ) : (
              <BrowserPreview runId={runId} active={active} personInControl={takeover ? takeover.active : undefined} />
            )}
          </section>
        )}
        <section className="agent-desk-steps" aria-label="Steps as they happen">
          {notStarted && (
            <p className="caption muted">{`${agentName} is getting started. Its steps appear here as it works.`}</p>
          )}
          <RunTraceCompact runId={runId} headingLevel="h3" limit={browsing ? 8 : 14} liveView={false} traceLink={false} />
        </section>
      </div>

      <ConfirmDialog
        open={confirmReturn}
        onClose={() => setConfirmReturn(false)}
        onConfirm={returnControl}
        eyebrow="Agent desk"
        title={`Return control to ${agentName}?`}
        description={`${holder} loses control of the web browser, and ${agentName} carries on from the page as it is now.`}
        confirmLabel="Return control"
        cancelLabel={`Leave it with ${holder}`}
        tone="primary"
        startOnCancel
        loading={release.isPending}
        error={releaseError}
      />
    </div>
  )
}

/** The desk's controls: Stop, Take over and Open full trace (which asks first while you hold control). */
function DeskFooter({
  runId,
  desk,
  onOpenTrace,
}: {
  runId: string
  desk: Desk
  onOpenTrace: (event: MouseEvent<HTMLAnchorElement>) => void
}) {
  const toast = useToast()
  const { run, active, takeover, controlling, waitingForControl, browsing, live } = desk
  const start = useStartTakeover(runId)
  const cancel = useCancelRun()
  const [confirmStop, setConfirmStop] = useState(false)
  const [stopError, setStopError] = useState<string | null>(null)
  const [startError, setStartError] = useState<string | null>(null)

  const canStop = active && can('run:cancel')
  const hasPage = Boolean(live && (live.url || live.frame))
  const canTakeOver =
    run?.status === 'running' &&
    browsing &&
    hasPage &&
    Boolean(takeover?.canTakeOver) &&
    !takeover?.active &&
    !takeover?.starting &&
    !controlling &&
    !waitingForControl

  const takeOver = async () => {
    setStartError(null)
    try {
      await start.mutateAsync()
    } catch (thrown) {
      setStartError(describeApiError(thrown))
    }
  }

  const stop = async () => {
    setStopError(null)
    try {
      await cancel.mutateAsync(runId)
      toast.success('Run stopped.')
      setConfirmStop(false)
    } catch (thrown) {
      setStopError(describeApiError(thrown))
    }
  }

  return (
    <div className="agent-desk-footer">
      {startError && (
        <Notice tone="warning" live>
          {startError}
        </Notice>
      )}
      <div className="agent-desk-actions">
        {canStop && (
          <Button variant="outline" onClick={() => setConfirmStop(true)}>
            Stop
          </Button>
        )}
        {canTakeOver && (
          <Button onClick={takeOver} loading={start.isPending}>
            Take over
          </Button>
        )}
        <a className="link agent-desk-trace" href={`/runs/${runId}`} onClick={onOpenTrace}>
          Open full trace
        </a>
      </div>
      <ConfirmDialog
        open={confirmStop}
        onClose={() => setConfirmStop(false)}
        onConfirm={stop}
        eyebrow="Stop a run"
        title="Stop this run?"
        description="The run is marked as stopped. A model or tool call already under way is not interrupted."
        confirmLabel="Stop run"
        cancelLabel="Keep it running"
        tone="danger"
        loading={cancel.isPending}
        error={stopError}
      />
    </div>
  )
}
