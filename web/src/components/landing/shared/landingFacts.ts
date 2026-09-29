/*
 * The facts the public pages state, in one place.
 *
 * Every figure and name here is one the platform actually makes good, and the counts match the
 * services, providers, tool servers and permission codes that ship. A number that appears in two
 * sections comes from here, so the pages cannot drift apart.
 *
 * The home page is written for the people who choose the product, not the engineers who run it,
 * so the agent copy below is plain English: what each assistant does, not which tools it calls.
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
    title: 'Hiring and onboarding',
    detail: 'Checks applications against the role, drafts the welcome email and books interviews in the calendar.',
  },
  {
    id: 'eng',
    name: 'Engineering Manager',
    category: 'engineering',
    title: 'Keeping projects on track',
    detail:
      'Keeps tickets up to date, sums up open pull requests and writes the daily standup note, so nobody has to.',
  },
  {
    id: 'research',
    name: 'Research',
    category: 'growth',
    title: 'Market and competitor research',
    detail:
      'Pulls together what your own documents already say, points out what is missing and writes the summary into a new document.',
  },
  {
    id: 'support',
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

export const TOOL_SERVERS = ['gmail', 'calendar', 'slack', 'github', 'jira', 'drive'] as const

export type ToolServer = (typeof TOOL_SERVERS)[number]

/** How each tool server is named to a reader: the product it stands for, not its identifier. */
export const TOOL_LABEL: Record<ToolServer, string> = {
  gmail: 'Gmail',
  calendar: 'Calendar',
  slack: 'Slack',
  github: 'GitHub',
  jira: 'Jira',
  drive: 'Drive',
}

export const SERVICE_COUNT = 8

export const PERMISSION_CODE_COUNT = 46

export const BUILT_IN_ROLE_COUNT = 5

export const SIMULATED_PAGE_NOTICE = 'Simulated in your browser. Nothing is sent, and no account is needed.'

export const CTA = {
  demo: { label: 'Try the demo', href: '/sign-in' },
  create: { label: 'Create your workspace', href: '/create-workspace' },
  signIn: { label: 'Sign in', href: '/sign-in' },
  technical: { label: 'Technical details for IT teams', href: '/trust' },
} as const

export const DEMO_ACCOUNTS_HEDGE = 'One-click demo accounts are on the sign-in page whenever this site offers them.'
