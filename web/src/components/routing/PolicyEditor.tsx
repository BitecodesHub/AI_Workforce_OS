import React from 'react'
import { Button, Card, ConfirmDialog, Eyebrow, Select, Tag } from '../ui'
import type { TagTone } from '../ui'
import { describeApiError } from '../../lib/api'
import { isEmbeddingModel, providerReadiness } from '../../lib/routing'
import type { ReadinessReason } from '../../lib/routing'
import type { CredentialView, Model, ModelPolicy, ModelPolicyInput, Provider } from '../../lib/queries'
import { useToast } from '../../lib/toast'
import { CandidateModelField } from './CandidateModelField'

/*
 * The candidate-chain editor: the ordered list of models a run tries, one after another, with what
 * happens when every one fails. It is the same editor in two places. On Model routing it edits the
 * workspace's chain, which every agent follows unless it has one of its own. On an agent's page it
 * edits that agent's own chain, and can hand the agent back to the workspace's.
 *
 * The caller says where the chain is saved (`onSave`, and `onClear` for an agent) and who may edit
 * it (`canManage`); this component owns the draft, the order, the readiness of each choice and the
 * words. Somebody who may not edit sees the chain read-only, in the same order.
 */

/** The most candidates a policy may hold (ModelPolicyController.MAX_CANDIDATES). */
const MAX_CANDIDATES = 10

const isSandbox = (provider: Provider) => provider.kind.toUpperCase() === 'SANDBOX'

/** Why the router would skip a candidate, as the tag beside it in the policy editor. */
const READINESS_TAG: Record<ReadinessReason, { tone: TagTone; label: string }> = {
  ready: { tone: 'success', label: 'Ready' },
  disabled: { tone: 'neutral', label: 'Off for this workspace, will be skipped' },
  platform_off: { tone: 'neutral', label: 'Not offered on this installation, will be skipped' },
  no_key: { tone: 'warning', label: 'No key stored, will be skipped' },
  rejected: { tone: 'warning', label: 'Key refused, will be skipped' },
  expired: { tone: 'warning', label: 'Key expired, will be skipped' },
  paused: { tone: 'warning', label: 'Paused after failures' },
}

/** The same reasons, short, after a provider's name in a list of choices. */
const READINESS_SUFFIX: Record<ReadinessReason, string> = {
  ready: '',
  disabled: ' (off)',
  platform_off: ' (not offered)',
  no_key: ' (no key)',
  rejected: ' (key refused)',
  expired: ' (key expired)',
  paused: ' (paused)',
}

const EXHAUSTED_COPY: Record<string, string> = {
  FAIL_CLOSED: 'If every candidate fails, runs stop with an error.',
  DEGRADE_TO_SANDBOX: 'If every candidate fails, runs fall back to the offline sandbox model.',
}

type DraftCandidate = {
  /** Stable across edits and moves, so a row keeps its fields (and focus) while the list changes. */
  key: string
  providerId: string
  modelId: string
  temperature: number | null
  maxOutputTokens: number | null
}

type MoveFocus = { key: string; direction: 'up' | 'down' }

export type PolicyEditorProps = {
  /** Whose chain this is: the workspace's, or one agent's own. */
  scope: 'workspace' | 'agent'
  /** The agent's name, for the agent scope's wording. */
  subject?: string
  policy: ModelPolicy
  providers: Provider[]
  models: Model[]
  /** Undefined until the stored keys have loaded; readiness is then left unsaid rather than guessed. */
  credentials: CredentialView[] | undefined
  /** Whether the chain can be changed here. False shows it read-only. */
  canManage: boolean
  now: number
  /** Saves the chain as the person arranged it. A rejection is shown to them in words. */
  onSave: (input: ModelPolicyInput) => Promise<unknown>
  /**
   * Agent scope: gives the agent back to the workspace's policy by deleting its own chain. Offered
   * only while the agent has one, and used when an empty chain is saved, because an agent with
   * no models of its own is an agent that follows the workspace's.
   */
  onClear?: () => Promise<unknown>
  /**
   * Offer every tool-capable model each provider lists (GET /api/providers/{id}/models) rather
   * than only the saved ones. Needs a query client; off, the picker shows the saved models alone.
   */
  liveCatalogue?: boolean
}

/**
 * Enabling a provider and storing its key only makes it usable, not chosen - the router still
 * needs an ordered chain to try, or every run keeps resolving to the built-in sandbox default.
 */
