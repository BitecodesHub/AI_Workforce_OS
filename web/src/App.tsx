import type { ReactElement } from 'react'
import { useEffect } from 'react'
import { Navbar } from './components/layout/Navbar'
import { match, useRouter } from './lib/router'
import { isSignedIn, can } from './lib/session'
import { CommandMap } from './routes/CommandMap'
import { Agents } from './routes/Agents'
import { AgentDetail } from './routes/AgentDetail'
import { ModelRouting } from './routes/ModelRouting'
import { Tasks } from './routes/Tasks'
import { RunDetail } from './routes/RunDetail'
import { Approvals } from './routes/Approvals'
import { Chat } from './routes/Chat'
import { Knowledge } from './routes/Knowledge'
import { SourceDetail } from './routes/SourceDetail'
import { Integrations } from './routes/Integrations'
import { Members } from './routes/Members'
import { AuditLog } from './routes/AuditLog'
import { Analytics } from './routes/Analytics'
import { SignIn } from './routes/SignIn'
import { CreateWorkspace } from './routes/CreateWorkspace'
import { Landing } from './routes/Landing'
import { Profile } from './routes/Profile'
import { NotFound } from './routes/NotFound'
import { PermissionState } from './components/ui'

/*
 * The application shell and its routes.
 *
 * One table lists every destination. Screens that need a signed-in session are behind a guard
 * that sends a signed-out visitor to sign in and brings them back afterwards, so a bookmarked
 * link to a run works rather than showing an empty screen full of failed requests.
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
]

const PRIVATE: Array<[string, RouteMeta]> = [
  ['/', { permission: null, screen: () => <CommandMap /> }],
  ['/agents', { permission: 'agent:read', screen: () => <Agents /> }],
  ['/agents/:id', { permission: 'agent:read', screen: (p) => <AgentDetail id={p.id!} /> }],
  ['/tasks', { permission: 'task:read', screen: () => <Tasks /> }],
  ['/runs/:id', { permission: 'run:read', screen: (p) => <RunDetail id={p.id!} /> }],
  ['/approvals', { permission: 'approval:read', screen: () => <Approvals /> }],
  ['/chat', { permission: 'chat:use', screen: () => <Chat /> }],
  ['/knowledge', { permission: 'source:read', screen: () => <Knowledge /> }],
  ['/knowledge/:id', { permission: 'source:read', screen: (p) => <SourceDetail id={p.id!} /> }],
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

export function App() {
  const { path, navigate } = useRouter()
  const signedIn = isSignedIn()

  const publicRoute = find(PUBLIC, path)
  const privateRoute = find(PRIVATE, path)

  // A signed-out visitor at the root sees the public page; at any other private screen they are
  // sent to sign in and returned afterwards.
  const redirectToSignIn = !signedIn && privateRoute && path !== '/'
  useEffect(() => {
    if (redirectToSignIn) navigate(`/sign-in?next=${encodeURIComponent(path)}`)
  }, [redirectToSignIn, navigate, path])

  // Page titles follow the screen, so browser history and tabs are readable.
  useEffect(() => {
    document.title = titleFor(publicRoute?.pattern ?? privateRoute?.pattern ?? '') + ' · AI Workforce OS'
  }, [publicRoute, privateRoute])

  if (!signedIn && path === '/') return <Landing />
  if (publicRoute) return publicRoute.meta.screen(publicRoute.params)
  if (redirectToSignIn) return <div className="page" aria-busy="true" />

  const current = privateRoute
  if (!current) return <NotFound />

  const { meta, params, pattern } = current
  if (meta.permission && !can(meta.permission)) {
    return (
      <div>
        <a className="skip-link" href="#main">
          Skip to content
        </a>
        <Navbar currentPath={path} />
        <main id="main" key={path} className="route-enter">
          <PermissionState permission={meta.permission} what={titleFor(pattern).toLowerCase()} />
        </main>
      </div>
    )
  }

  return (
    <div>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <Navbar currentPath={path} />
      <main id="main" key={path} className="route-enter">
        {meta.screen(params)}
      </main>
    </div>
  )
}

function titleFor(pattern: string): string {
  const titles: Record<string, string> = {
    '/': 'Command Map',
    '/home': 'A governed AI workforce',
    '/sign-in': 'Sign in',
    '/create-workspace': 'Create a workspace',
    '/agents': 'Agents',
    '/agents/:id': 'Agent',
    '/tasks': 'Tasks',
    '/runs/:id': 'Run',
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
