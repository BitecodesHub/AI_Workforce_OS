// @find: faq data, frequently asked questions, buyer questions and answers, FAQ_ITEMS, FAQPage structured data source, who is it for, what can it do without asking, how do we get started
// @what: The home page's questions and answers in plain words, the one source for the visible FAQ and its structured data.
// @flow: Rendered by Faq.tsx; read by src/seo/structuredData.ts (FAQPage) and src/seo/crawlerFiles.ts (llms-full.txt), so the markup and the files always say what the page says

import { CONNECTOR_LABEL, CONNECTOR_NOTICE, PROVIDERS, listOf } from '../shared/landingFacts'

/*
 * The questions a buyer asks, answered plainly.
 *
 * This is where the home page's limits live: the offline model, how connectors reach real
 * accounts, what happens without documents, what runs without asking and who may approve. Each
 * answer is true of the platform as it ships. Connectors reach a real account only once an owner
 * or admin connects them, and use practice data until then; the full list of connectors is the
 * technical page's to state (see landingFacts). Nothing here mentions demo accounts either, since
 * a site may not offer them.
 *
 * What runs without asking is taken from agentGrants.ts: reading, drafting, adding a calendar
 * event and updating a ticket or a GitHub issue run directly, while sending, posting and deleting
 * always wait. Owners and admins can make more actions wait: an agent's grant for a connector has a
 * requireApproval switch (orchestrator AgentGrantController, PUT /api/agents/{id}/grants/{server}),
 * behind agent:grant_tools, which only the Owner and Admin roles hold (roleData.ts). The built-in
 * roles themselves are fixed (identity RoleController refuses to edit or delete them); owners and
 * admins create roles of their own.
 *
 * Search and answer engines read these through the FAQPage structured data, built from this list
 * so it can never say something the page does not.
 */

/** One question and its answer, both plain text. */
export type FaqItem = { q: string; a: string }

/** The questions and answers, in the order the page shows them. Connector wording comes from landingFacts, and the provider list is built with listOf. */
export const FAQ_ITEMS: ReadonlyArray<FaqItem> = [
  {
    q: 'Who is it for?',
    a: 'Companies that want AI assistants to take on routine work, such as hiring and onboarding, customer emails, research summaries and project updates, while people keep the final say.',
  },
  {
    q: 'Will it send anything without asking?',
    a: 'No. Sending an email, posting a message or deleting something always waits for a person who is allowed to approve it. If nobody answers before the deadline, the request expires and nothing is sent.',
  },
  {
    q: 'What can it do without asking?',
    a: 'Reading, drafting and routine updates, such as adding a calendar event or updating a ticket, happen on their own. Sending an email, posting a message and deleting something wait for a person. Owners and admins can make more actions wait for approval.',
  },
  {
    q: 'Do I need technical skills to use it?',
    a: 'No. You ask for work in plain words in Chat, typed or spoken. The one-off set-up, such as choosing which AI provider to use, is done once by whoever looks after your workspace.',
  },
  {
    q: 'What happens when it does not know something?',
    a: 'It tells you. Answers come from the documents you add, with the page each point came from. When nothing in your documents covers the question, it says it cannot answer rather than guessing.',
  },
  {
    q: 'Can we try it before connecting anything?',
    a: 'Yes. Out of the box it runs on a built-in sandbox model that gives practice answers, clearly labelled as such, and every connector works with practice data, so nothing real is sent.',
  },
  {
    q: 'Which AI does it use?',
    a: `The one you choose: ${listOf([...PROVIDERS])}. You set the order, and if one is busy or unavailable it moves on to the next.`,
  },
  {
    q: 'Does it work with our email and calendar?',
    a: `Yes. ${[CONNECTOR_LABEL.gmail, CONNECTOR_LABEL.calendar, CONNECTOR_LABEL.outlook].join(', ')} and the other connectors connect to your real accounts once an owner or admin sets them up. ${CONNECTOR_NOTICE} Your IT team will find the technical details on the page for IT teams.`,
  },
  {
    q: 'Who can see and approve things?',
    a: 'Each person gets a role. An employee can hand work to the AI team but cannot approve what it sends, and a viewer can look at reports but change nothing. Owners and admins can create roles of their own to fit your team.',
  },
  {
    q: 'How do we get started?',
    a: 'Create your workspace. Whoever creates it becomes the owner, can start with ready-made assistants and can invite everyone else afterwards.',
  },
]
