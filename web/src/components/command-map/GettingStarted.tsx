import { useState, useSyncExternalStore } from 'react'
import type { ReactNode } from 'react'
import { Button, Card, Eyebrow } from '../ui'
import { TaskDialog } from '../ui/TaskDialog'
import { formatCount } from '../../lib/format'
import { roleLabel } from '../../lib/labels'
import { isGuideHidden, isStepDone, markStepDone, onGuideChange, setGuideHidden } from '../../lib/onboarding'
import type { GuideStep } from '../../lib/onboarding'
import { useApprovals, useCredentials, useModelPolicy, useProviders, useRuns, useSources } from '../../lib/queries'
import { liveRouting } from '../../lib/routing'
import { useRouter } from '../../lib/router'
import { can, profile } from '../../lib/session'
import { useToast } from '../../lib/toast'

/*
 * The getting-started guide on the Command Map.
 *
 * Someone signing in with a demo account lands on a table of runs, with no word on what a run,
 * goal or approval is or what to try first, and the five demo roles see very different consoles.
 * The guide lists the few things this person's role can actually do, each linked to the screen
 * that does it, and ticks them off as they are tried.
 *
 * What counts as tried is kept honest. Opening a screen reads "Opened", never "Done". Giving a
 * task is marked by the task dialog only once the task has really started, and reading a trace by
 * the run page itself. Two steps describe the workspace rather than the person (documents in
 * Knowledge, a live model), so they state what is true now instead of carrying a tick of their
 * own.
 *
 * Progress lives in localStorage per user (lib/onboarding.ts). When storage is blocked every step
 * reads as not tried and the guide still works; hiding it then lasts until the page is left.
 */

const ORDER: GuideStep[] = [
  'meet-agent',
  'give-task',
  'read-trace',
  'review-approvals',
  'search-documents',
  'add-document',
  'connect-model',
  'see-role',
]

/** The stored state as one string, so useSyncExternalStore can compare it by value. */
function readGuide(userId: string): string {
  const done = ORDER.filter((step) => isStepDone(userId, step))
  return `${isGuideHidden(userId) ? 'hidden' : 'shown'}:${done.join(',')}`
}

type Step = {
  id: GuideStep
  title: string
  body: ReactNode
  /**
   * link: opens a screen, and reads "Opened" once it has been. task: opens the task dialog, and
   * reads "Done" once a task has started. state: says what the workspace has; no tick of its own.
   */
  kind: 'link' | 'task' | 'state'
  href?: string
  satisfied: boolean
}

