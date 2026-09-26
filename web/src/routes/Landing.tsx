import { Brand } from '../components/layout/Brand'
import { Card, Eyebrow, Tag } from '../components/ui'

/*
 * The public home page.
 *
 * It has one job: explain what this is and who it is for, well enough that somebody decides
 * whether to sign in. So it describes the actual product rather than the category - what an agent
 * is allowed to do, what stops it, what happens when a provider fails - because "AI for business"
 * tells a reader nothing they can act on.
 *
 * Nothing here overstates the platform. The claims below are the ones the code actually makes
 * good, and the "what it does not do" section exists because a landing page that only lists
 * strengths is one nobody believes.
 */

type UseCase = {
  agent: string
  category: 'operations' | 'engineering' | 'growth' | 'support'
  title: string
  detail: string
  gated: string
}

const USE_CASES: UseCase[] = [
  {
    agent: 'HR',
    category: 'operations',
    title: 'Screening and onboarding',
    detail:
      'Reads applications against the role requirements, drafts the onboarding email, and books ' +
      'the interview in the calendar.',
    gated: 'Drafts freely. Sending waits for a person.',
  },
  {
    agent: 'Engineering Manager',
    category: 'engineering',
    title: 'Sprint bookkeeping',
    detail:
      'Keeps tickets current, summarises open pull requests, and posts the standup note so the ' +
      'team does not write it by hand.',
    gated: 'Updates tickets directly. Posting to a channel waits.',
  },
  {
    agent: 'Research',
    category: 'growth',
    title: 'Market and competitor reports',
    detail:
      'Gathers what is already known from your own documents, compiles the gaps, and writes the ' +
      'summary into a shareable file.',
    gated: 'Reads and writes documents. Sharing outside waits.',
  },
  {
    agent: 'Customer Support',
    category: 'support',
    title: 'Ticket triage',
    detail:
      'Sorts the overnight queue, drafts replies from your support handbook with citations, and ' +
      'escalates what it cannot answer.',
    gated: 'Drafts every reply. Each send waits for a person.',
  },
]

const PRINCIPLES = [
  {
    heading: 'It asks before it acts',
    body:
      'Anything that leaves the workspace or cannot be undone stops and waits for a named person ' +
      'holding the right permission. No configuration can remove that gate, only add to it.',
  },
  {
    heading: 'It answers from your documents',
    body:
      'Agents search your own policies, handbooks and files, and every answer carries the ' +
      'passages it relied on. When nothing supports an answer, the agent says so instead of ' +
      'guessing.',
  },
  {
    heading: 'It keeps working when a provider does not',
    body:
      'Each agent has an ordered chain of models. A provider that is throttled, out of credit or ' +
      'simply down is skipped, the next one answers, and the trace records exactly why.',
  },
  {
    heading: 'It writes down everything',
    body:
      'Every decision, tool call and refusal is recorded against the person accountable for it, ' +
      'in a log whose entries are chained so an altered one can be detected.',
  },
]

const ROLES = [
  { role: 'owner', can: 'Everything, including billing and closing the workspace' },
  { role: 'admin', can: 'Manages people, agents, integrations and settings' },
  { role: 'manager', can: 'Runs agents and approves their actions' },
  { role: 'employee', can: 'Asks questions and hands over routine work' },
  { role: 'viewer', can: 'Reads dashboards and traces, changes nothing' },
]

