// @find: grant connector to agent, give agent access, agent capabilities, change what agent may do, tool permissions, Grant dialog, connector grant, agent page
// @what: Dialog to give an agent a connector or change what it may do there.
// @flow: Used by the agent detail page.
import { useEffect, useId, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Dialog, Input, Tag } from '../ui'
import { describeApiError } from '../../lib/api'
import {
  MAX_CALLS_PER_RUN,
  MIN_CALLS_PER_RUN,
  capabilityLabel,
  connectorCategory,
  connectorState,
  grantTools,
  groupCapabilities,
  initialSelection,
  parseCallLimit,
} from '../../lib/connectors'
import { formatCount } from '../../lib/format'
import { connectorCategoryLabel, connectorStateLabel, serverLabel } from '../../lib/labels'
import { useSaveAgentGrant } from '../../lib/queries'
import type { AgentDetail, AgentGrant, GrantInput, Integration, Tool } from '../../lib/queries'
import { useToast } from '../../lib/toast'

/*
 * Giving an agent a connector, or changing what it may do there.
 *
 * Two steps. First the connector, from a searchable list that shows each one's category and
 * whether it reaches a live account or the sandbox; one the agent already has opens its current
 * settings rather than being refused. Then what the agent may do: reads start ticked, and creating,
 * sending and deleting start unticked, because each of those is a choice somebody should make on
 * purpose. Sending and deleting always wait for a person whatever is ticked here; the switch makes
 * every action from the connector wait.
 *
 * The parent keys this component per opening, so every opening starts fresh.
 */

const FIELD_LABELS: Record<string, string> = {
  tools: 'What it may do',
  requireApproval: 'Always ask first',
  maxCallsPerRun: 'Most calls per run',
}

function connectorName(integration: Pick<Integration, 'server' | 'displayName'>): string {
  return serverLabel(integration.server, integration.displayName)
}

// @find: GrantDialog, grant dialog, grant connector to agent, give agent access, agent capabilities, change what agent may do
export function GrantDialog({
  open,
  onClose,
  agent,
  integrations,
  initialServer,
}: {
  open: boolean
  onClose: () => void
  agent: Pick<AgentDetail, 'id' | 'name' | 'grants'>
  /** Every connector, loaded: the picker lists them and the second step reads their tools. */
  integrations: readonly Integration[]
  /** Opens straight on this connector's settings (Edit). Null starts at the picker (Add). */
  initialServer: string | null
}) {
  const save = useSaveAgentGrant(agent.id)
  const toast = useToast()
  const [server, setServer] = useState<string | null>(initialServer)
  const [error, setError] = useState<string | null>(null)
  // Back is offered only to someone who came through the picker.
  const cameFromPicker = initialServer === null

  const integration = server ? integrations.find((item) => item.server === server) : undefined
  const grant = server ? agent.grants.find((item) => item.server === server) : undefined
  const name = integration ? connectorName(integration) : server ? serverLabel(server) : ''

  const close = () => {
    if (save.isPending) return
    setError(null)
    save.reset()
    onClose()
  }

  const title = !server
    ? `Add a connector to ${agent.name}`
    : grant
      ? `What ${agent.name} may do in ${name}`
      : `Add ${name} to ${agent.name}`

  return (
    <Dialog
      open={open}
      onClose={close}
      eyebrow={server ? (grant ? 'Edit connector' : 'Step 2 of 2') : 'Step 1 of 2'}
      title={title}
      description={
        server
          ? 'Choose what it may do. Sending and deleting always wait for a person to approve them.'
          : 'Choose the app or service this agent should be able to act in.'
      }
      dismissible={!save.isPending}
      error={error}
    >
      {!server ? (
        <ConnectorPicker
          integrations={integrations}
          grantedServers={agent.grants.map((item) => item.server)}
          onCancel={close}
          onPick={(picked) => {
            setError(null)
            setServer(picked)
          }}
        />
      ) : integration ? (
        <GrantCapabilitiesForm
          // A new form per connector, so going back and choosing another starts from its defaults.
          key={integration.server}
          connectorName={name}
          tools={integration.tools}
          grant={grant ?? null}
          saving={save.isPending}
          onBack={
            cameFromPicker
              ? () => {
                  setError(null)
                  setServer(null)
                }
              : undefined
          }
          onCancel={close}
          onSubmit={async (input) => {
            setError(null)
            try {
              await save.mutateAsync({ server: integration.server, ...input })
              toast.success(grant ? `Saved what ${agent.name} may do in ${name}` : `${name} was added to ${agent.name}`)
              onClose()
            } catch (err) {
              setError(describeApiError(err, FIELD_LABELS))
            }
          }}
        />
      ) : (
        <p className="muted">This connector is no longer offered in this workspace.</p>
      )}
    </Dialog>
  )
}

