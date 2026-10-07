import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Dialog, Input, Notice, Select, Textarea } from '../ui'
import { describeApiError } from '../../lib/api'
import { formatDateTimeIn, sentenceCase } from '../../lib/format'
import { CATEGORY_LABEL } from '../../lib/labels'
import { useAgents, useCreateSchedule, useSchedulePreview, useUpdateSchedule } from '../../lib/queries'
import type { Schedule } from '../../lib/queries'
import { editTakesOwnership, isScheduleDone } from '../../lib/schedules'
import { profile } from '../../lib/session'
import { useToast } from '../../lib/toast'
import { SCHEDULE_EXAMPLES, scheduleDebounceKey } from './scheduleModel'

const NAME_MAX = 120
const INSTRUCTION_MAX = 10_000
const WHEN_MAX = 200
const DEBOUNCE_MS = 300

const FIELD_LABELS: Record<string, string> = { name: 'Name', agentId: 'Agent', instruction: 'Instruction', text: 'When' }

/** An active agent this workspace can hand a schedule to. Matches TaskDialog's own rule: a run
 * needs a saved configuration, and the platform refuses to start one without it. */
const canSchedule = (agent: { status: string; revision: number | null }) =>
  agent.status === 'active' && agent.revision != null

function agentOptionLabel(agent: { name: string; category: string; status: string; revision: number | null }): string {
  const category = CATEGORY_LABEL[agent.category] ?? sentenceCase(agent.category)
  const base = category ? `${agent.name} — ${category}` : agent.name
  return canSchedule(agent) ? base : `${base} (not available)`
}

/**
 * Create or edit a schedule. `schedule` present means edit: its name, agent and instruction are
 * changed freely, but its timetable stays as it is unless a new "When" phrase is typed - editing a
 * schedule must not force a person to retype a timetable they are keeping.
 *
 * A schedule runs as its owner. Changing what somebody else's schedule does makes the editor its
 * owner (the server decides; this only says so before saving), and a one-off that already ran
 * comes back on when it is given a new time still ahead.
 */