export function Landing() {
  return (
    <div>
      <header className="landing-bar">
        <Brand />
        <div className="row" style={{ gap: 'var(--space-3)' }}>
          <a className="nav-pill" href="#how-it-works">
            How it works
          </a>
          <a className="nav-pill" href="#use-cases">
            Use cases
          </a>
          <a className="button button-primary" href="/sign-in">
            Sign in
          </a>
        </div>
      </header>

      <main className="page" id="main">
        {/* ---- Hero ---- */}
        <section style={{ paddingTop: 'var(--space-8)', paddingBottom: 'var(--space-8)' }}>
          <Eyebrow>A governed AI workforce</Eyebrow>
          <h1 className="page-title" style={{ fontSize: '40px', maxWidth: '20ch' }}>
            A team of AI employees your company can actually authorise
          </h1>
          <p className="page-description" style={{ fontSize: '14px', maxWidth: '58ch' }}>
            Configure agents for the roles you already have, let them work from your own documents
            and your real tools, and keep a person in front of every action that cannot be taken
            back.
          </p>

          <div className="row" style={{ gap: 'var(--space-3)', marginTop: 'var(--space-6)' }}>
            <a className="button button-primary" href="/sign-in">
              Try a demo account
            </a>
            <a className="button button-outline" href="/create-workspace">
              Create a workspace
            </a>
          </div>

          <p className="caption" style={{ marginTop: 'var(--space-5)' }}>
            Runs on an offline model out of the box. No API key is needed to look around.
          </p>
        </section>

        {/* ---- What it is for ---- */}
        <section id="use-cases" style={{ paddingBottom: 'var(--space-8)' }}>
          <Eyebrow>What it is for</Eyebrow>
          <h2 className="page-title" style={{ fontSize: '26px', marginBottom: 'var(--space-3)' }}>
            Four roles, working the way a person would
          </h2>
          <p className="page-description" style={{ marginBottom: 'var(--space-6)' }}>
            Each agent is given a role, a set of tools and a limit on what it may do unsupervised.
          </p>

          <div className="landing-grid">
            {USE_CASES.map((useCase) => (
              <Card key={useCase.agent} as="article">
                <Eyebrow>{useCase.agent}</Eyebrow>
                <h3 className="section-heading" style={{ fontSize: '15px', marginBottom: 'var(--space-3)' }}>
                  {useCase.title}
                </h3>
                <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
                  {useCase.detail}
                </p>
                {/* The limit matters as much as the capability, so it is shown beside it rather
                    than buried in documentation. */}
                <Tag tone={useCase.category} withDot>
                  {useCase.gated}
                </Tag>
              </Card>
            ))}
          </div>
        </section>

        {/* ---- How it works ---- */}
        <section id="how-it-works" style={{ paddingBottom: 'var(--space-8)' }}>
          <Eyebrow>How it works</Eyebrow>
          <h2 className="page-title" style={{ fontSize: '26px', marginBottom: 'var(--space-6)' }}>
            Four things that make it safe to switch on
          </h2>

          <div className="landing-grid">
            {PRINCIPLES.map((principle) => (
              <Card key={principle.heading} as="article">
                <h3 className="section-heading" style={{ fontSize: '14px', marginBottom: 'var(--space-3)' }}>
                  {principle.heading}
                </h3>
                <p className="muted">{principle.body}</p>
              </Card>
            ))}
          </div>
        </section>

        {/* ---- Roles ---- */}
        <section style={{ paddingBottom: 'var(--space-8)' }}>
          <Card as="section">
            <Eyebrow>Who can do what</Eyebrow>
            <h2 className="section-heading" style={{ fontSize: '15px', marginBottom: 'var(--space-3)' }}>
              Five roles, and you can compose your own
            </h2>
            <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
              Roles are built from individual permissions and edited in the console. A change takes
              effect the next time somebody signs in, not the next time the platform is deployed.
            </p>

            <ul className="stack" style={{ gap: 'var(--space-3)', margin: 0, padding: 0, listStyle: 'none' }}>
              {ROLES.map((entry) => (
                <li
                  key={entry.role}
                  className="row"
                  style={{ gap: 'var(--space-4)', justifyContent: 'space-between' }}
                >
                  <Tag tone="blue">{entry.role}</Tag>
                  <span className="muted" style={{ flex: 1, textAlign: 'right' }}>
                    {entry.can}
                  </span>
                </li>
              ))}
            </ul>
          </Card>
        </section>

        {/* ---- Honesty ---- */}
        <section style={{ paddingBottom: 'var(--space-8)' }}>
          <Card as="section">
            <Eyebrow>What it does not do</Eyebrow>
            <h2 className="section-heading" style={{ fontSize: '15px', marginBottom: 'var(--space-4)' }}>
              Stated plainly, so nothing here is a surprise later
            </h2>
            {/* A page that lists only strengths is one nobody believes, and the limits below are
                the ones an evaluator would find within an hour anyway. */}
            <ul className="stack muted" style={{ gap: 'var(--space-3)', paddingLeft: 'var(--space-5)' }}>
              <li>
                Agents are not autonomous. They stop at every outbound or destructive action and
                wait for a person.
              </li>
              <li>
                Answers are only as good as the documents you connect. With nothing indexed, an
                agent will tell you it cannot answer.
              </li>
              <li>
                The offline model is a placeholder. It returns sensible-looking text so the
                platform can be tried, and says so wherever it answers.
              </li>
              <li>
                Connecting a live tool account needs an administrator. Until then every server runs
                against a sandbox and nothing leaves your machine.
              </li>
            </ul>
          </Card>
        </section>

        <footer style={{ paddingBottom: 'var(--space-8)' }}>
          <div className="row" style={{ gap: 'var(--space-4)', justifyContent: 'space-between' }}>
            <span className="caption">AI Workforce OS — an enterprise multi-agent platform.</span>
            <a className="nav-pill" href="/sign-in">
              Sign in
            </a>
          </div>
        </footer>
      </main>
    </div>
  )
}
