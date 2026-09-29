import type { ReactElement, ReactNode } from 'react'
import { Suspense, lazy, useEffect, useLayoutEffect, useReducer, useRef } from 'react'
import { Navbar } from './components/layout/Navbar'
import { match, useRouter } from './lib/router'
import { isSignedIn, can, SESSION_EVENT } from './lib/session'
import { CommandMap } from './routes/CommandMap'
import { SignIn } from './routes/SignIn'
import { CreateWorkspace } from './routes/CreateWorkspace'
import { AcceptInvite } from './routes/AcceptInvite'
import { Landing } from './routes/Landing'
import { Profile } from './routes/Profile'
import { NotFound } from './routes/NotFound'
import { PageHeader, PermissionState } from './components/ui'

/*
 * Every screen below is fetched only when it is actually visited: a first-time visitor to the
 * marketing page never downloads Chat or Orchestrator, and a signed-in user landing on the
 * Command Map never downloads the technical page for IT teams. The two default screens above
 * (Command Map for a signed-in visit to "/", Landing for a signed-out one) and the small,
 * always-on-the-critical-path auth screens stay in the main bundle, so neither common first paint
 * adds a round trip.
 */
const Trust = lazy(() => import('./routes/Trust').then((m) => ({ default: m.Trust })))
const Agents = lazy(() => import('./routes/Agents').then((m) => ({ default: m.Agents })))
const AgentDetail = lazy(() => import('./routes/AgentDetail').then((m) => ({ default: m.AgentDetail })))
const ModelRouting = lazy(() => import('./routes/ModelRouting').then((m) => ({ default: m.ModelRouting })))
const Tasks = lazy(() => import('./routes/Tasks').then((m) => ({ default: m.Tasks })))
const Runs = lazy(() => import('./routes/Runs').then((m) => ({ default: m.Runs })))
const RunDetail = lazy(() => import('./routes/RunDetail').then((m) => ({ default: m.RunDetail })))
const Approvals = lazy(() => import('./routes/Approvals').then((m) => ({ default: m.Approvals })))
const Chat = lazy(() => import('./routes/Chat').then((m) => ({ default: m.Chat })))
const Orchestrator = lazy(() => import('./routes/Orchestrator').then((m) => ({ default: m.Orchestrator })))
const Schedules = lazy(() => import('./routes/Schedules').then((m) => ({ default: m.Schedules })))
const Knowledge = lazy(() => import('./routes/Knowledge').then((m) => ({ default: m.Knowledge })))
const SourceDetail = lazy(() => import('./routes/SourceDetail').then((m) => ({ default: m.SourceDetail })))
const Integrations = lazy(() => import('./routes/Integrations').then((m) => ({ default: m.Integrations })))
const Members = lazy(() => import('./routes/Members').then((m) => ({ default: m.Members })))
const AuditLog = lazy(() => import('./routes/AuditLog').then((m) => ({ default: m.AuditLog })))
const Analytics = lazy(() => import('./routes/Analytics').then((m) => ({ default: m.Analytics })))

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
  ['/trust', { permission: null, screen: () => <Trust /> }],
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
  if (publicRoute) {
    return (
      <Suspense fallback={<div className="page" aria-busy="true" />}>{publicRoute.meta.screen(publicRoute.params)}</Suspense>
    )
  }
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

  return (
    <Shell path={path}>
      <Suspense fallback={<div className="page" aria-busy="true" />}>{meta.screen(params)}</Suspense>
    </Shell>
  )
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
    '/home': 'AI employees for your business',
    '/trust': 'Technical details',
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