/**
 * Moving between the two steps replaces the dialog's content, and the button that had focus goes
 * with it. Each step puts focus on its own first field when it appears, so a keyboard user carries
 * on from the top of the new step rather than from the top of the page. On the dialog's first
 * opening the dialog is still closed here, focus() does nothing, and Dialog places focus itself.
 */
function useFocusFirstField(formRef: React.RefObject<HTMLFormElement | null>) {
  useEffect(() => {
    formRef.current?.querySelector<HTMLElement>('input:not(:disabled)')?.focus()
  }, [formRef])
}

/* ---- Step 1: the connector ------------------------------------------------------------------- */

function ConnectorPicker({
  integrations,
  grantedServers,
  onPick,
  onCancel,
}: {
  integrations: readonly Integration[]
  grantedServers: readonly string[]
  onPick: (server: string) => void
  onCancel: () => void
}) {
  const groupName = useId()
  const formRef = useRef<HTMLFormElement>(null)
  const [search, setSearch] = useState('')
  const [choice, setChoice] = useState<string | null>(null)

  useFocusFirstField(formRef)

  const shown = useMemo(() => {
    const terms = search.toLowerCase().split(/\s+/).filter(Boolean)
    const sorted = [...integrations].sort((a, b) => connectorName(a).localeCompare(connectorName(b)))
    if (terms.length === 0) return sorted
    return sorted.filter((integration) => {
      const text = [
        connectorName(integration),
        integration.server,
        connectorCategoryLabel(connectorCategory(integration)),
        integration.description ?? '',
      ]
        .join(' ')
        .toLowerCase()
      return terms.every((term) => text.includes(term))
    })
  }, [integrations, search])

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (choice) onPick(choice)
  }

  return (
    <form ref={formRef} onSubmit={handleSubmit}>
      <div className="stack" style={{ gap: 'var(--space-4)' }}>
        <Input
          type="search"
          label="Search connectors"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          placeholder="Name, category or what it is for"
          autoComplete="off"
          spellCheck={false}
          data-autofocus
        />
        <fieldset style={{ border: 0, margin: 0, padding: 0, minWidth: 0 }}>
          <legend className="field-label">Connector</legend>
          {shown.length === 0 ? (
            <p className="muted" style={{ marginTop: 'var(--space-3)' }}>
              No connector matches that search.
            </p>
          ) : (
            // A plain block, not a flex column: in a column with a height limit the rows would
            // shrink below their content and overlap instead of scrolling.
            <div
              style={{
                marginTop: 'var(--space-2)',
                maxHeight: 'min(360px, 46vh)',
                overflowY: 'auto',
                paddingRight: 'var(--space-1)',
              }}
            >
              {shown.map((integration) => {
                const granted = grantedServers.includes(integration.server)
                const state = connectorStateLabel(connectorState(integration))
                return (
                  <label key={integration.server} className="question-option">
                    <input
                      type="radio"
                      name={groupName}
                      value={integration.server}
                      checked={choice === integration.server}
                      onChange={() => setChoice(integration.server)}
                    />
                    <span className="question-option-label">
                      {connectorName(integration)}
                      <span className="question-option-description">
                        {connectorCategoryLabel(connectorCategory(integration))}
                        {integration.description ? ` · ${integration.description}` : ''}
                      </span>
                    </span>
                    <span className="row" style={{ gap: 'var(--space-2)', flexWrap: 'wrap', justifyContent: 'flex-end' }}>
                      {granted && <Tag tone="blue">Added</Tag>}
                      <Tag tone={state.tone}>{state.label}</Tag>
                    </span>
                  </label>
                )
              })}
            </div>
          )}
        </fieldset>
      </div>
      <div className="dialog-footer">
        <Button variant="outline" onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" disabled={!choice}>
          {choice && grantedServers.includes(choice) ? 'Edit its settings' : 'Next'}
        </Button>
      </div>
    </form>
  )
}

/* ---- Step 2: what it may do ------------------------------------------------------------------- */

