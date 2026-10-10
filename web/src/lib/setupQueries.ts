// @find: set up your workspace, setup steps, setup checklist, setup banner, canSetUp, useSetupSteps, what is left to do, Setup page, Command Map banner
// @what: Loads the facts behind "Set up your workspace" using the same queries as the pages that change them.
// @flow: Used by routes/Setup.tsx and components/setup/SetupBanner.tsx; hands facts to setup.ts.
import { useBrowserNotifications } from './attention'
import { connectorState } from './connectors'
import { useEmbeddingStatus } from './embeddingQueries'
import { useBudget } from './insightsQueries'
import { useCredentials, useIntegrations, useInvitations, useMembers, useModelPolicy, useProviders, useSources } from './queries'
import { liveRouting, providerReadiness } from './routing'
import { can, profile } from './session'
import { useNotificationSettings } from './settingsQueries'
import { setupSteps, summarise, type SetupFacts, type SetupStep, type SetupSummary } from './setup'

/*
 * The facts "Set up your workspace" is worked out from, read with the same queries the pages that
 * change them use, so a change made on the setup page or on its own page shows in both at once.
 * Each is asked only of a role that can act on it.
 */

const isSandbox = (provider: { kind: string }) => provider.kind.toUpperCase() === 'SANDBOX'

// @find: setup steps hook, workspace setup progress, checklist status; reads providers, sources, members, budget, notifications; used by: Setup page, setup banner
export function useSetupSteps(options: { enabled?: boolean } = {}): { steps: SetupStep[]; summary: SetupSummary } {
  const on = options.enabled ?? true
  const me = profile()
  const orgId = me?.workspaceId ?? ''

  const canModels = on && can('provider:manage') && can('provider:read')
  const canSearch = on && can('knowledge:source_manage')
  const canTools = on && can('integration:connect') && can('integration:read')
  const canKnowledge = on && can('knowledge:source_manage') && can('knowledge:read')
  const canTeam = on && can('member:invite') && can('member:read')
  const canNotify = on && can('workspace:update')
  const canBudget = on && can('budget:manage') && can('budget:read')

  const policy = useModelPolicy({ enabled: canModels })
  const providers = useProviders({ enabled: canModels })
  const credentials = useCredentials({ enabled: canModels })
  const embedding = useEmbeddingStatus({ enabled: canSearch })
  const integrations = useIntegrations({ enabled: canTools })
  const sources = useSources({ enabled: canKnowledge })
  const members = useMembers({ enabled: canTeam })
  const invitations = useInvitations(orgId, { enabled: canTeam && Boolean(orgId) })
  const notifications = useNotificationSettings({ enabled: canNotify })
  const browser = useBrowserNotifications(me?.userId ?? '')
  const budget = useBudget({ enabled: canBudget })

  const routingKnown = policy.data && providers.data && credentials.data
  const routing = routingKnown ? liveRouting(policy.data, providers.data!, credentials.data!) : null
  const readyCandidates =
    routingKnown
      ? policy.data!.candidates.filter((candidate) => {
          const provider = providers.data!.find((row) => row.id === candidate.providerId)
          return provider && !isSandbox(provider) && providerReadiness(provider, credentials.data!).ready
        }).length
      : undefined

  const facts: SetupFacts = {
    model: { allowed: canModels, data: routing ? { live: routing.live, providerName: routing.first?.providerName ?? null } : undefined },
    order: { allowed: canModels, data: readyCandidates === undefined ? undefined : { readyCandidates } },
    search: {
      allowed: canSearch,
      data: embedding.data ? { meaning: embedding.data.searchMode === 'keyword+meaning', model: embedding.data.model } : undefined,
    },
    tools: {
      allowed: canTools,
      data: integrations.data
        ? {
            live: integrations.data.filter((item) => connectorState(item) === 'connected').length,
            available: integrations.data.filter((item) => item.liveAvailable).length,
          }
        : undefined,
    },
    knowledge: {
      allowed: canKnowledge,
      data: sources.data
        ? { documents: sources.data.filter((source) => !source.agentId).reduce((sum, source) => sum + source.documentCount, 0) }
        : undefined,
    },
    team: {
      allowed: canTeam,
      data:
        members.data && (invitations.data || !orgId)
          ? {
              members: members.data.filter((member) => member.status === 'active').length,
              invited: (invitations.data ?? []).filter((invitation) => invitation.status.toLowerCase() === 'pending').length,
            }
          : undefined,
    },
    notifications: {
      allowed: canNotify,
      data: notifications.data ? { webhook: notifications.data.webhookUrl !== '', browser } : undefined,
    },
    budget: { allowed: canBudget, data: budget.data === undefined ? undefined : { monthlyCap: budget.data?.monthlyCap ?? null } },
  }

  const steps = setupSteps(facts)
  return { steps, summary: summarise(steps) }
}

// @find: can set up workspace, owner or admin only, shows setup banner
/** Whether this person is the one setup is for: an owner or admin, who can change the workspace. */
export function canSetUp(): boolean {
  return can('workspace:update') && can('provider:manage')
}
