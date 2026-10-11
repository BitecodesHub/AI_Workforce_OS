// @find: skill editor, create skill, edit skill, new skill dialog, save as skill, skill instructions, default agent, required connectors, POST /api/skills, PUT /api/skills/{id}, SkillEditorDialog
// @what: The dialog for writing or changing a skill: name, description, instructions, default agent and the connectors it needs.
// @flow: Opened from routes/Skills.tsx and from the Chat thread menu with a prefilled draft; uses lib/skillQueries, useAgents and useIntegrations.
import { useId, useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Dialog, Input, Notice, Select, Textarea } from '../ui'
import { ApiError, describeApiError } from '../../lib/api'
import { formatCount } from '../../lib/format'
import { useAgents, useIntegrations } from '../../lib/queries'
import { can } from '../../lib/session'
import { SKILL_INSTRUCTIONS_MAX, useCreateSkill, useUpdateSkill } from '../../lib/skillQueries'
import type { Skill, SkillInput } from '../../lib/skillQueries'
import { useToast } from '../../lib/toast'

const NAME_MAX = 120
const DESCRIPTION_MAX = 300

const FIELD_LABELS: Record<string, string> = {
  name: 'Name',
  description: 'Description',
  instructions: 'Instructions',
  defaultAgentId: 'Default agent',
  requiredConnectors: 'Required connectors',
}

/** Field problems keyed by the form's own field names. */
type FieldErrors = Partial<Record<keyof SkillInput, string>>

/** Splits a failed save into problems shown beside their fields and one message for the rest. */
function placeProblems(error: unknown): { fields: FieldErrors; message: string | null } {
  if (error instanceof ApiError) {
    if (error.status === 409) {
      return { fields: { name: 'Another skill in this workspace already has this name.' }, message: null }
    }
    const fields: FieldErrors = {}
    let unplaced = false
    for (const [field, problem] of Object.entries(error.fields)) {
      const key = field.replace(/\[\d+\]$/, '') as keyof SkillInput
      if (key in FIELD_LABELS) fields[key] = problem
      else unplaced = true
    }
    if (Object.keys(fields).length > 0) {
      return { fields, message: unplaced ? describeApiError(error, FIELD_LABELS) : null }
    }
  }
  return { fields: {}, message: describeApiError(error, FIELD_LABELS) }
}

/**
 * Write a new skill or change one. `skill` present means edit; otherwise `initial` (a draft built
 * elsewhere, such as from a chat thread) prefills a new one. Each opening starts from whichever
 * of those is given, so a cancelled edit never carries over to the next.
 */
