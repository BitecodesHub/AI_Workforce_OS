import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { Agent } from './queries'
import { TEMPLATES, type AgentTemplate } from './templates'

/*
 * The ready-made assistants a workspace can start from: the catalogue, and adding one.
 *
 * Adding an assistant creates an agent with its instructions and no connectors. Which connectors
 * it may act in is decided separately, once they are connected, so a new assistant comes with the
 * names of the connectors worth connecting (suggestedConnectors) and nothing more.
 */

/** What the platform serves for one assistant (AgentTemplateController.TemplateView). */
export type TemplateView = {
  key: string
  name: string
  category: string
  description: string
  suggestedConnectors: string[]
}

/** What adding one returns (AgentTemplateController.FromTemplateResult). */
export type FromTemplate = { agent: Agent; suggestedConnectors: string[] }

/*
 * The catalogue is also served at /api/agent-templates. This spelling is the one the gateway, the
 * development proxy and the launcher already route to the orchestrator, so it works wherever the
 * rest of /api/agents does.
 */
const TEMPLATES_PATH = '/api/agents/templates'
const fromTemplatePath = (key: string) => `/api/agents/from-template/${encodeURIComponent(key)}`

/** The catalogue's own list, as cards need it. */
export function viewOf(template: AgentTemplate): TemplateView {
  return { ...template, suggestedConnectors: [...template.suggestedConnectors] }
}

/**
 * The assistants to offer, from the platform when it has answered and from this build's copy until
 * then (and if it never does), so the cards are there on first paint and never an error.
 */
export function useAgentTemplates(options: { enabled?: boolean } = {}): TemplateView[] {
  const query = useQuery({
    queryKey: ['agent-templates'],
    queryFn: ({ signal }) => api<TemplateView[]>(TEMPLATES_PATH, { signal }),
    enabled: options.enabled ?? true,
    // A catalogue that changes with a release, not with a click.
    staleTime: 10 * 60_000,
  })
  return query.data && query.data.length > 0 ? query.data : TEMPLATES.map(viewOf)
}

/** Adds one assistant to the workspace (agent:create). Adding the same one twice makes a second, keyed -2. */
export function useCreateFromTemplate() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (key: string) => api<FromTemplate>(fromTemplatePath(key), { method: 'POST' }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['agents'] }),
  })
}

/** What adding several came to: the keys that were added and the keys that were not. */
export type AssistantsAdded = { created: string[]; failed: string[] }

/**
 * Adds assistants one after another, for the signup flow, which has no query client to lean on.
 * One that cannot be added never stops the rest, and nothing here throws: the workspace already
 * exists, and a missing assistant is something to add later from the Agents page, not a reason to
 * undo the signup.
 */
export async function createAssistants(keys: readonly string[]): Promise<AssistantsAdded> {
  const created: string[] = []
  const failed: string[] = []
  // In turn rather than together, so the platform's key suffixes are handed out without a race.
  for (const key of keys) {
    try {
      await api<FromTemplate>(fromTemplatePath(key), { method: 'POST' })
      created.push(key)
    } catch {
      failed.push(key)
    }
  }
  return { created, failed }
}