export function ScheduleDialog({ open, onClose, schedule }: { open: boolean; onClose: () => void; schedule?: Schedule | null }) {
  const editing = schedule ?? null

  const [name, setName] = useState('')
  const [agentId, setAgentId] = useState('')
  const [instruction, setInstruction] = useState('')
  const [whenText, setWhenText] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [previewedFor, setPreviewedFor] = useState<string | null>(null)

  const toast = useToast()
  const agentsQuery = useAgents()
  const agents = agentsQuery.data ?? []
  const preview = useSchedulePreview()
  const create = useCreateSchedule()
  const update = useUpdateSchedule(editing?.id ?? 'unselected')

  // Each opening starts from the record as it stands (or blank, for a new one); a cancelled edit
  // never bleeds into the next time this dialog opens.
  const [wasOpen, setWasOpen] = useState(open)
  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) {
      setName(editing?.name ?? '')
      setAgentId(editing?.agentId ?? '')
      setInstruction(editing?.instruction ?? '')
      setWhenText('')
      setError(null)
      setSubmitting(false)
      setPreviewedFor(null)
      preview.reset()
    }
  }

  const whenTrimmed = whenText.trim()
  const debounceKey = scheduleDebounceKey(whenText)
  const previewRef = useRef(preview)
  useEffect(() => {
    previewRef.current = preview
  })
  const whenTextRef = useRef(whenText)
  useEffect(() => {
    whenTextRef.current = whenText
  })

  useEffect(() => {
    if (!debounceKey) {
      previewRef.current.reset()
      return
    }
    const timer = setTimeout(() => {
      setPreviewedFor(debounceKey)
      previewRef.current.mutate({ text: whenTextRef.current.trim() })
    }, DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [debounceKey])

  const previewCurrent = previewedFor === debounceKey && debounceKey.length > 0
  const previewPending = previewCurrent && preview.isPending
  const previewValid = previewCurrent && preview.isSuccess
  const previewProblem = previewCurrent && preview.isError ? describeApiError(preview.error, FIELD_LABELS) : null

  // On create the timetable is required; on edit it is only required once somebody starts typing
  // a new one, since leaving it blank means "keep the one it already has".
  const needsPreview = !editing || whenTrimmed.length > 0
  const canSubmit =
    name.trim().length > 0 &&
    agentId.length > 0 &&
    instruction.trim().length > 0 &&
    (!needsPreview || previewValid) &&
    !submitting

  const close = () => {
    if (!submitting) onClose()
  }

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (!canSubmit) return
    setError(null)
    setSubmitting(true)
    try {
      if (editing) {
        await update.mutateAsync({
          name,
          agentId,
          instruction,
          ...(whenTrimmed ? { text: whenTrimmed } : {}),
        })
        toast.success('Schedule saved')
      } else {
        await create.mutateAsync({ name, agentId, instruction, text: whenTrimmed })
        toast.success('Schedule created')
      }
      setSubmitting(false)
      onClose()
    } catch (err) {
      setSubmitting(false)
      setError(describeApiError(err, FIELD_LABELS))
    }
  }

  const selectedAgent = agents.find((agent) => agent.id === agentId)
  const editingDone = editing ? isScheduleDone(editing) : false
  const takesOwnership = editing
    ? editTakesOwnership(editing, profile()?.userId ?? null, {
        agentId,
        instruction,
        reactivates: editingDone && whenTrimmed.length > 0 && previewValid,
      })
    : false

  return (
    <Dialog
      open={open}
      onClose={close}
      dismissible={!submitting}
      error={error}
      eyebrow={editing ? 'Edit schedule' : 'New schedule'}
      title={editing ? `Change “${editing.name}”` : 'Set up a schedule'}
      description="Agents can start work on their own. Describe when in plain English and it is echoed back before it is saved."
      footer={
        <>
          <Button variant="outline" type="button" onClick={close} disabled={submitting}>
            Cancel
          </Button>
          <Button type="submit" form="schedule-form" loading={submitting} disabled={!canSubmit}>
            {editing ? 'Save schedule' : 'Create schedule'}
          </Button>
        </>
      }
    >
      <form id="schedule-form" onSubmit={handleSubmit}>
        <div className="stack" style={{ gap: 'var(--space-4)' }}>
          <Input
            label="Name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="Weekday ticket summary"
            required
            maxLength={NAME_MAX}
            data-autofocus
          />

          <Select
            label="Agent"
            value={agentId}
            onChange={(event) => setAgentId(event.target.value)}
            required
            disabled={agentsQuery.isLoading}
            hint={
              selectedAgent?.summary ? (
                <>
                  From its instructions: <q>{selectedAgent.summary}</q>
                </>
              ) : undefined
            }
          >
            {agentsQuery.isLoading && <option value="">Loading agents…</option>}
            {!agentsQuery.isLoading && !agentId && (
              <option value="" disabled>
                Choose an agent
              </option>
            )}
            {agents.map((agent) => (
              <option key={agent.id} value={agent.id} disabled={!canSchedule(agent)}>
                {agentOptionLabel(agent)}
              </option>
            ))}
          </Select>

          <Textarea
            label="Instruction"
            value={instruction}
            onChange={(event) => setInstruction(event.target.value)}
            placeholder="Summarise new support tickets from the last day and flag anything urgent."
            required
            maxLength={INSTRUCTION_MAX}
            rows={4}
            hint="What the agent does each time this schedule fires."
          />

          <div className="stack" style={{ gap: 'var(--space-2)' }}>
            <Input
              label="When"
              optional={Boolean(editing)}
              value={whenText}
              onChange={(event) => setWhenText(event.target.value)}
              placeholder="every weekday at 9am"
              maxLength={WHEN_MAX}
              hint={
                editingDone && !whenTrimmed
                  ? 'This one-off has already run. Give it a new time to run it again.'
                  : editing && !whenTrimmed
                    ? `Leave this blank to keep its current timetable: ${editing.description}`
                    : 'Plain English. Times follow the workspace timezone.'
              }
              error={previewProblem}
            />

            <div className="row" style={{ flexWrap: 'wrap', gap: 'var(--space-2)' }}>
              {SCHEDULE_EXAMPLES.map((example) => (
                <button
                  key={example}
                  type="button"
                  className="filter-chip"
                  aria-pressed={whenText === example}
                  onClick={() => setWhenText(example)}
                >
                  {example}
                </button>
              ))}
            </div>

            {previewPending && <Notice tone="info">Reading that phrase back…</Notice>}
            {previewValid && preview.data && (
              <Notice tone="success">
                {preview.data.description} ({preview.data.timezone}).
                <br />
                Next, in that timezone: {preview.data.nextRuns.map((run) => formatDateTimeIn(run, preview.data.timezone)).join(' · ')}
              </Notice>
            )}
          </div>

          {takesOwnership && (
            <Notice tone="info">
              Changing what this schedule does makes you its owner. Every later run starts in your name.
            </Notice>
          )}
        </div>
      </form>
    </Dialog>
  )
}