// @find: skill editor dialog component, create or edit skill
export function SkillEditorDialog({
  open,
  onClose,
  skill,
  initial,
  onSaved,
}: {
  open: boolean
  onClose: () => void
  skill?: Skill | null | undefined
  initial?: Partial<SkillInput> | null | undefined
  onSaved?: ((skill: Skill) => void) | undefined
}) {
  const editing = skill ?? null
  const toast = useToast()
  const connectorsLabelId = useId()

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [instructions, setInstructions] = useState('')
  const [defaultAgentId, setDefaultAgentId] = useState('')
  const [connectors, setConnectors] = useState<string[]>([])
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [error, setError] = useState<string | null>(null)

  const create = useCreateSkill()
  const update = useUpdateSkill(editing?.id ?? 'unselected')
  const submitting = create.isPending || update.isPending

  const agentsQuery = useAgents({ enabled: open })
  const canReadConnectors = can('integration:read')
  const integrations = useIntegrations({ enabled: open && canReadConnectors })

  const [wasOpen, setWasOpen] = useState(false)
  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) {
      const source: Partial<SkillInput> = editing ?? initial ?? {}
      setName(source.name ?? '')
      setDescription(source.description ?? '')
      setInstructions(source.instructions ?? '')
      setDefaultAgentId(source.defaultAgentId ?? '')
      setConnectors(source.requiredConnectors ? [...source.requiredConnectors] : [])
      setFieldErrors({})
      setError(null)
    }
  }

  // Only an active agent can take work; one already chosen stays listed so it is not lost silently.
  const agents = (agentsQuery.data ?? []).filter((agent) => agent.status === 'active' || agent.id === defaultAgentId)
  const savedAgentMissing =
    defaultAgentId !== '' && !agentsQuery.isLoading && !agents.some((agent) => agent.id === defaultAgentId)

  // Every connector the catalogue knows, plus any the skill names that the catalogue does not.
  const known = (integrations.data ?? []).map((item) => ({ id: item.server, label: item.displayName || item.server }))
  const connectorOptions = [
    ...known,
    ...connectors.filter((id) => !known.some((item) => item.id === id)).map((id) => ({ id, label: id })),
  ]

  const toggleConnector = (id: string) =>
    setConnectors((current) => (current.includes(id) ? current.filter((value) => value !== id) : [...current, id]))

  const clearFieldError = (field: keyof SkillInput) =>
    setFieldErrors((current) => {
      if (!current[field]) return current
      const next = { ...current }
      delete next[field]
      return next
    })

  const tooLong = instructions.length > SKILL_INSTRUCTIONS_MAX
  const canSubmit = name.trim().length > 0 && instructions.trim().length > 0 && !tooLong && !submitting

  const close = () => {
    if (!submitting) onClose()
  }

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (!canSubmit) return
    setError(null)
    setFieldErrors({})
    const body: SkillInput = {
      name: name.trim(),
      description: description.trim(),
      instructions,
      defaultAgentId: defaultAgentId || null,
      requiredConnectors: connectors,
    }
    try {
      const saved = editing ? await update.mutateAsync(body) : await create.mutateAsync(body)
      toast.success(editing ? `${saved.name} was saved` : `${saved.name} was created`)
      onSaved?.(saved)
      onClose()
    } catch (err) {
      const placed = placeProblems(err)
      setFieldErrors(placed.fields)
      setError(placed.message)
    }
  }

  const formId = editing ? `skill-form-${editing.id}` : 'skill-form-new'

  return (
    <Dialog
      open={open}
      onClose={close}
      dismissible={!submitting}
      error={error}
      eyebrow={editing ? 'Edit skill' : 'New skill'}
      title={editing ? `Change “${editing.name}”` : 'Write a skill'}
      description="A skill is a reusable playbook. Apply it to any chat message from the + menu and the agent follows these instructions."
      footer={
        <>
          <Button variant="outline" type="button" onClick={close} disabled={submitting}>
            Cancel
          </Button>
          <Button type="submit" form={formId} loading={submitting} disabled={!canSubmit}>
            {editing ? 'Save skill' : 'Create skill'}
          </Button>
        </>
      }
    >
      <form id={formId} onSubmit={handleSubmit} noValidate>
        <div className="stack" style={{ gap: 'var(--space-4)' }}>
          <Input
            label="Name"
            value={name}
            onChange={(event) => {
              setName(event.target.value)
              clearFieldError('name')
            }}
            placeholder="Draft a customer reply"
            required
            maxLength={NAME_MAX}
            error={fieldErrors.name}
            data-autofocus
          />

          <Input
            label="Description"
            optional
            value={description}
            onChange={(event) => {
              setDescription(event.target.value)
              clearFieldError('description')
            }}
            placeholder="One line on when to use it"
            maxLength={DESCRIPTION_MAX}
            error={fieldErrors.description}
          />

          <Textarea
            label="Instructions"
            value={instructions}
            onChange={(event) => {
              setInstructions(event.target.value)
              clearFieldError('instructions')
            }}
            placeholder="Read the customer’s message, check the order history, and draft a short, friendly reply that answers every question."
            required
            rows={8}
            className="skill-instructions"
            error={
              fieldErrors.instructions ??
              (tooLong ? `Keep the instructions to ${formatCount(SKILL_INSTRUCTIONS_MAX)} characters or fewer.` : undefined)
            }
            hint={`Markdown is supported. ${formatCount(instructions.length)} of ${formatCount(SKILL_INSTRUCTIONS_MAX)} characters.`}
          />

          <Select
            label="Default agent"
            value={defaultAgentId}
            onChange={(event) => {
              setDefaultAgentId(event.target.value)
              clearFieldError('defaultAgentId')
            }}
            disabled={agentsQuery.isLoading}
            error={fieldErrors.defaultAgentId}
            hint="Who takes the message when this skill is applied. Route as usual lets the coordinator choose."
          >
            <option value="">Route as usual</option>
            {savedAgentMissing && (
              <option value={defaultAgentId}>{editing?.defaultAgentName ?? 'The saved agent'} (not available)</option>
            )}
            {agents.map((agent) => (
              <option key={agent.id} value={agent.id}>
                {agent.status === 'active' ? agent.name : `${agent.name} (not available)`}
              </option>
            ))}
          </Select>

          <div className="field" role="group" aria-labelledby={connectorsLabelId}>
            <span className="field-label" id={connectorsLabelId}>
              Required connectors<span className="field-optional"> (optional)</span>
            </span>
            {integrations.isLoading ? (
              <p className="caption muted">Loading connectors…</p>
            ) : connectorOptions.length === 0 ? (
              <p className="caption muted">
                {canReadConnectors ? 'No connectors are available yet.' : 'Your role cannot list connectors.'}
              </p>
            ) : (
              <div className="filter-chips skill-connector-chips">
                {connectorOptions.map((option) => (
                  <button
                    key={option.id}
                    type="button"
                    className="filter-chip"
                    aria-pressed={connectors.includes(option.id)}
                    onClick={() => {
                      toggleConnector(option.id)
                      clearFieldError('requiredConnectors')
                    }}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
            )}
            {fieldErrors.requiredConnectors ? (
              <p className="field-error">{fieldErrors.requiredConnectors}</p>
            ) : (
              <p className="caption field-hint">The services this skill relies on. People are told if one is not connected.</p>
            )}
          </div>

          {editing?.builtIn && (
            <Notice tone="info">This is a ready-made skill. Your changes apply to everyone in the workspace.</Notice>
          )}
        </div>
      </form>
    </Dialog>
  )
}
