// @find: schedule this, turn message into schedule, schedule from chat, plus menu schedule, next runs preview, POST /api/schedules from composer
// @what: The small dialog the composer's "Schedule this" opens: the message becomes a schedule, with a plain-English time read back as its next runs.
// @flow: Opened by Composer from PlusMenu; uses useSchedulePreview and useCreateSchedule (the existing schedules API) and useAgents.
import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Dialog, Input, Notice, Select, Textarea } from '../ui'
import { describeApiError } from '../../lib/api'
import { formatDateTimeIn } from '../../lib/format'
import { useAgents, useCreateSchedule, useSchedulePreview } from '../../lib/queries'
import { useToast } from '../../lib/toast'

const DEBOUNCE_MS = 350
const EXAMPLES = ['every weekday at 9am', 'every Monday at 8am', 'tomorrow at 3pm', 'on the 1st of every month at 9am']
const FIELD_LABELS: Record<string, string> = { name: 'Name', agentId: 'Agent', instruction: 'Instruction', text: 'When' }

/** A name from the message: its first line, cut at a word, at most 60 characters. */
export function scheduleNameFrom(text: string): string {
  const first = text.trim().split('\n')[0] ?? ''
  if (first.length <= 60) return first
  const cut = first.slice(0, 60)
  const space = cut.lastIndexOf(' ')
  return `${(space > 30 ? cut.slice(0, space) : cut).trim()}…`
}

// @find: ScheduleThisDialog, schedule this message
/**
 * Dialog opened from the "+" menu that turns the message being written into a schedule.
 * Flow: each opening seeds the name (scheduleNameFrom), the instruction (the message text) and clears the
 * "when" field. The agent defaults to preferredAgentId when it can take schedules, otherwise the General
 * Employee, otherwise the first active agent with a revision (useAgents). The "when" text is plain English;
 * after a 350 ms pause (and at least 3 characters) it is sent to useSchedulePreview, and a successful
 * reading is echoed back with the timezone and the next three runs. Example chips fill the field.
 * Create schedule is enabled only when name, instruction, agent and a current successful preview exist;
 * it calls useCreateSchedule, then shows a toast, calls onCreated (Composer clears its draft) and closes.
 * Server problems are shown at the field or in the dialog. The dialog cannot be dismissed while saving.
 */
export function ScheduleThisDialog({
  open,
  onClose,
  text,
  preferredAgentId,
  onCreated,
}: {
  open: boolean
  onClose: () => void
  /** The message being written, which becomes the schedule's instruction. */
  text: string
  /** The agent mentioned (or the skill's default agent), chosen first when it can take schedules. */
  preferredAgentId: string | null
  onCreated?: () => void
}) {
  const agentsQuery = useAgents()
  const schedulable = (agentsQuery.data ?? []).filter((agent) => agent.status === 'active' && agent.revision != null)
  const preview = useSchedulePreview()
  const create = useCreateSchedule()
  const toast = useToast()

  const [name, setName] = useState('')
  const [instruction, setInstruction] = useState('')
  const [agentId, setAgentId] = useState('')
  const [when, setWhen] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [previewedFor, setPreviewedFor] = useState('')

  // Each opening starts from the message as it is now.
  const [wasOpen, setWasOpen] = useState(open)
  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) {
      setName(scheduleNameFrom(text))
      setInstruction(text.trim())
      setWhen('')
      setError(null)
      setPreviewedFor('')
      setAgentId('')
      preview.reset()
    }
  }

  // The agent defaults once the list is in: the preferred one when it can take schedules, else
  // the workspace's General Employee, else the first.
  const defaultAgentId =
    schedulable.find((agent) => agent.id === preferredAgentId)?.id ??
    schedulable.find((agent) => agent.fallback)?.id ??
    schedulable[0]?.id ??
    ''
  const chosenAgentId = agentId || defaultAgentId

  const previewRef = useRef(preview)
  useEffect(() => {
    previewRef.current = preview
  })
  const trimmedWhen = when.trim()
  useEffect(() => {
    if (!open || trimmedWhen.length < 3) return
    const timer = window.setTimeout(() => {
      setPreviewedFor(trimmedWhen)
      previewRef.current.mutate({ text: trimmedWhen })
    }, DEBOUNCE_MS)
    return () => window.clearTimeout(timer)
  }, [trimmedWhen, open])

  const current = previewedFor === trimmedWhen && trimmedWhen.length > 0
  const previewOk = current && preview.isSuccess
  const previewProblem = current && preview.isError ? describeApiError(preview.error, FIELD_LABELS) : null
  const canSubmit =
    name.trim().length > 0 && instruction.trim().length > 0 && chosenAgentId.length > 0 && previewOk && !create.isPending

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!canSubmit) return
    setError(null)
    try {
      await create.mutateAsync({ name: name.trim(), agentId: chosenAgentId, instruction: instruction.trim(), text: trimmedWhen })
      toast.success('Schedule created')
      onCreated?.()
      onClose()
    } catch (err) {
      setError(describeApiError(err, FIELD_LABELS))
    }
  }

  return (
    <Dialog
      open={open}
      onClose={() => !create.isPending && onClose()}
      dismissible={!create.isPending}
      error={error}
      eyebrow="Schedule this"
      title="Run this message on a timetable"
      description="Say when in plain words. The next runs are read back before anything is saved."
      footer={
        <>
          <Button variant="outline" type="button" onClick={onClose} disabled={create.isPending}>
            Cancel
          </Button>
          <Button type="submit" form="schedule-this-form" loading={create.isPending} disabled={!canSubmit}>
            Create schedule
          </Button>
        </>
      }
    >
      <form id="schedule-this-form" onSubmit={submit}>
        <div className="stack" style={{ gap: 'var(--space-4)' }}>
          <div className="stack" style={{ gap: 'var(--space-2)' }}>
            <Input
              label="When"
              value={when}
              onChange={(event) => setWhen(event.target.value)}
              placeholder="every weekday at 9am"
              maxLength={200}
              data-autofocus
              hint="Plain English. Times follow the workspace timezone."
              error={previewProblem}
            />
            <div className="row" style={{ flexWrap: 'wrap', gap: 'var(--space-2)' }}>
              {EXAMPLES.map((example) => (
                <button key={example} type="button" className="filter-chip" aria-pressed={when === example} onClick={() => setWhen(example)}>
                  {example}
                </button>
              ))}
            </div>
            {current && preview.isPending && <Notice tone="info">Reading that time back…</Notice>}
            {previewOk && preview.data && (
              <Notice tone="success">
                {preview.data.description} ({preview.data.timezone}).
                <br />
                Next runs: {preview.data.nextRuns.slice(0, 3).map((run) => formatDateTimeIn(run, preview.data.timezone)).join(' · ')}
              </Notice>
            )}
          </div>
          <Select
            label="Agent"
            value={chosenAgentId}
            onChange={(event) => setAgentId(event.target.value)}
            disabled={agentsQuery.isLoading}
          >
            {agentsQuery.isLoading && <option value="">Loading agents…</option>}
            {schedulable.map((agent) => (
              <option key={agent.id} value={agent.id}>
                {agent.name}
              </option>
            ))}
          </Select>
          <Input label="Name" value={name} onChange={(event) => setName(event.target.value)} maxLength={120} required />
          <Textarea
            label="Instruction"
            value={instruction}
            onChange={(event) => setInstruction(event.target.value)}
            rows={3}
            maxLength={10_000}
            required
            hint="What the agent does each time it runs."
          />
        </div>
      </form>
    </Dialog>
  )
}
