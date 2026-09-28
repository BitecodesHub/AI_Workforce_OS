import type { ReactElement, ReactNode } from 'react'
import { useEffect, useLayoutEffect, useReducer, useRef } from 'react'
import { Navbar } from './components/layout/Navbar'
import { match, useRouter } from './lib/router'
import { isSignedIn, can, SESSION_EVENT } from './lib/session'
import { CommandMap } from './routes/CommandMap'
import { Agents } from './routes/Agents'
import { AgentDetail } from './routes/AgentDetail'
import { ModelRouting } from './routes/ModelRouting'
import { Tasks } from './routes/Tasks'
import { Runs } from './routes/Runs'
import { RunDetail } from './routes/RunDetail'
import { Approvals } from './routes/Approvals'
import { Chat } from './routes/Chat'
import { Orchestrator } from './routes/Orchestrator'
import { Schedules } from './routes/Schedules'
import { Knowledge } from './routes/Knowledge'
import { SourceDetail } from './routes/SourceDetail'
import { Integrations } from './routes/Integrations'
import { Members } from './routes/Members'
import { AuditLog } from './routes/AuditLog'
import { Analytics } from './routes/Analytics'
import { SignIn } from './routes/SignIn'
import { CreateWorkspace } from './routes/CreateWorkspace'
import { AcceptInvite } from './routes/AcceptInvite'
import { Landing } from './routes/Landing'
import { Profile } from './routes/Profile'
import { NotFound } from './routes/NotFound'
import { PageHeader, PermissionState } from './components/ui'

/*
 * The application shell and its routes.
 *
 * One table lists every destination. Screens that need a signed-in session are behind a guard
 * that sends a signed-out visitor to sign in and brings them back afterwards, so a bookmarked
 * link to a run works rather than showing an empty screen full of failed requests.
 *
 * Every signed-in screen, including "not found" and "not in your role", keeps the navigation
 * bar: a person who lands somewhere unexpected should still be one click from anywhere else.
 */

type Permission = string | null

type RouteMeta = {
  permission: Permission
  screen: (params: Record<string, string>) => ReactElement
}

const PUBLIC: Array<[string, RouteMeta]> = [
  ['/home', { permission: null, screen: () => <Landing /> }],
  ['/sign-in', { permission: null, screen: () => <SignIn /> }],
  ['/create-workspace', { permission: null, screen: () => <CreateWorkspace /> }],
  ['/accept-invite', { permission: null, screen: () => <AcceptInvite /> }],
]

const PRIVATE: Array<[string, RouteMeta]> = [
  ['/', { permission: null, screen: () => <CommandMap /> }],
  ['/agents', { permission: 'agent:read', screen: () => <Agents /> }],
  ['/agents/:id', { permission: 'agent:read', screen: (p) => <AgentDetail id={p.id!} /> }],
  ['/tasks', { permission: 'task:read', screen: () => <Tasks /> }],
  ['/schedules', { permission: 'task:read', screen: () => <Schedules /> }],
  ['/runs', { permission: 'run:read', screen: () => <Runs /> }],
  ['/runs/:id', { permission: 'run:read', screen: (p) => <RunDetail id={p.id!} /> }],
  ['/orchestrator', { permission: 'run:read', screen: () => <Orchestrator /> }],
  ['/approvals', { permission: 'approval:read', screen: () => <Approvals /> }],
  ['/chat', { permission: 'chat:use', screen: () => <Chat /> }],
  ['/knowledge', { permission: 'knowledge:read', screen: () => <Knowledge /> }],
  ['/knowledge/:id', { permission: 'knowledge:read', screen: (p) => <SourceDetail id={p.id!} /> }],
  ['/integrations', { permission: 'integration:read', screen: () => <Integrations /> }],
  ['/routing', { permission: 'provider:read', screen: () => <ModelRouting /> }],
  ['/members', { permission: 'member:read', screen: () => <Members /> }],
  ['/audit', { permission: 'audit:read', screen: () => <AuditLog /> }],
  ['/analytics', { permission: 'analytics:read', screen: () => <Analytics /> }],
  ['/profile', { permission: null, screen: () => <Profile /> }],
]

function find(routes: Array<[string, RouteMeta]>, path: string) {
  for (const [pattern, meta] of routes) {
    const params = match(pattern, path)
    if (params) return { meta, params, pattern }
  }
  return null
}

/** The skip link, the navigation bar and the main landmark around a signed-in screen. */
function Shell({ path, children }: { path: string; children: ReactNode }) {
  return (
    <div>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <Navbar currentPath={path} />
      {/* Focusable from script and from the skip link only; keyed on the path so each screen
          starts fresh and fades in. */}
      <main id="main" tabIndex={-1} key={path} className="route-enter">
        {children}
      </main>
    </div>
  )
}

