// @find: app shell, routes, routing table, navigation, permissions per route, sign in redirect, restore session, session unreachable, tab title, waiting count, page titles, Navbar, error boundary, App
// @what: The root component: route table, permission checks, sign-in redirects, session restore, page shell and tab titles.
// @flow: Rendered by main.tsx; renders the screens in src/routes and the Navbar
import type { ReactElement, ReactNode } from 'react'
import { Suspense, lazy, useContext, useEffect, useLayoutEffect, useReducer, useRef, useState } from 'react'
import { QueryClientContext } from '@tanstack/react-query'
import { Navbar } from './components/layout/Navbar'
import { ErrorBoundary } from './components/ui/ErrorBoundary'
import { NETWORK_FAILURE, restoreSession } from './lib/api'
import { useAttention } from './lib/attention'
import { match, redirectFor, setPageTitle, useRouter } from './lib/router'
import { isSignedIn, can, maySignInSilently, SESSION_EVENT, watchSessionOwner } from './lib/session'
import { SignIn } from './routes/SignIn'
import { CreateWorkspace } from './routes/CreateWorkspace'
import { AcceptInvite } from './routes/AcceptInvite'
import { Profile } from './routes/Profile'
import { NotFound } from './routes/NotFound'
import { Button, Notice, PageHeader, PermissionState } from './components/ui'

/*
 * Every screen below is fetched only when it is actually visited: a first-time visitor to the
 * marketing page never downloads Chat or Orchestrator, and a signed-in user never downloads the
 * marketing page. The two default screens (Command Map for a signed-in visit to "/", Landing for a
 * signed-out one) are fetched the same way, and App starts fetching whichever of the two this
 * visit is likely to want as soon as it mounts, so neither first paint waits long for it. The
 * small auth screens stay in the main bundle.
 */
const loadCommandMap = () => import('./routes/CommandMap')
const loadLanding = () => import('./routes/Landing')
const CommandMap = lazy(() => loadCommandMap().then((m) => ({ default: m.CommandMap })))
const Landing = lazy(() => loadLanding().then((m) => ({ default: m.Landing })))
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
const Connectors = lazy(() => import('./routes/Connectors').then((m) => ({ default: m.Connectors })))
const Members = lazy(() => import('./routes/Members').then((m) => ({ default: m.Members })))
const AuditLog = lazy(() => import('./routes/AuditLog').then((m) => ({ default: m.AuditLog })))
const Analytics = lazy(() => import('./routes/Analytics').then((m) => ({ default: m.Analytics })))
const Settings = lazy(() => import('./routes/Settings').then((m) => ({ default: m.Settings })))
const Setup = lazy(() => import('./routes/Setup').then((m) => ({ default: m.Setup })))

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
  ['/connectors', { permission: 'integration:read', screen: () => <Connectors /> }],
  ['/routing', { permission: 'provider:read', screen: () => <ModelRouting /> }],
  ['/members', { permission: 'member:read', screen: () => <Members /> }],
  ['/audit', { permission: 'audit:read', screen: () => <AuditLog /> }],
  ['/analytics', { permission: 'analytics:read', screen: () => <Analytics /> }],
  ['/settings', { permission: 'workspace:update', screen: () => <Settings /> }],
  ['/setup', { permission: 'workspace:update', screen: () => <Setup /> }],
  ['/profile', { permission: null, screen: () => <Profile /> }],
]

function find(routes: Array<[string, RouteMeta]>, path: string) {
  for (const [pattern, meta] of routes) {
    const params = match(pattern, path)
    if (params) return { meta, params, pattern }
  }
  return null
}

/**
 * The skip link, the navigation bar and the main landmark around a signed-in screen.
 *
 * It also counts what is waiting on the person (approvals they can decide, questions asked of
 * them) and puts the number in the tab title, so it shows on every screen, with the tab in the
 * background, and for a person with the navigation bar out of sight. See lib/attention.ts.
 */
