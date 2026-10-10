// @find: set up your workspace, setup, getting started, checklist, onboarding, connect your AI, free model, notifications step, finish setup, /setup, Setup page
// @what: The Setup page: a checklist of steps to get a new workspace working, each with a button to finish it.
// @flow: Routed from App.tsx at /setup; reuses the model and notification panels from other pages
import { useState } from 'react'
import type { ReactNode } from 'react'
import { Button, Card, Notice, PageHeader, Tag } from '../components/ui'
import type { TagTone } from '../components/ui'
import { QueryState } from '../components/ui/QueryState'
import { ConnectModelDialog } from '../components/onboarding/ConnectModelDialog'
import { EmbeddingSettingsCard } from '../components/knowledge/EmbeddingSettingsCard'
import { BudgetCard } from '../components/analytics/BudgetCard'
import { BrowserNotificationsCard } from '../components/settings/BrowserNotificationsCard'
import { TestNow } from '../components/routing/TestNow'
import { ModelOrderPicker } from '../components/setup/ModelOrderPicker'
import { InviteInline } from '../components/setup/InviteInline'
import { QuickUpload } from '../components/setup/QuickUpload'
import { ToolsShortlist } from '../components/setup/ToolsShortlist'
import { NotificationsCard } from './Settings'
import { KEY_PAGES } from '../lib/keyFormats'
import { useModelPolicy } from '../lib/queries'
import { useNotificationSettings } from '../lib/settingsQueries'
import { STATUS_LABEL, type SetupStatus, type SetupStep, type SetupStepId } from '../lib/setup'
import { useSetupSteps } from '../lib/setupQueries'
import { can } from '../lib/session'

/*
 * Set up your workspace.
 *
 * Everything a new workspace needs, on one page, in the order it matters: a live model first,
 * because without one every run answers with placeholder text. Each step says whether it is done,
 * needs attention, or is optional, worked out from what the services say (lib/setupQueries.ts),
 * and is finished here: the step opens the same dialog or card its own page uses, so nothing is
 * done two ways. Every step can be skipped; a skipped one is only folded away on this visit, and
 * its status still says what is true.
 */

const TONE: Record<SetupStatus, TagTone> = {
  done: 'success',
  attention: 'warning',
  optional: 'neutral',
  checking: 'neutral',
  not_allowed: 'neutral',
}

const TITLE: Record<SetupStepId, string> = {
  model: 'Connect an AI model',
  order: 'Choose the model order',
  search: 'Search documents by meaning',
  tools: 'Connect your tools',
  knowledge: 'Add knowledge',
  team: 'Invite your team',
  notifications: 'Get notified',
  budget: 'Set a budget',
}

/** The words on the button that opens a step. */
const OPEN_LABEL: Record<SetupStepId, string> = {
  model: 'Connect a model',
  order: 'Choose the order',
  search: 'Choose how to search',
  tools: 'Connect a tool',
  knowledge: 'Upload files',
  team: 'Invite someone',
  notifications: 'Set up notifications',
  budget: 'Set a budget',
}

// @find: Setup component, set up your workspace checklist, /setup
export function Setup() {
  const { steps, summary } = useSetupSteps()
  const [connectOpen, setConnectOpen] = useState(false)
  const [skipped, setSkipped] = useState<ReadonlySet<SetupStepId>>(new Set())
  // The first step still needing attention starts open, so the page begins where the work is.
  const firstOpen = steps.find((step) => step.status === 'attention')?.id ?? null
  const [opened, setOpened] = useState<SetupStepId | null | undefined>(undefined)
  const open = opened === undefined ? firstOpen : opened

  const share = summary.total === 0 ? 0 : summary.done / summary.total
  const toggle = (id: SetupStepId) => setOpened(open === id ? null : id)
  const skip = (id: SetupStepId) => {
    setSkipped((current) => new Set(current).add(id))
    const next = steps.find((step) => step.id !== id && step.status === 'attention' && !skipped.has(step.id))
    setOpened(next?.id ?? null)
  }

  return (
    <div className="page">
      <PageHeader
        eyebrow="Getting set up"
        title="Set up your workspace"
        description="The few things that make agents useful here, in the order they matter. Each one is finished on this page, and any can be skipped and done later."
      />

      <Card as="section">
        <div className="setup-progress">
          <p className="section-heading" style={{ margin: 0 }} id="setup-progress-label">
            {summary.complete
              ? summary.done < summary.total
                ? `Your workspace is set up: ${summary.done} of ${summary.total} done, and the rest are optional.`
                : 'Your workspace is set up.'
              : `${summary.done} of ${summary.total} done${summary.remaining ? `, ${summary.remaining} ${summary.remaining === 1 ? 'step needs' : 'steps need'} attention` : ''}`}
          </p>
          <progress className="setup-progress-bar" value={share} max={1} aria-labelledby="setup-progress-label" />
          {summary.complete && (
            <p className="caption" style={{ margin: 0 }}>
              The optional steps below can still be done at any time.{' '}
              <a className="link" href="/">
                Go to the Command Map
              </a>
            </p>
          )}
        </div>
      </Card>

      <ol className="setup-steps">
        {steps.map((step, index) => (
          <SetupRow
            key={step.id}
            step={step}
            number={index + 1}
            open={open === step.id}
            skipped={skipped.has(step.id)}
            onToggle={() => toggle(step.id)}
            onSkip={() => skip(step.id)}
            onConnect={() => setConnectOpen(true)}
          />
        ))}
      </ol>

      {can('provider:manage') && (
        <ConnectModelDialog
          open={connectOpen}
          onClose={() => setConnectOpen(false)}
          initialProviderId={steps[0]?.status === 'done' ? undefined : 'nvidia'}
        />
      )}
    </div>
  )
}

