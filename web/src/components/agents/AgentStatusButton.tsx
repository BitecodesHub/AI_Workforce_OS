// @find: pause agent, resume agent, agent status, activate, deactivate, agent card button, agent list, agent page, agent:update, Pause button
// @what: Button to pause or resume one agent, from its page and its card in the agent list.
// @flow: Used by the Agents page and agent detail page.
import { useState } from 'react'
import { Button, ConfirmDialog } from '../ui'
import { describeApiError } from '../../lib/api'
import { useSetAgentStatus } from '../../lib/queries'
import type { Agent } from '../../lib/queries'
import { can } from '../../lib/session'
import { useToast } from '../../lib/toast'

/*
 * Pause or resume one agent, from its own page and from its card in the agent list (the control the
 * chat points people to when it says an agent is paused).
 *
 * It shows only for somebody who may change agents (agent:update), and never for a retired agent,
 * which cannot be paused or resumed. Pausing the General Employee asks first: it is the agent that
 * answers when no other fits, and while it is held those requests ask people to choose one.
 */

// @find: AgentStatusButton, agent status button, pause agent, resume agent, agent status, activate
export function AgentStatusButton({
  agent,
  variant = 'outline',
  className,
  busy = false,
}: {
  agent: Pick<Agent, 'id' | 'name' | 'status' | 'fallback'>
  variant?: 'outline' | 'quiet'
  className?: string
  /** True while something else is changing this agent, such as a bulk action, so it cannot be pressed twice. */
  busy?: boolean
}) {
  const toast = useToast()
  const setStatus = useSetAgentStatus()
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (!can('agent:update') || agent.status === 'retired') return null

  const paused = agent.status === 'paused'
  const verb = paused ? 'Resume' : 'Pause'

  const apply = async (): Promise<boolean> => {
    setError(null)
    try {
      await setStatus.mutateAsync({ id: agent.id, action: paused ? 'resume' : 'pause' })
      toast.success(`${agent.name} ${paused ? 'resumed' : 'paused'}`)
      return true
    } catch (thrown) {
      const message = describeApiError(thrown)
      // A confirmation shows its own failure; a plain button has no place to, so it says so here.
      if (confirming) setError(message)
      else toast.error(message)
      return false
    }
  }

  const press = () => {
    if (!paused && agent.fallback) {
      setError(null)
      setConfirming(true)
      return
    }
    void apply()
  }

  return (
    <>
      <Button
        variant={variant}
        className={className}
        aria-label={`${verb} ${agent.name}`}
        loading={(setStatus.isPending && !confirming) || busy}
        onClick={press}
      >
        {verb}
      </Button>
      {agent.fallback && (
        <ConfirmDialog
          open={confirming}
          onClose={() => setConfirming(false)}
          onConfirm={async () => {
            if (await apply()) setConfirming(false)
          }}
          eyebrow="Pause an agent"
          title={`Pause ${agent.name}?`}
          description={`${agent.name} answers when no other agent fits a request. While it is paused, a request nobody else can take will ask people to choose an agent instead. Work already running is not stopped, and it can be resumed at any time.`}
          confirmLabel={`Pause ${agent.name}`}
          tone="danger"
          loading={setStatus.isPending}
          error={error}
        />
      )}
    </>
  )
}
