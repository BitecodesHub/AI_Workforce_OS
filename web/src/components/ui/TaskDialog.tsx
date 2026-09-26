import { useEffect, useState } from 'react'
import { Button, Dialog, Notice, Select, Textarea } from './index'
import { CATEGORY_LABEL } from './index'
import { useAgents, useCreateRun, useCreateGoal } from '../../lib/queries'
import { useToast } from '../../lib/toast'
import { useRouter } from '../../lib/router'

/*
 * Give an agent a task.
 *
 * The same dialog opens from four places: the Command Map, the Agents list, an agent's own page,
 * and Tasks. From an agent's page the agent is already decided, so the dialog only asks what to
 * do. From everywhere else, nothing has decided which agent should do it - the dialog has to ask,
 * and it used to not: it silently sent an empty agent id to the server, which failed with a
 * validation error that named a field the person never saw. Every path through this dialog now
 * ends with a real agent id or does not let the person submit.
 */

interface TaskDialogProps {
  open: boolean
  onClose: () => void
  agentId?: string
  onSuccess?: (runId?: string) => void
}

export function TaskDialog({ open, onClose, agentId, onSuccess }: TaskDialogProps) {
  const { navigate } = useRouter()
  const toast = useToast()
  const [instruction, setInstruction] = useState('')
  const [selectedAgentId, setSelectedAgentId] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)

  const needsAgentPicker = !agentId
  const agentsQuery = useAgents()
  const agents = agentsQuery.data ?? []

  // Picking up a default the moment the list arrives means a person with only one agent can go
  // straight to describing the task, while still seeing - and being able to change - which agent
  // it is.
  useEffect(() => {
    if (needsAgentPicker && !selectedAgentId && agents.length > 0) {
      setSelectedAgentId(agents[0]!.id)
    }
  }, [needsAgentPicker, agents, selectedAgentId])

  useEffect(() => {
    if (!open) {
      setInstruction('')
      setSelectedAgentId('')
    }
  }, [open])

  const effectiveAgentId = agentId ?? selectedAgentId
  const createRunMutation = useCreateRun(effectiveAgentId || 'unselected')
  const createGoalMutation = useCreateGoal()

  const noAgentsExist = needsAgentPicker && !agentsQuery.isLoading && agents.length === 0
  const canSubmit = instruction.trim().length > 0 && effectiveAgentId.length > 0 && !noAgentsExist

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!canSubmit || isSubmitting) return

    setIsSubmitting(true)
    try {
      if (agentId) {
        const result = await createRunMutation.mutateAsync({ instruction })
        toast.success('Run started')
        if (onSuccess) {
          onSuccess(result.id)
        } else {
          navigate(`/runs/${result.id}`)
        }
      } else {
        const title = instruction.slice(0, 80)
        await createGoalMutation.mutateAsync({ title, agentId: effectiveAgentId, instruction })
        toast.success('Goal created')
        if (onSuccess) {
          onSuccess()
        } else {
          navigate('/tasks')
        }
      }
      onClose()
    } catch (error) {
      const message = error instanceof Error ? error.message : 'The task could not be created.'
      toast.error(message)
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      eyebrow={agentId ? 'Give agent a task' : 'New goal'}
      title={agentId ? 'What should this agent do?' : 'Describe the goal'}
      description={
        agentId
          ? 'The agent will start a run with this instruction.'
          : 'Choose which agent should do the work, then describe it.'
      }
    >
      <form onSubmit={handleSubmit}>
        {needsAgentPicker && noAgentsExist && (
          <div style={{ marginBottom: 'var(--space-5)' }}>
            <Notice tone="warning">
              This workspace has no agents yet. <a href="/agents">Create one</a> before giving out
              work.
            </Notice>
          </div>
        )}

        {needsAgentPicker && !noAgentsExist && (
          <Select
            label="Agent"
            value={selectedAgentId}
            onChange={(e) => setSelectedAgentId(e.target.value)}
            disabled={agentsQuery.isLoading}
            style={{ marginBottom: 'var(--space-4)' }}
          >
            {agentsQuery.isLoading && <option value="">Loading agents…</option>}
            {!agentsQuery.isLoading && (
              <option value="" disabled>
                Choose an agent
              </option>
            )}
            {agents.map((agent) => (
              <option key={agent.id} value={agent.id}>
                {agent.name} — {CATEGORY_LABEL[agent.category] ?? agent.category}
              </option>
            ))}
          </Select>
        )}

        <Textarea
          label="Instruction"
          value={instruction}
          onChange={(e) => setInstruction(e.target.value)}
          placeholder={agentId ? 'Describe the task for this agent…' : 'Describe the goal…'}
          required
          maxLength={10000}
          rows={6}
          hint="Maximum 10,000 characters"
          style={{ marginBottom: 'var(--space-6)' }}
        />
        <div className="dialog-footer" style={{ justifyContent: 'flex-end', gap: 'var(--space-3)' }}>
          <Button variant="outline" type="button" onClick={onClose} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button type="submit" loading={isSubmitting} disabled={!canSubmit}>
            {agentId ? 'Start run' : 'Create goal'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