export function PolicyEditor({
  scope,
  subject = 'this agent',
  policy,
  providers,
  models,
  credentials,
  canManage,
  now,
  onSave,
  onClear,
  liveCatalogue = false,
}: PolicyEditorProps) {
  const toast = useToast()
  const agentScope = scope === 'agent'
  const [draft, setDraft] = React.useState<DraftCandidate[] | null>(null)
  const [saving, setSaving] = React.useState(false)
  const [confirmEmpty, setConfirmEmpty] = React.useState(false)
  const [confirmClear, setConfirmClear] = React.useState(false)
  const [dialogError, setDialogError] = React.useState<string | null>(null)
  const newRowCount = React.useRef(0)
  const pendingFocus = React.useRef<MoveFocus | null>(null)
  const listRef = React.useRef<HTMLDivElement>(null)

  const saved = React.useMemo<DraftCandidate[]>(
    () =>
      [...policy.candidates]
        .sort((a, b) => a.position - b.position)
        .map((candidate, index) => ({
          key: `saved-${index}`,
          providerId: candidate.providerId,
          modelId: candidate.modelId,
          temperature: candidate.temperature ?? null,
          maxOutputTokens: candidate.maxOutputTokens ?? null,
        })),
    [policy.candidates],
  )
  const candidates = draft ?? saved
  const dirty = draft !== null
  // The router skips a policy with no candidates, so only a non-empty one is the agent's own.
  const hasOwnChain = policy.configured && policy.candidates.length > 0

  const byId = React.useMemo(() => new Map(providers.map((provider) => [provider.id, provider])), [providers])
  const chatModels = React.useMemo(() => models.filter((model) => !isEmbeddingModel(model)), [models])
  // Only providers with a model that can answer a run are offered.
  const choosable = React.useMemo(
    () => providers.filter((provider) => chatModels.some((model) => model.providerId === provider.id)),
    [providers, chatModels],
  )

  // After Up or Down, focus stays on the same button in the row that moved, or on its other
  // button when the move has taken the row to the top or bottom.
  React.useEffect(() => {
    const focus = pendingFocus.current
    pendingFocus.current = null
    if (!focus || !listRef.current) return
    const find = (direction: 'up' | 'down') =>
      listRef.current?.querySelector<HTMLButtonElement>(`[data-candidate="${focus.key}"][data-move="${direction}"]`)
    const same = find(focus.direction)
    const target = same && !same.disabled ? same : find(focus.direction === 'up' ? 'down' : 'up')
    target?.focus()
  }, [draft])

  const readinessOf = (providerId: string): ReadinessReason | 'unavailable' | null => {
    const provider = byId.get(providerId)
    if (!provider) return 'unavailable'
    if (!credentials) return null
    return providerReadiness(provider, credentials, now).reason
  }

  const addCandidate = () => {
    const used = new Set(candidates.map((candidate) => `${candidate.providerId}/${candidate.modelId}`))
    const fresh = chatModels.filter((model) => !used.has(`${model.providerId}/${model.modelId}`))
    // A ready live model first, then any model not yet in the chain.
    const pick =
      fresh.find((model) => {
        const provider = byId.get(model.providerId)
        return provider !== undefined && !isSandbox(provider) && readinessOf(model.providerId) === 'ready'
      }) ??
      fresh[0] ??
      chatModels[0]
    if (!pick) return
    newRowCount.current += 1
    setDraft([
      ...candidates,
      { key: `new-${newRowCount.current}`, providerId: pick.providerId, modelId: pick.modelId, temperature: null, maxOutputTokens: null },
    ])
  }

  const updateCandidate = (index: number, change: Partial<DraftCandidate>) =>
    setDraft(candidates.map((candidate, i) => (i === index ? { ...candidate, ...change } : candidate)))

  const removeCandidate = (index: number) => setDraft(candidates.filter((_, i) => i !== index))

  const move = (index: number, direction: 'up' | 'down') => {
    const target = direction === 'up' ? index - 1 : index + 1
    if (target < 0 || target >= candidates.length) return
    const copy = candidates.slice()
    const [item] = copy.splice(index, 1)
    if (!item) return
    copy.splice(target, 0, item)
    pendingFocus.current = { key: item.key, direction }
    setDraft(copy)
  }

  const fieldLabels = Object.fromEntries(
    candidates.flatMap((_, index) => [
      [`candidates[${index}]`, `Candidate ${index + 1}`],
      [`candidates[${index}].providerId`, `Candidate ${index + 1} provider`],
      [`candidates[${index}].modelId`, `Candidate ${index + 1} model`],
    ]),
  )

  /** Runs a save or a clear; resolves to a sentence explaining the failure, or null. */
  const attempt = async (action: () => Promise<unknown>, done: string): Promise<string | null> => {
    setSaving(true)
    try {
      await action()
      toast.success(done)
      setDraft(null)
      return null
    } catch (err) {
      return describeApiError(err, fieldLabels)
    } finally {
      setSaving(false)
    }
  }

  const save = () =>
    attempt(
      () =>
        onSave({
          candidates: candidates.map(({ providerId, modelId, temperature, maxOutputTokens }) => ({
            providerId,
            modelId,
            temperature,
            maxOutputTokens,
          })),
        }),
      agentScope ? `${subject}'s models were saved.` : 'Routing policy saved.',
    )

  const clear = () =>
    attempt(() => onClear?.() ?? Promise.resolve(), `${subject} now follows the workspace routing policy.`)

  const handleSave = async () => {
    if (candidates.length === 0) {
      setDialogError(null)
      // An agent with no models of its own follows the workspace's, which is a clear, not a save.
      if (agentScope && onClear) setConfirmClear(true)
      else setConfirmEmpty(true)
      return
    }
    const failure = await save()
    if (failure) toast.error(failure)
  }

  const confirmEmptySave = async () => {
    const failure = await save()
    if (failure) setDialogError(failure)
    else setConfirmEmpty(false)
  }

  const confirmClearPolicy = async () => {
    const failure = await clear()
    if (failure) setDialogError(failure)
    else setConfirmClear(false)
  }

  const providerName = (providerId: string) => byId.get(providerId)?.displayName ?? providerId
  const modelName = (candidate: DraftCandidate) =>
    models.find((model) => model.providerId === candidate.providerId && model.modelId === candidate.modelId)?.displayName ??
    candidate.modelId

  const readinessTag = (providerId: string) => {
    const reason = readinessOf(providerId)
    if (reason === null) return null
    if (reason === 'unavailable') return <Tag tone="warning">Not available, will be skipped</Tag>
    const tag = READINESS_TAG[reason]
    return (
      <Tag tone={tag.tone} withDot title={tag.label}>
        {tag.label}
      </Tag>
    )
  }

  const exhausted = EXHAUSTED_COPY[(policy.exhaustedBehaviour ?? '').toUpperCase()] ?? EXHAUSTED_COPY.FAIL_CLOSED

  const heading = agentScope ? 'Model routing' : 'Routing policy'
  const intro = agentScope
    ? `Which language models answer for ${subject}. Give it a chain of its own, or let it follow the workspace routing policy.`
    : 'The order every agent tries by default, unless it has a chain of its own. With nothing set here, those agents answer on the offline sandbox model whatever is enabled above: a deliberate default, so no workspace is billed before it chooses to be.'
  const nothingSaid = dirty
    ? agentScope
      ? `No candidates. Saving this gives ${subject} back to the workspace routing policy.`
      : 'No candidates. Saving this sends every run of an agent without its own routing to the offline sandbox model.'
    : agentScope
      ? `${subject} has no models of its own, so it follows the workspace routing policy.`
      : policy.configured
        ? 'The saved policy has no candidates, so agents without routing of their own answer on the offline sandbox model.'
        : 'Nothing configured. Agents without routing of their own answer on the offline sandbox model.'
  const saveLabel = agentScope ? 'Save these models' : 'Save routing policy'

  return (
    // The routing page's own rules for a candidate row hang from this class, so the editor looks
    // the same wherever it is placed.
    <section
      id={agentScope ? undefined : 'routing-policy'}
      className="admin-model-routing"
      style={agentScope ? undefined : { scrollMarginTop: 'var(--space-7)' }}
    >
      <Card as="section">
        <Eyebrow as="h2">{heading}</Eyebrow>
        <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
          {intro}
        </p>

        {candidates.length === 0 && (
          <p className="muted" style={{ marginBottom: 'var(--space-4)' }}>
            {nothingSaid}
          </p>
        )}

        {canManage ? (
          <div ref={listRef} className="stack" style={{ gap: 'var(--space-4)', marginBottom: 'var(--space-5)' }}>
            {candidates.map((candidate, index) => {
              const number = index + 1
              const providerModels = chatModels.filter((model) => model.providerId === candidate.providerId)
              const knownProvider = choosable.some((provider) => provider.id === candidate.providerId)
              return (
                <div key={candidate.key} className="row candidate-row">
                  <span className="candidate-index mono muted" aria-hidden="true">
                    {number}.
                  </span>
                  <div className="policy-field">
                    <Select
                      label={`Candidate ${number} provider`}
                      value={candidate.providerId}
                      onChange={(event) => {
                        const providerId = event.target.value
                        const first = chatModels.find((model) => model.providerId === providerId)
                        updateCandidate(index, { providerId, modelId: first?.modelId ?? '', maxOutputTokens: null })
                      }}
                    >
                      {!knownProvider && <option value={candidate.providerId}>{`${candidate.providerId} (not available)`}</option>}
                      {choosable.map((provider) => {
                        const reason = credentials ? providerReadiness(provider, credentials, now).reason : 'ready'
                        return (
                          <option key={provider.id} value={provider.id}>
                            {`${provider.displayName}${READINESS_SUFFIX[reason]}`}
                          </option>
                        )
                      })}
                    </Select>
                  </div>
                  <CandidateModelField
                    live={liveCatalogue}
                    label={`Candidate ${number} model`}
                    providerId={candidate.providerId}
                    providerName={providerName(candidate.providerId)}
                    value={candidate.modelId}
                    savedName={modelName(candidate)}
                    savedModels={providerModels}
                    onChange={(modelId) => updateCandidate(index, { modelId, maxOutputTokens: null })}
                  />
                  <div className="row action-group" style={{ flexWrap: 'wrap', paddingBottom: '4px' }}>
                    {readinessTag(candidate.providerId)}
                    <Button
                      variant="outline"
                      className="button-sm"
                      aria-label={`Move candidate ${number} up`}
                      data-candidate={candidate.key}
                      data-move="up"
                      onClick={() => move(index, 'up')}
                      disabled={index === 0}
                    >
                      Up
                    </Button>
                    <Button
                      variant="outline"
                      className="button-sm"
                      aria-label={`Move candidate ${number} down`}
                      data-candidate={candidate.key}
                      data-move="down"
                      onClick={() => move(index, 'down')}
                      disabled={index === candidates.length - 1}
                    >
                      Down
                    </Button>
                    <Button
                      variant="outline"
                      className="button-sm"
                      aria-label={`Remove candidate ${number}`}
                      onClick={() => removeCandidate(index)}
                    >
                      Remove
                    </Button>
                  </div>
                </div>
              )
            })}
          </div>
        ) : (
          candidates.length > 0 && (
            <ol className="stack" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-5)' }}>
              {candidates.map((candidate, index) => (
                <li key={candidate.key} className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap' }}>
                  <span className="mono muted" aria-hidden="true">
                    {index + 1}.
                  </span>
                  <span>
                    {providerName(candidate.providerId)} · {modelName(candidate)}
                  </span>
                  {readinessTag(candidate.providerId)}
                </li>
              ))}
            </ol>
          )
        )}

        {candidates.length > 0 && (
          <p className="caption" style={{ marginBottom: 'var(--space-5)' }}>
            {exhausted}
          </p>
        )}

        {canManage && (
          <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap' }}>
            <Button
              variant="outline"
              onClick={addCandidate}
              disabled={chatModels.length === 0 || candidates.length >= MAX_CANDIDATES}
            >
              Add a candidate
            </Button>
            <Button onClick={() => void handleSave()} loading={saving && !confirmEmpty && !confirmClear} disabled={!dirty}>
              {saveLabel}
            </Button>
            {dirty && (
              <Button variant="quiet" onClick={() => setDraft(null)} disabled={saving}>
                Discard changes
              </Button>
            )}
            {agentScope && onClear && hasOwnChain && (
              <Button
                variant="quiet"
                onClick={() => {
                  setDialogError(null)
                  setConfirmClear(true)
                }}
                disabled={saving}
              >
                Use the workspace policy
              </Button>
            )}
            {candidates.length >= MAX_CANDIDATES && <span className="caption">A policy holds at most 10 candidates.</span>}
          </div>
        )}
      </Card>

      <ConfirmDialog
        open={confirmEmpty}
        onClose={() => setConfirmEmpty(false)}
        onConfirm={confirmEmptySave}
        eyebrow="Routing policy"
        title="Save an empty routing policy?"
        description="Every run of an agent without routing of its own will answer on the offline sandbox model."
        confirmLabel="Save empty policy"
        cancelLabel="Keep editing"
        tone="primary"
        loading={saving}
        error={dialogError}
      />

      {agentScope && onClear && (
        <ConfirmDialog
          open={confirmClear}
          onClose={() => setConfirmClear(false)}
          onConfirm={confirmClearPolicy}
          eyebrow="Model routing"
          title={`Use the workspace policy for ${subject}?`}
          description={`${subject}'s own models are removed. From its next run it follows the workspace routing policy, the same as every agent without a chain of its own.`}
          confirmLabel="Use the workspace policy"
          cancelLabel="Keep its models"
          tone="primary"
          loading={saving}
          error={dialogError}
        />
      )}
    </section>
  )
}