export function GettingStarted() {
  const { navigate } = useRouter()
  const toast = useToast()
  const me = profile()
  const userId = me?.userId ?? ''

  const stored = useSyncExternalStore(onGuideChange, () => readGuide(userId), () => readGuide(userId))
  const [hiddenHere, setHiddenHere] = useState(false)
  const [taskOpen, setTaskOpen] = useState(false)

  const storedHidden = stored.startsWith('hidden:')
  const done = new Set(stored.slice(stored.indexOf(':') + 1).split(',').filter(Boolean))

  const canGiveTask = can('task:create')
  const canReadRuns = can('run:read')
  const canReadApprovals = can('approval:read')
  const canManageKnowledge = can('knowledge:source_manage') && can('knowledge:read')
  const canManageModels = can('provider:manage') && can('provider:read')

  // The Command Map already loads the runs; this shares that request.
  const runs = useRuns()
  const approvals = useApprovals({ enabled: canReadApprovals })
  const sources = useSources({ enabled: canManageKnowledge })
  const policy = useModelPolicy({ enabled: canManageModels })
  const providers = useProviders({ enabled: canManageModels })
  const credentials = useCredentials({ enabled: canManageModels })

  const steps: Step[] = []

  if (can('agent:read')) {
    steps.push({
      id: 'meet-agent',
      kind: 'link',
      href: '/agents',
      title: 'Meet an agent and read what it does',
      body: 'Each agent has its own instructions, the tools it may use and a record of its runs.',
      satisfied: done.has('meet-agent'),
    })
  }

  if (canGiveTask) {
    steps.push({
      id: 'give-task',
      kind: 'task',
      title: 'Give an agent a task',
      body: 'Describe some work in a sentence or two, and an agent starts on it.',
      satisfied: done.has('give-task'),
    })
  }

  if (canReadRuns) {
    const newest = runs.data?.[0]
    steps.push({
      id: 'read-trace',
      kind: 'link',
      href: newest ? `/runs/${newest.id}` : '/runs',
      title: newest ? 'Read the trace of the latest run' : 'Read the trace of a run',
      body: 'A trace shows each step: what the agent was asked, which model answered, the tools it used and what it cost.',
      satisfied: done.has('read-trace'),
    })
  }

  if (canReadApprovals) {
    const count = approvals.data?.length
    const waiting = count === undefined ? '' : count === 0 ? 'None waiting. ' : `${formatCount(count)} waiting now. `
    steps.push({
      id: 'review-approvals',
      kind: 'link',
      href: '/approvals',
      title: can('approval:decide') ? 'Decide an approval' : 'Review the approvals queue',
      body: `${waiting}Agents stop and ask here before an action that needs a person to agree to it.`,
      satisfied: done.has('review-approvals'),
    })
  }

  if (can('chat:use')) {
    steps.push({
      id: 'search-documents',
      kind: 'link',
      href: '/chat',
      title: 'Ask the workforce',
      body: 'Chat routes what you ask to the right agent, or agents, and also searches your documents when it reads as a question about them.',
      satisfied: done.has('search-documents'),
    })
  }

  // The two workspace facts appear once they are known. Until their data loads, or if it fails,
  // the guide says nothing about them rather than something that may be untrue.
  if (canManageKnowledge && sources.data) {
    const total = sources.data.reduce((sum, source) => sum + source.documentCount, 0)
    const hasDocuments = total > 0
    steps.push({
      id: 'add-document',
      kind: 'state',
      href: '/knowledge',
      title: hasDocuments
        ? `This workspace has ${formatCount(total)} ${total === 1 ? 'document' : 'documents'}.`
        : 'Add a document to Knowledge',
      body: hasDocuments ? (
        <>
          Chat searches these.{' '}
          <a className="link" href="/knowledge">
            Open Knowledge
          </a>
        </>
      ) : (
        'Chat searches only the documents added to Knowledge.'
      ),
      satisfied: hasDocuments,
    })
  }

  if (canManageModels && policy.data && providers.data && credentials.data) {
    const routing = liveRouting(policy.data, providers.data, credentials.data)
    const live = routing.live
    steps.push({
      id: 'connect-model',
      kind: 'state',
      href: '/routing',
      title: live ? `Runs try ${routing.first?.providerName ?? 'a live model'} first.` : 'Connect a live model',
      // The notice above the guide gives the full reason; this line only says what it means now.
      body: live ? (
        <>
          From the workspace routing policy.{' '}
          <a className="link" href="/routing">
            Open Model routing
          </a>
        </>
      ) : routing.reason === 'sandbox_first' ? (
        'The routing policy puts the offline sandbox model first, so it answers every run.'
      ) : routing.fallsBackToSandbox ? (
        'Until then, runs answer on the offline sandbox model, which gives placeholder replies.'
      ) : (
        'Until a provider is ready, runs fail.'
      ),
      satisfied: live,
    })
  }

  steps.push({
    id: 'see-role',
    kind: 'link',
    href: '/profile',
    title: 'See what your role allows',
    body: me?.role
      ? `Your role is ${roleLabel(me.role)}. Your profile lists what it can and cannot do.`
      : 'Your profile lists what your role can and cannot do.',
    satisfied: done.has('see-role'),
  })

  const allSatisfied = steps.every((step) => step.satisfied)
  // A workspace fact still loading has no step yet, so the guide cannot tell whether it holds.
  const factsPending =
    (canManageKnowledge && sources.isLoading) ||
    (canManageModels && (policy.isLoading || providers.isLoading || credentials.isLoading))

  /**
   * After a step is taken: when it was the last one left, the guide hides itself for next time.
   * A guide brought back from the account menu with every step already done stays until the
   * person hides it.
   */
  const afterStep = (id: GuideStep) => {
    if (!userId) return
    const wasComplete = allSatisfied
    markStepDone(userId, id)
    if (!wasComplete && !factsPending && steps.every((step) => step.id === id || step.satisfied)) {
      setGuideHidden(userId, true)
    }
  }

  const hide = () => {
    const saved = userId ? setGuideHidden(userId, true) : false
    // Only when storage refused: a saved setting hides the guide through the store, which is also
    // what lets the account menu's "Show getting started" bring it back without a reload.
    setHiddenHere(!saved)
    toast.info(
      saved
        ? 'The guide is hidden. Your account menu can show it again.'
        : 'The guide is hidden until you leave this page. This browser is not keeping the setting.',
    )
  }

  if (storedHidden || hiddenHere) return null

  return (
    <Card as="section">
      <div
        className="row"
        style={{ justifyContent: 'space-between', alignItems: 'flex-start', gap: 'var(--space-4)', flexWrap: 'wrap' }}
      >
        <div style={{ minWidth: 0, flex: '1 1 320px' }}>
          <Eyebrow as="h2">Getting started</Eyebrow>
          <p className="muted" style={{ maxWidth: '62ch' }}>
            {allSatisfied
              ? 'You have been through every step. Hide this guide when you no longer need it.'
              : 'A short tour of what your role can do here. Take the steps in any order.'}
          </p>
        </div>
        <Button variant="quiet" className="button-sm" onClick={hide}>
          Hide this guide
        </Button>
      </div>

      {/* Two columns where there is room, so the guide does not push the day's figures off the
          first screen; one column on a phone. Each step keeps a rule above it in either layout. */}
      <ol
        style={{
          listStyle: 'none',
          margin: 'var(--space-5) 0 0',
          padding: 0,
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 340px), 1fr))',
          columnGap: 'var(--space-6)',
        }}
      >
        {steps.map((step, index) => (
          <li
            key={step.id}
            style={{
              display: 'flex',
              alignItems: 'flex-start',
              gap: 'var(--space-4)',
              padding: 'var(--space-4) 0',
              borderTop: '1px solid var(--line)',
            }}
          >
            <StepMarker satisfied={step.satisfied} number={index + 1} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <p className="section-heading">
                <StepAction step={step} onOpen={() => afterStep(step.id)} onGiveTask={() => setTaskOpen(true)} />
              </p>
              <p className="caption" style={{ marginTop: 'var(--space-1)' }}>
                {step.body}
              </p>
            </div>
            {step.satisfied && step.kind !== 'state' && (
              <span className="caption" style={{ whiteSpace: 'nowrap' }}>
                {step.kind === 'task' ? 'Done' : 'Opened'}
              </span>
            )}
          </li>
        ))}
      </ol>

      {canGiveTask && (
        <TaskDialog
          open={taskOpen}
          onClose={() => setTaskOpen(false)}
          onSuccess={(_runId, started) => {
            // The dialog has already recorded the step; this only hides a finished guide.
            afterStep('give-task')
            navigate(started.href)
          }}
        />
      )}
    </Card>
  )
}

