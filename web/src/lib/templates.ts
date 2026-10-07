/*
 * The ready-made assistants, as the console knows them before it can ask the platform.
 *
 * The same four are defined in orchestrator-service AgentTemplates.java, which is the source of
 * truth: it holds their instructions, and it is what GET /api/agent-templates and POST
 * /api/agents/from-template/{key} serve. This short copy exists for the one place that runs
 * before there is a session to ask: the "Which assistants do you want?" step of creating a
 * workspace. templates.test.ts reads AgentTemplates.java and fails when a key, name, category or
 * description here differs from it, so the two cannot drift apart unnoticed. Change both together.
 *
 * The public home page's four example assistants (components/landing/shared/landingFacts.ts) are
 * checked against these keys in the same test.
 */

export type TemplateKey = 'hr' | 'engineering-manager' | 'research' | 'support'

export type AgentTemplate = {
  key: TemplateKey
  name: string
  category: string
  /** What it does, in one plain sentence. */
  description: string
  /** The connectors worth connecting so it can act, by connector id. */
  suggestedConnectors: readonly string[]
}

export const TEMPLATES: readonly AgentTemplate[] = [
  {
    key: 'hr',
    name: 'HR',
    category: 'operations',
    description: 'Checks applications against the role, drafts the welcome email and books interviews in the calendar.',
    suggestedConnectors: ['gmail', 'calendar'],
  },
  {
    key: 'engineering-manager',
    name: 'Engineering Manager',
    category: 'engineering',
    description:
      'Keeps tickets up to date, sums up open pull requests and writes the daily standup note, so nobody has to.',
    suggestedConnectors: ['github', 'jira', 'slack'],
  },
  {
    key: 'research',
    name: 'Research',
    category: 'growth',
    description:
      'Pulls together what your own documents already say, points out what is missing and writes the summary into a new document.',
    suggestedConnectors: ['drive'],
  },
  {
    key: 'support',
    name: 'Customer Support',
    category: 'support',
    description:
      'Sorts the overnight queue, drafts replies from your support handbook and passes on anything it cannot answer.',
    suggestedConnectors: ['gmail', 'slack', 'drive'],
  },
]

export const TEMPLATE_KEYS: readonly TemplateKey[] = TEMPLATES.map((template) => template.key)

/** The template with this key, or undefined for one this build does not know. */
export function templateFor(key: string): AgentTemplate | undefined {
  return TEMPLATES.find((template) => template.key === key)
}

/**
 * The prompt that follows a new assistant: "Connect Gmail to let it act." One sentence per
 * connector, in the order the template lists them, with the connector named as people know it.
 * An assistant starts with no connectors, so each of these is something still to do.
 */
export function connectPrompts(connectors: readonly string[], label: (connector: string) => string): string[] {
  return connectors.map((connector) => `Connect ${label(connector)} to let it act.`)
}
