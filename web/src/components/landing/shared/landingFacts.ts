/*
 * The facts the public page states, in one place.
 *
 * Every figure and name here is one the platform actually makes good: the agent copy is carried
 * over verbatim from the previous home page, and the counts match the services, providers, tool
 * servers and permission codes that ship. A number that appears in two sections comes from here,
 * so the hero, the platform band and the demos cannot drift apart.
 */

export type AgentCategory = 'operations' | 'engineering' | 'growth' | 'support'

export type AgentId = 'hr' | 'eng' | 'research' | 'support'

export type AgentFact = {
  id: AgentId
  name: string
  category: AgentCategory
  title: string
  detail: string
}

export const AGENTS: ReadonlyArray<AgentFact> = [
  {
    id: 'hr',
    name: 'HR',
    category: 'operations',
    title: 'Screening and onboarding',
    detail:
      'Reads applications against the role requirements, drafts the onboarding email, and books ' +
      'the interview in the calendar.',
  },
  {
    id: 'eng',
    name: 'Engineering Manager',
    category: 'engineering',
    title: 'Sprint bookkeeping',
    detail:
      'Keeps tickets current, summarises open pull requests, and posts the standup note so the ' +
      'team does not write it by hand.',
  },
  {
    id: 'research',
    name: 'Research',
    category: 'growth',
    title: 'Market and competitor reports',
    detail:
      'Gathers what is already known from your own documents, compiles the gaps, and writes the ' +
      'summary into a shareable file.',
  },
  {
    id: 'support',
    name: 'Customer Support',
    category: 'support',
    title: 'Ticket triage',
    detail:
      'Sorts the overnight queue, drafts replies from your support handbook with citations, and ' +
      'escalates what it cannot answer.',
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

export const TOOL_SERVERS = ['gmail', 'calendar', 'slack', 'github', 'jira', 'drive'] as const

export type ToolServer = (typeof TOOL_SERVERS)[number]

export const SERVICE_COUNT = 8

export const PERMISSION_CODE_COUNT = 46

export const BUILT_IN_ROLE_COUNT = 5

export const SIMULATED_PAGE_NOTICE = 'Simulated in your browser. Nothing is sent, and no account is needed.'

export const CTA = {
  demo: { label: 'Try a demo account', href: '/sign-in' },
  create: { label: 'Create a workspace', href: '/create-workspace' },
  signIn: { label: 'Sign in', href: '/sign-in' },
} as const

export const DEMO_ACCOUNTS_HEDGE =
  'When this environment offers demo accounts, the sign-in page lists one per role.'
