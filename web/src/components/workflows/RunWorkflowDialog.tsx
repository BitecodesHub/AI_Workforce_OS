// @find: run workflow dialog, start workflow, values for this run, placeholders, run inputs, approvals still apply, RunWorkflowDialog
// @what: The dialog that starts a workflow: one field per {{placeholder}} the steps use, and a plain summary of what will happen.
// @flow: Opened by routes/WorkflowEditor.tsx; its onRun posts through useRunWorkflow
import { useState } from 'react'
import { Dialog, Button, Input } from '../ui'
import { plural } from '../../lib/format'

// @find: run workflow dialog component
/**
 * Asks for a value for each placeholder (all required) and starts the run. Says how many steps
 * will run and that anything that sends, posts or deletes still waits for a person's approval.
 * Props: open, the placeholder names, the step count, onRun (resolves when started), onClose,
 * running and error from the caller's mutation.
 */
export function RunWorkflowDialog({
  open,
  name,
  placeholders,
  steps,
  running,
  error,
  onRun,
  onClose,
}: {
  open: boolean
  name: string
  placeholders: readonly string[]
  steps: number
  running: boolean
  error: string | null
  onRun: (inputs: Record<string, string>) => void
  onClose: () => void
}) {
  const [values, setValues] = useState<Record<string, string>>({})
  const [touched, setTouched] = useState(false)
  const missing = placeholders.filter((key) => !values[key]?.trim())

  // Enter in a field submits the form even while "Run now" is disabled; a run already starting
  // must not start a second goal.
  const submit = () => {
    if (running) return
    setTouched(true)
    if (missing.length > 0) return
    const inputs: Record<string, string> = {}
    for (const key of placeholders) inputs[key] = values[key]!.trim()
    onRun(inputs)
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      eyebrow="Run a workflow"
      title={`Run ${name}`}
      description={`${plural(steps, 'step', 'steps')} will run as one goal. Anything that sends, posts or deletes still waits for a person to approve it.`}
      dismissible={!running}
      error={error}
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={running}>
            Not now
          </Button>
          <Button onClick={submit} loading={running} disabled={running} data-autofocus={placeholders.length === 0 || undefined}>
            Run now
          </Button>
        </>
      }
    >
      {placeholders.length > 0 && (
        <form
          className="stack"
          style={{ gap: 'var(--space-4)' }}
          onSubmit={(event) => {
            event.preventDefault()
            submit()
          }}
        >
          <p className="caption muted">The steps use these values. Each one is filled in before the work starts.</p>
          {placeholders.map((key) => (
            <Input
              key={key}
              label={key}
              value={values[key] ?? ''}
              maxLength={2000}
              required
              error={touched && !values[key]?.trim() ? `Fill in ${key}.` : null}
              onChange={(event) => setValues((current) => ({ ...current, [key]: event.target.value }))}
            />
          ))}
          <button type="submit" hidden aria-hidden="true" tabIndex={-1} disabled={running} />
        </form>
      )}
    </Dialog>
  )
}