// @find: Shell component, signed-in page frame, navbar and content
function Shell({ path, children }: { path: string; children: ReactNode }) {
  useAttention()
  return (
    <div>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <Navbar currentPath={path} />
      {/* Focusable from script and from the skip link only; keyed on the path so each screen
          starts fresh and fades in. The boundary sits inside it, so a screen that fails to draw
          leaves the navigation bar working, and the key clears the failure on the next screen. */}
      <main id="main" tabIndex={-1} key={path} className="route-enter">
        <ErrorBoundary>{children}</ErrorBoundary>
      </main>
    </div>
  )
}

/** A screen without the shell: the public pages, which have their own header and landmarks. */
// @find: PublicScreen, signed-out page frame
function PublicScreen({ children }: { children: ReactNode }) {
  return (
    <ErrorBoundary variant="page">
      <Suspense fallback={<div className="page" aria-busy="true" />}>{children}</Suspense>
    </ErrorBoundary>
  )
}

/**
 * Shown while a tab that opened without a token asks for one from the refresh cookie. Quiet at
 * "/", where a first-time visitor has no session and should not read about restoring one.
 */
// @find: restoring session screen, signing you back in
function RestoringSession({ quiet }: { quiet: boolean }) {
  return (
    <main id="main" tabIndex={-1} className="page" aria-busy="true">
      <p role="status" className={quiet ? 'visually-hidden' : 'muted'}>
        Restoring your session
      </p>
    </main>
  )
}

/** The session could not be restored because nothing reached the service. Signing in would fail too. */
// @find: session unreachable screen, retry connecting
function SessionUnreachable({ onRetry }: { onRetry: () => void }) {
  return (
    <main id="main" tabIndex={-1} className="page">
      <PageHeader eyebrow="Your session" title="Your session could not be restored" />
      <Notice tone="warning" live>
        {NETWORK_FAILURE}{' '}
        <Button variant="quiet" onClick={onRetry}>
          Retry
        </Button>
      </Notice>
    </main>
  )
}

/**
 * Where restoring the session from the refresh cookie stands. 'pending' until it has been tried
 * once in this page load; 'unreachable' after a network failure, until the person retries.
 */
type Restore = 'pending' | 'unreachable' | 'settled'

