// @find: retire agent, restore agent, archive agent, delete agent, bring back agent, fallback agent, Employee cannot be retired, agent page, agent:delete, Retire card
// @what: Card at the foot of an agent's page to retire (archive) an agent or bring a retired one back.
// @flow: Used by the agent detail page.
import { useId, useState } from 'react'
import { Button, Card, ConfirmDialog, Eyebrow } from '../ui'
import { describeApiError } from '../../lib/api'
import { useRetireAgent } from '../../lib/queries'
import type { Agent } from '../../lib/queries'
import { can } from '../../lib/session'
import { useToast } from '../../lib/toast'

/*
 * Retire an agent, or bring a retired one back, from the foot of its page.
 *
 * Retiring archives rather than deletes: chat and routing stop sending it work and its schedules
 * are paused, while its runs, traces, memory and settings stay. It asks first. The General
 * Employee cannot be retired (it answers what no other agent takes), and a role without
 * agent:delete sees the control disabled with the reason beside it. Shown only to somebody who may
 * change agents; everyone else has nothing to do here.
 */

// @find: retireBlockedReason, retire blocked reason, retire agent, restore agent, archive agent, delete agent
/** Why the button is disabled for this person and agent, or null when it is not. */
export function retireBlockedReason(agent: Pick<Agent, 'fallback' | 'status'>, canDelete: boolean): string | null {
  if (agent.fallback && agent.status !== 'retired') {
    return 'The General Employee cannot be retired: it answers anything no other agent takes. Pause it instead.'
  }
  if (!canDelete) return 'Your role cannot retire or restore agents. An owner or admin can.'
  return null
}

// @find: RetireAgentCard, retire agent card, retire agent, restore agent, archive agent, delete agent
export function RetireAgentCard({ agent }: { agent: Pick<Agent, 'id' | 'name' | 'status' | 'fallback'> }) {
  const toast = useToast()
  const retire = useRetireAgent()
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const reasonId = useId()

  if (!can('agent:update') && !can('agent:delete')) return null

  const retired = agent.status === 'retired'
  const blocked = retireBlockedReason(agent, can('agent:delete'))

  const restore = async () => {
    try {
      await retire.mutateAsync({ id: agent.id, action: 'restore' })
      toast.success(`${agent.name} is back, paused. Resume it when it should take work again.`)
    } catch (thrown) {
      toast.error(describeApiError(thrown))
    }
  }

  const confirmRetire = async () => {
    setError(null)
    try {
      await retire.mutateAsync({ id: agent.id, action: 'retire' })
      setConfirming(false)
      toast.success(`${agent.name} was retired. Its history is kept.`)
    } catch (thrown) {
      setError(describeApiError(thrown))
    }
  }

  return (
    <Card as="section">
      <Eyebrow as="h2">{retired ? 'Retired' : 'Retire this agent'}</Eyebrow>
      <p className="muted" style={{ marginBottom: 'var(--space-4)', maxWidth: '62ch' }}>
        {retired
          ? `${agent.name} is retired: chat, routing and schedules do not send it work. Its runs, memory and settings are kept. Restoring brings it back paused.`
          : `Retiring stops ${agent.name} taking work: chat and routing no longer send it anything and its schedules are paused. Its runs, traces, memory and settings are kept, and it can be restored later.`}
      </p>
      <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap', alignItems: 'center' }}>
        <Button
          variant="outline"
          disabled={blocked !== null}
          loading={retire.isPending && !confirming}
          aria-describedby={blocked ? reasonId : undefined}
          onClick={() => {
            if (retired) {
              void restore()
              return
            }
            setError(null)
            setConfirming(true)
          }}
        >
          {retired ? `Restore ${agent.name}` : 'Retire agent'}
        </Button>
        {blocked && (
          <span id={reasonId} className="caption muted">
            {blocked}
          </span>
        )}
      </div>
      <ConfirmDialog
        open={confirming}
        onClose={() => setConfirming(false)}
        onConfirm={confirmRetire}
        eyebrow="Retire an agent"
        title={`Retire ${agent.name}?`}
        description={`${agent.name} stops taking new work and its schedules are paused. Work already running finishes. Nothing is deleted, and it can be restored from this page.`}
        confirmLabel={`Retire ${agent.name}`}
        tone="danger"
        loading={retire.isPending}
        error={error}
      />
    </Card>
  )
}
