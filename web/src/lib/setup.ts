// @find: set up your workspace, setup steps, setup status, done attention optional, checking, not allowed, setupSteps, summarise, SETUP_ORDER, Setup page, Command Map banner, Settings link
// @what: Works out each setup step status (model, order, search, tools, knowledge, team, notifications, budget) from loaded facts.
// @flow: Called by setupQueries.useSetupSteps; shown by routes/Setup.tsx and the setup banner.
/*
 * "Set up your workspace": what each setup step's status is, worked out from what the services
 * say, never from what this browser remembers. The page (routes/Setup.tsx) loads the data and
 * calls these; the Command Map banner and the Settings link use the same summary, so all three
 * agree on what is left.
 *
 * A step is done, needs attention, or optional (not needed, and not done). A step whose data has
 * not loaded is 'checking', and one the person's role cannot do is 'not_allowed': neither is
 * counted either way, because a guess is wrong exactly when someone looks.
 */

export type SetupStepId = 'model' | 'order' | 'search' | 'tools' | 'knowledge' | 'team' | 'notifications' | 'budget'

export type SetupStatus = 'done' | 'attention' | 'optional' | 'checking' | 'not_allowed'

export type SetupStep = { id: SetupStepId; status: SetupStatus; detail: string }

/** The order the page lists the steps in. */
export const SETUP_ORDER: readonly SetupStepId[] = ['model', 'order', 'search', 'tools', 'knowledge', 'team', 'notifications', 'budget']

/** Steps that are only nice to have: they never hold the banner up. */
export const OPTIONAL_STEPS: ReadonlySet<SetupStepId> = new Set(['search', 'notifications', 'budget'])

type Known<T> = { allowed: boolean; data: T | undefined }

export type SetupFacts = {
  /** Whether a live model answers runs, and which (lib/routing.ts liveRouting). */
  model: Known<{ live: boolean; providerName: string | null }>
  /** How many candidates in the workspace chain are live and ready. */
  order: Known<{ readyCandidates: number }>
  search: Known<{ meaning: boolean; model: string }>
  tools: Known<{ live: number; available: number }>
  knowledge: Known<{ documents: number }>
  team: Known<{ members: number; invited: number }>
  notifications: Known<{ webhook: boolean; browser: boolean }>
  budget: Known<{ monthlyCap: number | null }>
}

const plural = (count: number, one: string, many = `${one}s`) => `${count} ${count === 1 ? one : many}`

// @find: compute setup steps, step status from facts, what is left to set up
export function setupSteps(facts: SetupFacts): SetupStep[] {
  const step = <K extends SetupStepId>(id: K, known: Known<unknown>, decide: () => Omit<SetupStep, 'id'>): SetupStep => {
    if (!known.allowed) return { id, status: 'not_allowed', detail: 'Your role cannot change this. An owner or admin can.' }
    if (known.data === undefined) return { id, status: 'checking', detail: 'Checking…' }
    return { id, ...decide() }
  }
  return [
    step('model', facts.model, () =>
      facts.model.data!.live
        ? { status: 'done', detail: `Agents answer with ${facts.model.data!.providerName ?? 'a live model'}.` }
        : { status: 'attention', detail: 'No live model yet, so runs answer with placeholder text.' },
    ),
    step('order', facts.order, () => {
      const ready = facts.order.data!.readyCandidates
      return ready >= 2
        ? { status: 'done', detail: `${plural(ready, 'live model')} in order, so a backup takes over when one fails.` }
        : ready === 1
          ? { status: 'attention', detail: 'One live model and no backup. If it fails, runs fail or fall back to placeholder text.' }
          : { status: 'attention', detail: 'Connect a model first, then choose the order.' }
    }),
    step('search', facts.search, () =>
      facts.search.data!.meaning
        ? { status: 'done', detail: `Documents are searched by meaning, with ${facts.search.data!.model}.` }
        : { status: 'optional', detail: 'Keyword search only. Searching by meaning finds answers worded differently.' },
    ),
    step('tools', facts.tools, () => {
      const { live, available } = facts.tools.data!
      return live > 0
        ? { status: 'done', detail: `${plural(live, 'tool')} connected to a live account.` }
        : { status: 'attention', detail: `None connected yet. Agents use practice data until you connect ${available > 0 ? 'one' : 'a tool'}.` }
    }),
    step('knowledge', facts.knowledge, () => {
      const documents = facts.knowledge.data!.documents
      return documents > 0
        ? { status: 'done', detail: `${plural(documents, 'document')} for agents and Chat to search.` }
        : { status: 'attention', detail: 'No documents yet. Agents can only answer from what you add.' }
    }),
    step('team', facts.team, () => {
      const { members, invited } = facts.team.data!
      return members > 1 || invited > 0
        ? { status: 'done', detail: `${plural(members, 'person', 'people')} in the workspace${invited ? `, ${invited} invited` : ''}.` }
        : { status: 'attention', detail: 'Only you so far. Invite the people who will approve and use the work.' }
    }),
    step('notifications', facts.notifications, () => {
      const { webhook, browser } = facts.notifications.data!
      return webhook || browser
        ? { status: 'done', detail: webhook ? 'Messages go to your webhook.' : 'This browser shows notifications.' }
        : { status: 'optional', detail: 'Get told when an approval or a question is waiting.' }
    }),
    step('budget', facts.budget, () => {
      const cap = facts.budget.data!.monthlyCap
      return cap !== null
        ? { status: 'done', detail: `Monthly cap of US$${cap}.` }
        : { status: 'optional', detail: 'No monthly cap. Set one so spending cannot surprise you.' }
    }),
  ]
}

export type SetupSummary = {
  /** Steps that are done, out of those the person can act on and that have loaded. */
  done: number
  total: number
  /** Non-optional steps still needing attention. */
  remaining: number
  /** True once every non-optional step the person can do is done; false while any is unknown. */
  complete: boolean
}

// @find: setup summary, how many steps done, steps left, setup banner count
export function summarise(steps: readonly SetupStep[]): SetupSummary {
  const counted = steps.filter((step) => step.status !== 'not_allowed' && step.status !== 'checking')
  const checking = steps.some((step) => step.status === 'checking')
  const remaining = steps.filter((step) => step.status === 'attention' && !OPTIONAL_STEPS.has(step.id)).length
  return {
    done: counted.filter((step) => step.status === 'done').length,
    total: steps.filter((step) => step.status !== 'not_allowed').length,
    remaining,
    complete: !checking && remaining === 0,
  }
}

/** The words for a status, as the page's tag shows them. */
export const STATUS_LABEL: Record<SetupStatus, string> = {
  done: 'Done',
  attention: 'Needs attention',
  optional: 'Optional',
  checking: 'Checking',
  not_allowed: 'Owner or admin',
}