// @find: App component, route table, permission gate, sign-in redirect, tab title with waiting count
export function App() {
  const { path, search, hash, navigate } = useRouter()
  const signedIn = isSignedIn()
  // Read without insisting on a provider: a signed-out render (the public page's own tests) has
  // no cache to keep apart.
  const queryClient = useContext(QueryClientContext)

  // A session renewal can change the role, and with it every can() in the navigation and the
  // route guards below. Re-rendering on the renewal event shows the change straight away.
  const [, sessionChanged] = useReducer((version: number) => version + 1, 0)
  useEffect(() => {
    window.addEventListener(SESSION_EVENT, sessionChanged)
    return () => window.removeEventListener(SESSION_EVENT, sessionChanged)
  }, [])

  // The query cache holds whatever the last person was shown, under keys that do not name them.
  // When the session comes to belong to somebody else (a sign-in as another person, a demo role
  // switch, a new workspace) the cache is emptied as the session is saved, before the next screen
  // draws, so nothing of theirs appears even for a frame. A sign-out in another tab also signs
  // this one out: its cache goes, and a signed-in screen hands over to the sign-in form.
  useEffect(
    () =>
      watchSessionOwner((change) => {
        queryClient?.clear()
        if (change === 'signed-out-elsewhere' && find(PRIVATE, window.location.pathname)) {
          navigate('/sign-in?signedOut=1', { replace: true })
        }
      }),
    [queryClient, navigate],
  )

  // Starts fetching the default screen this visit is most likely to show, while the person is
  // still reading the current one. A failure here is reported when the screen is actually shown.
  useEffect(() => {
    const load = signedIn ? loadCommandMap : loadLanding
    load().catch(() => {})
  }, [signedIn])

  const publicRoute = find(PUBLIC, path)
  const privateRoute = find(PRIVATE, path)

  // An address that moved goes to its new home first, keeping the query and the #fragment, and
  // replacing the history entry so Back does not return to the old address and bounce again. The
  // new address then goes through the sign-in and permission checks like any other.
  const movedTo = redirectFor(path)
  useEffect(() => {
    if (movedTo) navigate(`${movedTo}${window.location.search}${window.location.hash}`, { replace: true })
  }, [movedTo, navigate])

  // A tab opened without a token (a new tab, a pasted link, a reload after the browser closed)
  // may still hold a valid refresh cookie. Before treating the visit as signed out, it asks once
  // per page load; a failure to reach the service is offered a retry rather than the sign-in
  // form, since signing in would fail the same way. At "/" it asks only if this browser has signed
  // in before, so a first-time visitor sees the home page at once. Once the tab holds a session
  // there is nothing left to restore, and a later sign-out goes straight to where it always did.
  const [restore, setRestore] = useState<Restore>(() => (signedIn ? 'settled' : 'pending'))
  if (signedIn && restore !== 'settled') setRestore('settled')
  const restoring =
    !signedIn && privateRoute !== null && !movedTo && restore === 'pending' && (path !== '/' || maySignInSilently())
  useEffect(() => {
    if (!restoring) return
    restoreSession().then((outcome) => setRestore(outcome === 'unreachable' ? 'unreachable' : 'settled'))
  }, [restoring])
  const unreachable = !signedIn && privateRoute !== null && restore === 'unreachable'

  // A signed-out visitor at the root sees the public page; at any other private screen they are
  // sent to sign in and returned afterwards, to the same filtered list or the same #item. The
  // redirect replaces the history entry, so Back does not bounce straight into it again.
  const redirectToSignIn = Boolean(!signedIn && privateRoute && path !== '/' && restore === 'settled')
  const query = search.toString()
  const here = `${path}${query ? `?${query}` : ''}${hash}`
  useEffect(() => {
    if (redirectToSignIn) navigate(`/sign-in?next=${encodeURIComponent(here)}`, { replace: true })
  }, [redirectToSignIn, navigate, here])

  // Page titles follow the screen, so browser history and tabs are readable. A signed-out visitor
  // at the root sees the public page, so it takes the public page's title, not the Command Map's.
  // Written in a layout effect: a detail screen names the tab after its record (useDocumentTitle)
  // in a passive effect, which runs after this one and so wins.
  const titlePattern =
    movedTo ?? (!signedIn && path === '/' ? '/home' : (publicRoute?.pattern ?? privateRoute?.pattern ?? ''))
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

  if (movedTo) return <div className="page" aria-busy="true" />
  if (restoring) return <RestoringSession quiet={path === '/'} />
  if (unreachable) return <SessionUnreachable onRetry={() => setRestore('pending')} />
  if (!signedIn && path === '/') {
    return (
      <PublicScreen>
        <Landing />
      </PublicScreen>
    )
  }
  if (publicRoute) return <PublicScreen>{publicRoute.meta.screen(publicRoute.params)}</PublicScreen>
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

/**
 * Writes the browser tab title for a route pattern. It lives outside App because it writes a
 * global; the router keeps it apart from the waiting count, which the signed-in shell adds.
 */
function showTitle(pattern: string): void {
  setPageTitle(titleFor(pattern) + ' · AI Workforce OS')
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
    '/connectors': 'Connectors',
    '/routing': 'Model routing',
    '/members': 'Members and roles',
    '/audit': 'Audit log',
    '/analytics': 'Analytics',
    '/settings': 'Workspace settings',
    '/setup': 'Set up your workspace',
    '/profile': 'Your profile',
  }
  return titles[pattern] ?? 'Not found'
}
