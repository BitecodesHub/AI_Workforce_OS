/*
 * The facts the public pages state, in one place.
 *
 * Every figure and name here is one the platform actually makes good, and the counts match the
 * services, providers, connectors and permission codes that ship. A number that appears in two
 * sections comes from here, so the pages cannot drift apart.
 *
 * The home page is written for the people who choose the product, not the engineers who run it,
 * so the agent copy below is plain English: what each assistant does, not which tools it calls.
 */

import type { TemplateKey } from '../../../lib/templates'

export type AgentCategory = 'operations' | 'engineering' | 'growth' | 'support'

export type AgentId = 'hr' | 'eng' | 'research' | 'support'

export type AgentFact = {
  id: AgentId
  /**
   * The key the real assistant is created under: a new workspace's copy of this one, from
   * lib/templates.ts (which mirrors AgentTemplates.java). lib/templates.test.ts holds the four in
   * step, so the page cannot describe an assistant a workspace cannot add.
   */
  key: TemplateKey
  name: string
  category: AgentCategory
  title: string
  detail: string
}

export const AGENTS: ReadonlyArray<AgentFact> = [
  {
    id: 'hr',
    key: 'hr',
    name: 'HR',
    category: 'operations',
    title: 'Hiring and onboarding',
    detail: 'Checks applications against the role, drafts the welcome email and books interviews in the calendar.',
  },
  {
    id: 'eng',
    key: 'engineering-manager',
    name: 'Engineering Manager',
    category: 'engineering',
    title: 'Keeping projects on track',
    detail:
      'Keeps tickets up to date, sums up open pull requests and writes the daily standup note, so nobody has to.',
  },
  {
    id: 'research',
    key: 'research',
    name: 'Research',
    category: 'growth',
    title: 'Market and competitor research',
    detail:
      'Pulls together what your own documents already say, points out what is missing and writes the summary into a new document.',
  },
  {
    id: 'support',
    key: 'support',
    name: 'Customer Support',
    category: 'support',
    title: 'Answering customers',
    detail:
      'Sorts the overnight queue, drafts replies from your support handbook and passes on anything it cannot answer.',
  },
]

export const PROVIDERS = [
  'OpenRouter',
  'Groq',
  'NVIDIA NIM',
  'Google Gemini',
  'AWS Bedrock',
  'Anthropic',
  'OpenAI',
] as const

export type ProviderName = (typeof PROVIDERS)[number]

export const SANDBOX_MODEL = 'Offline sandbox model'

/*
 * Connectors, as mcp-core's ConnectorCatalog.java (os.aiworkforce.mcp.catalog) defines them. That
 * catalogue is the source of truth and these lists only copy it: a connector is live here only
 * where the catalogue marks it liveAvailable, because only those have an adapter that talks to the
 * real service with a token or address an administrator adds. Every other one works with practice
 * data. Voice notes are in the catalogue too but connect to nothing outside, so they are not
 * counted. Check both lists against the catalogue whenever it changes.
 *
 * The home page promises no live connection at all (see CONNECTOR_NOTICE); only the page for IT
 * teams names the live ones.
 */
export const LIVE_CONNECTORS = [
  'github', 'slack', 'notion', 'linear', 'hubspot', 'webhook',
  'jira', 'confluence', 'asana', 'zendesk', 'stripe', 'zoom',
  'gmail', 'calendar', 'drive', 'sheets', 'outlook', 'teams', 'salesforce',
] as const

export const PRACTICE_CONNECTORS = [] as const

export type ConnectorId = (typeof LIVE_CONNECTORS)[number] | (typeof PRACTICE_CONNECTORS)[number]

/** The catalogue's display names. */
export const CONNECTOR_LABEL: Record<ConnectorId, string> = {
  github: 'GitHub',
  slack: 'Slack',
  notion: 'Notion',
  linear: 'Linear',
  hubspot: 'HubSpot',
  webhook: 'Webhook',
  gmail: 'Gmail',
  calendar: 'Google Calendar',
  drive: 'Google Drive',
  sheets: 'Google Sheets',
  outlook: 'Microsoft Outlook',
  teams: 'Microsoft Teams',
  zoom: 'Zoom',
  salesforce: 'Salesforce',
  jira: 'Jira',
  confluence: 'Confluence',
  asana: 'Asana',
  zendesk: 'Zendesk',
  stripe: 'Stripe',
}

export const CONNECTOR_COUNT = LIVE_CONNECTORS.length + PRACTICE_CONNECTORS.length

/** What the home page says about connectors: true of every install, and no promise of a live one. */
export const CONNECTOR_NOTICE = 'Connectors run in practice mode in the demo; nothing real is sent.'

/**
 * The tools the four demo assistants are given (orchestrator-service DemoAgentSeeder.java, from the
 * catalogue in AgentTemplates.java). In the demo workspace each runs on practice data, whichever
 * kind of connector it is.
 */
export type ToolServer = Extract<ConnectorId, 'gmail' | 'calendar' | 'slack' | 'github' | 'jira' | 'drive'>

/** How a demo assistant's tool is named on its chip: short, since the tile is small. */
export const TOOL_LABEL: Record<ToolServer, string> = {
  gmail: 'Gmail',
  calendar: 'Calendar',
  slack: 'Slack',
  github: 'GitHub',
  jira: 'Jira',
  drive: 'Drive',
}

/** "a, b and c", for the sentences that name a list. */
export function listOf(items: readonly string[]): string {
  if (items.length < 2) return items.join('')
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`
}

export const SERVICE_COUNT = 8

export const PERMISSION_CODE_COUNT = 46

export const BUILT_IN_ROLE_COUNT = 5

export const SIMULATED_PAGE_NOTICE = 'Simulated in your browser. Nothing is sent, and no account is needed.'

/*
 * "Try the demo" leads to the sign-in page's one-click demo accounts, so it is offered only where
 * this site has them (see useDemoCta). Everywhere else, and until the site has answered, the same
 * button is "See it work" and scrolls to the simulated demos on this page, which work anywhere.
 */
export const CTA = {
  demo: { label: 'Try the demo', href: '/sign-in' },
  seeItWork: { label: 'See it work', href: '#demos' },
  create: { label: 'Create your workspace', href: '/create-workspace' },
  signIn: { label: 'Sign in', href: '/sign-in' },
  technical: { label: 'Technical details for IT teams', href: '/trust' },
} as const