export function App() {
  const { path, search, hash, navigate } = useRouter()
  const signedIn = isSignedIn()

  // A session renewal can change the role, and with it every can() in the navigation and the
  // route guards below. Re-rendering on the renewal event shows the change straight away.
  const [, sessionChanged] = useReducer((version: number) => version + 1, 0)
  useEffect(() => {
    window.addEventListener(SESSION_EVENT, sessionChanged)
    return () => window.removeEventListener(SESSION_EVENT, sessionChanged)
  }, [])

  const publicRoute = find(PUBLIC, path)
  const privateRoute = find(PRIVATE, path)

  // A signed-out visitor at the root sees the public page; at any other private screen they are
  // sent to sign in and returned afterwards, to the same filtered list or the same #item. The
  // redirect replaces the history entry, so Back does not bounce straight into it again.
  const redirectToSignIn = Boolean(!signedIn && privateRoute && path !== '/')
  const query = search.toString()
  const here = `${path}${query ? `?${query}` : ''}${hash}`
  useEffect(() => {
    if (redirectToSignIn) navigate(`/sign-in?next=${encodeURIComponent(here)}`, { replace: true })
  }, [redirectToSignIn, navigate, here])

  // Page titles follow the screen, so browser history and tabs are readable. A signed-out visitor
  // at the root sees the public page, so it takes the public page's title, not the Command Map's.
  // Written in a layout effect: a detail screen names the tab after its record (useDocumentTitle)
  // in a passive effect, which runs after this one and so wins.
  const titlePattern = !signedIn && path === '/' ? '/home' : publicRoute?.pattern ?? privateRoute?.pattern ?? ''
  useLayoutEffect(() => {
    showTitle(titlePattern)
  }, [titlePattern, path])

  // After moving to another screen, focus goes to its heading, so a screen reader announces
  // where the person has arrived and the next Tab starts from the content rather than from
  // wherever the old page left it. Not on first load, where the browser's own start is right.
  const shownPath = useRef(path)
  useEffect(() => {
    if (shownPath.current === path) return
    shownPath.current = path
    focusMainHeading()
  }, [path])

  if (!signedIn && path === '/') return <Landing />
  if (publicRoute) return publicRoute.meta.screen(publicRoute.params)
  if (redirectToSignIn) return <div className="page" aria-busy="true" />

  const current = privateRoute
  if (!current) {
    return signedIn ? (
      <Shell path={path}>
        <NotFound />
      </Shell>
    ) : (
      <main id="main" tabIndex={-1}>
        <NotFound />
      </main>
    )
  }

  const { meta, params, pattern } = current
  if (meta.permission && !can(meta.permission)) {
    return (
      <Shell path={path}>
        <div className="page">
          <PageHeader eyebrow="Not in your role" title={titleFor(pattern)} />
          <PermissionState permission={meta.permission} what="this screen" />
        </div>
      </Shell>
    )
  }

  return <Shell path={path}>{meta.screen(params)}</Shell>
}

/** Writes the browser tab title for a route pattern. It lives outside App because it writes a global. */
function showTitle(pattern: string): void {
  document.title = titleFor(pattern) + ' · AI Workforce OS'
}

/**
 * Moves focus to the new screen's h1, or to the main landmark when the screen has none yet.
 *
 * A screen may already have placed focus itself in the same commit (Approvals focuses the card a
 * /approvals#approval-... link points at). Child effects run before this one, and main is new on
 * every path, so focus already inside it was put there on purpose and is left alone.
 */
function focusMainHeading(): void {
  const main = document.querySelector<HTMLElement>('main')
  if (!main) return
  const active = document.activeElement
  if (active && active !== main && main.contains(active)) return
  const target = main.querySelector<HTMLElement>('h1') ?? main
  if (!target.hasAttribute('tabindex')) target.setAttribute('tabindex', '-1')
  target.focus({ preventScroll: true })
}

function titleFor(pattern: string): string {
  const titles: Record<string, string> = {
    '/': 'Command Map',
    '/home': 'A governed AI workforce',
    '/sign-in': 'Sign in',
    '/create-workspace': 'Create a workspace',
    '/accept-invite': 'Join the workspace',
    '/agents': 'Agents',
    '/agents/:id': 'Agent',
    '/tasks': 'Tasks',
    '/schedules': 'Schedules',
    '/runs': 'Runs',
    '/runs/:id': 'Run',
    '/orchestrator': 'Orchestrator',
    '/approvals': 'Approvals',
    '/chat': 'Chat',
    '/knowledge': 'Knowledge',
    '/knowledge/:id': 'Source',
    '/integrations': 'Integrations',
    '/routing': 'Model routing',
    '/members': 'Members and roles',
    '/audit': 'Audit log',
    '/analytics': 'Analytics',
    '/profile': 'Your profile',
  }
  return titles[pattern] ?? 'Not found'
}