function StepAction({ step, onOpen, onGiveTask }: { step: Step; onOpen: () => void; onGiveTask: () => void }) {
  if (step.kind === 'task') {
    return (
      <button type="button" className="link" style={{ cursor: 'pointer', textAlign: 'left' }} onClick={onGiveTask}>
        {step.title}
      </button>
    )
  }
  // A workspace fact that already holds is a statement, with its link in the line below.
  if (step.kind === 'state' && step.satisfied) return <>{step.title}</>
  return (
    <a className="link" href={step.href} onClick={step.kind === 'link' ? onOpen : undefined}>
      {step.title}
    </a>
  )
}

function StepMarker({ satisfied, number }: { satisfied: boolean; number: number }) {
  return (
    <span
      aria-hidden="true"
      className="tabular"
      style={{
        flex: 'none',
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: 22,
        height: 22,
        borderRadius: 'var(--radius-pill)',
        border: '1px solid var(--line)',
        background: 'var(--surface)',
        color: satisfied ? 'var(--green)' : 'var(--muted)',
        fontSize: 'var(--text-caption)',
        fontWeight: 'var(--weight-emphasis)',
      }}
    >
      {satisfied ? (
        <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
          <path d="M2.5 6.2l2.3 2.3 4.7-5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      ) : (
        number
      )}
    </span>
  )
}