// @find: setup step row, done, to do, open step
function SetupRow({
  step,
  number,
  open,
  skipped,
  onToggle,
  onSkip,
  onConnect,
}: {
  step: SetupStep
  number: number
  open: boolean
  skipped: boolean
  onToggle: () => void
  onSkip: () => void
  onConnect: () => void
}) {
  const panelId = `setup-panel-${step.id}`
  const actionable = step.status !== 'not_allowed' && step.status !== 'checking'
  // Connecting a model is one dialog, so its button opens that instead of a panel.
  const opensDialog = step.id === 'model' && step.status !== 'done'
  return (
    <li className="setup-step" data-status={step.status}>
      <div className="setup-step-head">
        <StepMarker done={step.status === 'done'} number={number} />
        <div className="setup-step-text">
          <div className="setup-step-title">
            <h2 className="section-heading" style={{ margin: 0 }}>
              {TITLE[step.id]}
            </h2>
            <Tag tone={TONE[step.status]}>{skipped && step.status !== 'done' ? 'Skipped for now' : STATUS_LABEL[step.status]}</Tag>
          </div>
          <p className="caption" style={{ margin: 0 }}>
            {step.detail}
          </p>
        </div>
        {actionable && (
          <div className="setup-step-actions">
            {opensDialog ? (
              <Button onClick={onConnect}>{OPEN_LABEL.model}</Button>
            ) : (
              <Button
                variant={step.status === 'attention' && !skipped ? 'primary' : 'outline'}
                className={step.status === 'attention' && !skipped ? undefined : 'button-sm'}
                aria-expanded={open}
                aria-controls={panelId}
                aria-label={`${open ? 'Close' : step.status === 'done' ? 'Change' : OPEN_LABEL[step.id]}: ${TITLE[step.id]}`}
                onClick={onToggle}
              >
                {open ? 'Close' : step.status === 'done' ? 'Change' : OPEN_LABEL[step.id]}
              </Button>
            )}
            {step.status === 'attention' && !skipped && (
              <Button variant="quiet" className="button-sm" aria-label={`Skip for now: ${TITLE[step.id]}`} onClick={onSkip}>
                Skip for now
              </Button>
            )}
          </div>
        )}
      </div>
      {actionable && !opensDialog && open && (
        <div id={panelId} className="setup-step-panel">
          <StepPanel id={step.id} onConnect={onConnect} />
        </div>
      )}
      {step.id === 'model' && step.status !== 'done' && actionable && <FreeStart />}
    </li>
  )
}

/** The free way in, under the model step, while there is no live model. */
// @find: free start, recommended free model
function FreeStart() {
  const nvidia = KEY_PAGES.nvidia!
  return (
    <p className="caption setup-step-note">
      No key yet? Start free with NVIDIA NIM:{' '}
      <a className="link" href={nvidia.url} target="_blank" rel="noreferrer">
        get a free NVIDIA key
      </a>{' '}
      ({nvidia.note.toLowerCase().replace(/\.$/, '')}), then paste it into Connect a model. Groq, OpenRouter and Google
      Gemini have free tiers too, and Amazon Bedrock and the others are in the same dialog.
    </p>
  )
}

// @find: step panel, expanded setup step
function StepPanel({ id, onConnect }: { id: SetupStepId; onConnect: () => void }): ReactNode {
  switch (id) {
    case 'model':
      return <ModelPanel onConnect={onConnect} />
    case 'order':
      return <ModelOrderPicker />
    case 'search':
      return <EmbeddingSettingsCard />
    case 'tools':
      return <ToolsShortlist />
    case 'knowledge':
      return <QuickUpload />
    case 'team':
      return <InviteInline />
    case 'notifications':
      return <NotificationsPanel />
    case 'budget':
      return <BudgetCard />
  }
}

/** A connected model: test the first choice now, or connect another. */
// @find: model step panel, connect your AI
function ModelPanel({ onConnect }: { onConnect: () => void }) {
  const policy = useModelPolicy()
  const first = policy.data?.candidates[0]
  return (
    <div className="row" style={{ gap: 'var(--space-4)', flexWrap: 'wrap', alignItems: 'flex-start' }}>
      {first && <TestNow providerId={first.providerId} modelId={first.modelId} name={first.modelId} />}
      <Button variant="outline" className="button-sm" onClick={onConnect}>
        Connect another model
      </Button>
      <a className="link caption" href="/routing" style={{ alignSelf: 'center' }}>
        Open Model routing
      </a>
    </div>
  )
}

// @find: notifications step panel
function NotificationsPanel() {
  const settings = useNotificationSettings()
  return (
    <div className="stack" style={{ gap: 'var(--space-4)' }}>
      <Notice tone="info">Either is enough: a webhook tells a team channel, a browser notification tells you.</Notice>
      <BrowserNotificationsCard />
      <QueryState query={settings} permission="workspace:update" what="the notification settings" rows={3}>
        {(loaded) => <NotificationsCard key={`${loaded.webhookUrl}:${loaded.hasSecret}:${loaded.events.join(',')}`} settings={loaded} />}
      </QueryState>
    </div>
  )
}

// @find: step marker, tick or number
function StepMarker({ done, number }: { done: boolean; number: number }) {
  return (
    <span aria-hidden="true" className={`setup-marker tabular${done ? ' setup-marker-done' : ''}`}>
      {done ? (
        <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
          <path d="M2.5 6.2l2.3 2.3 4.7-5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      ) : (
        number
      )}
    </span>
  )
}