// @find: GrantCapabilitiesForm, grant capabilities form, grant connector to agent, give agent access, agent capabilities, change what agent may do
export function GrantCapabilitiesForm({
  connectorName: name,
  tools,
  grant,
  saving,
  onSubmit,
  onBack,
  onCancel,
}: {
  connectorName: string
  tools: readonly Tool[]
  /** The agent's current grant on this connector, or null for a new one. */
  grant: Pick<AgentGrant, 'tools' | 'requireApproval' | 'maxCallsPerRun'> | null
  saving: boolean
  onSubmit: (input: GrantInput) => void | Promise<void>
  onBack?: (() => void) | undefined
  onCancel: () => void
}) {
  const baseId = useId()
  const formRef = useRef<HTMLFormElement>(null)
  const [selected, setSelected] = useState<string[]>(() => initialSelection(grant, tools))
  const [requireApproval, setRequireApproval] = useState(grant?.requireApproval ?? false)
  const [callLimit, setCallLimit] = useState(grant?.maxCallsPerRun == null ? '' : String(grant.maxCallsPerRun))

  const groups = useMemo(() => groupCapabilities(tools), [tools])
  const limit = parseCallLimit(callLimit)
  const limitProblem =
    limit === false ? `Enter a whole number from ${MIN_CALLS_PER_RUN} to ${MAX_CALLS_PER_RUN}, or leave it empty.` : null
  const allChosen = tools.length > 0 && selected.length === tools.length
  const nothingChosen = selected.length === 0

  useFocusFirstField(formRef)

  const toggle = (toolName: string, on: boolean) =>
    setSelected((current) => (on ? [...current, toolName] : current.filter((existing) => existing !== toolName)))

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (saving || nothingChosen || limit === false) return
    void onSubmit({ tools: grantTools(selected, tools), requireApproval, maxCallsPerRun: limit })
  }

  return (
    <form ref={formRef} onSubmit={handleSubmit}>
      <div className="stack" style={{ gap: 'var(--space-6)' }}>
        <fieldset style={{ border: 0, margin: 0, padding: 0, minWidth: 0 }} aria-describedby={`${baseId}-count`}>
          <legend className="field-label">What it may do in {name}</legend>
          <div
            className="row"
            style={{ justifyContent: 'space-between', gap: 'var(--space-3)', flexWrap: 'wrap', margin: 'var(--space-2) 0 var(--space-4)' }}
          >
            <span className="caption" id={`${baseId}-count`} aria-live="polite">
              {nothingChosen
                ? 'Nothing chosen yet. Choose at least one.'
                : `${formatCount(selected.length)} of ${formatCount(tools.length)} chosen`}
            </span>
            <Button
              variant="quiet"
              className="button-sm"
              onClick={() => setSelected(allChosen ? [] : tools.map((tool) => tool.name))}
              disabled={tools.length === 0}
            >
              {allChosen ? 'Clear all' : 'Select all'}
            </Button>
          </div>

          {groups.length === 0 ? (
            <p className="muted">{name} offers nothing an agent can use yet.</p>
          ) : (
            <div className="stack" style={{ gap: 'var(--space-5)' }}>
              {groups.map((group) => {
                const headingId = `${baseId}-${group.key}`
                return (
                  <div key={group.key} role="group" aria-labelledby={headingId}>
                    <p className="caption" id={headingId} style={{ marginBottom: 'var(--space-2)' }}>
                      {group.label}
                    </p>
                    {group.tools.map((tool) => (
                      <label key={tool.name} className="question-option">
                        <input
                          type="checkbox"
                          name="tools"
                          value={tool.name}
                          checked={selected.includes(tool.name)}
                          onChange={(event) => toggle(tool.name, event.target.checked)}
                        />
                        <span className="question-option-label">
                          {capabilityLabel(tool)}
                          {tool.description && <span className="question-option-description">{tool.description}</span>}
                        </span>
                        {!group.asksFirst && tool.alwaysRequiresApproval && <Tag tone="warning">Asks first</Tag>}
                      </label>
                    ))}
                  </div>
                )
              })}
            </div>
          )}
        </fieldset>

        <label className="question-option">
          <input
            type="checkbox"
            role="switch"
            checked={requireApproval}
            onChange={(event) => setRequireApproval(event.target.checked)}
          />
          <span className="question-option-label">
            Always ask a person before any action from this connector
            <span className="question-option-description">
              Reads and edits included. Sending and deleting always ask, whether this is on or off.
            </span>
          </span>
        </label>

        <Input
          label="Most calls per run"
          optional
          type="number"
          inputMode="numeric"
          min={MIN_CALLS_PER_RUN}
          max={MAX_CALLS_PER_RUN}
          step={1}
          value={callLimit}
          onChange={(event) => setCallLimit(event.target.value)}
          placeholder="No limit"
          hint={`From ${MIN_CALLS_PER_RUN} to ${MAX_CALLS_PER_RUN}. Leave it empty for no limit. A run that reaches it cannot use ${name} again.`}
          error={limitProblem}
        />
      </div>

      <div className="dialog-footer">
        {onBack && (
          <Button variant="quiet" onClick={onBack} disabled={saving} style={{ marginRight: 'auto' }}>
            Back
          </Button>
        )}
        <Button variant="outline" onClick={onCancel} disabled={saving}>
          Cancel
        </Button>
        <Button type="submit" loading={saving} disabled={nothingChosen || limit === false}>
          {grant ? 'Save changes' : 'Add connector'}
        </Button>
      </div>
    </form>
  )
}
