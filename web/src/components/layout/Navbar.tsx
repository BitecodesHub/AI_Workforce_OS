import { useEffect, useRef, useState } from 'react'
import { Brand } from './Brand'
import { IconButton } from '../ui'
import { can, clearSession, initials as initialsOf, profile } from '../../lib/session'
import { useApprovals } from '../../lib/queries'
import { useRouter } from '../../lib/router'

/*
 * The primary navigation.
 *
 * Six destinations in the capsule, no more: the brief caps it there, and the cap does real work
 * because a seventh always arrives and the one after that turns the capsule into a menu nobody
 * reads.
 *
 * The other nine screens are not unreachable, though - that was the mistake worth fixing. They
 * live behind the gear, grouped by what a person is trying to do, and the bell goes straight to
 * the approvals queue because an unread approval is the one thing somebody must not have to hunt
 * for.
 */

type NavItem = { label: string; href: string; needs?: string }

const NAV_ITEMS: readonly NavItem[] = [
  { label: 'Command Map', href: '/' },
  { label: 'Agents', href: '/agents' },
  { label: 'Tasks', href: '/tasks' },
  { label: 'Chat', href: '/chat', needs: 'chat:use' },
  { label: 'Knowledge', href: '/knowledge' },
  { label: 'Integrations', href: '/integrations' },
] as const

/*
 * Grouped, because a flat list of nine is a list nobody scans. Each item names the permission it
 * needs, and items the signed-in role cannot use are not offered - a menu entry that opens onto
 * "your role does not include this" is a dead end the menu itself created.
 */
const MENU_GROUPS = [
  {
    heading: 'Oversight',
    items: [
      { label: 'Approvals', href: '/approvals', note: 'Actions waiting on a decision', needs: 'approval:read' },
      { label: 'Audit log', href: '/audit', note: 'Everything that happened', needs: 'audit:read' },
      { label: 'Analytics', href: '/analytics', note: 'Activity, outcomes and spend', needs: 'analytics:read' },
    ],
  },
  {
    heading: 'Configuration',
    items: [
      { label: 'Model routing', href: '/routing', note: 'Providers, chains and budgets', needs: 'provider:read' },
      { label: 'Members and roles', href: '/members', note: 'Who can do what', needs: 'member:read' },
    ],
  },
] as const

export function Navbar({ currentPath = '/', glass = false }: { currentPath?: string; glass?: boolean }) {
  // Who is signed in comes from the session, never from a default. A hard-coded name here made
  // every demo account appear to be the same person.
  const me = profile()
  const name = me?.displayName ?? 'Signed in'
  const email = me?.email ?? ''
  const role = me?.role ?? null
  const approvals = useApprovals()
  const canReadApprovals = can('approval:read')
  const unreadCount = canReadApprovals ? (approvals.data?.length ?? 0) : 0
  const { navigate } = useRouter()
  const [menuOpen, setMenuOpen] = useState(false)
  const [moreOpen, setMoreOpen] = useState(false)
  const [accountOpen, setAccountOpen] = useState(false)
  const moreRef = useRef<HTMLDivElement>(null)
  const accountRef = useRef<HTMLDivElement>(null)
  const visibleNavItems = NAV_ITEMS.filter((item) => !item.needs || can(item.needs))

  /*
   * Signing out clears the refresh cookie on the server rather than only forgetting it here.
   * A client-side sign-out leaves the session live for anybody holding the cookie, which is the
   * opposite of what the person just asked for.
   */
  async function signOut() {
    setAccountOpen(false)
    try {
      await fetch('/api/auth/sign-out', { method: 'POST', credentials: 'include' })
    } catch {
      // The session is ended locally regardless: a network failure must not strand somebody
      // on a screen they have asked to leave.
    }
    clearSession()
    navigate('/sign-in?signedOut=1')
  }

  // A menu that only closes by clicking the button again is a menu people leave open by
  // accident and then click through.
  useEffect(() => {
    if (!moreOpen && !accountOpen) return
    function onPointerDown(event: MouseEvent) {
      const target = event.target as Node
      if (moreRef.current && !moreRef.current.contains(target)) setMoreOpen(false)
      if (accountRef.current && !accountRef.current.contains(target)) setAccountOpen(false)
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setMoreOpen(false)
        setAccountOpen(false)
      }
    }
    document.addEventListener('mousedown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [moreOpen, accountOpen])

  return (
    <nav className={`navbar ${glass ? 'navbar-glass' : ''}`.trim()} aria-label="Primary">
      <Brand />

      <div className="nav-capsule" data-open={menuOpen} id="primary-navigation">
        {visibleNavItems.map((item) => (
          <a
            key={item.href}
            className="nav-pill"
            href={item.href}
            // aria-current is what tells a screen reader which page this is. The blue pill
            // conveys it to sighted users only.
            aria-current={item.href === currentPath ? 'page' : undefined}
          >
            {item.label}
          </a>
        ))}
      </div>

      <div className="navbar-right">
        {/* A link, not a button: an unread approval should be one click away and openable in a
            new tab like anything else. */}
        {canReadApprovals && (
          <a
            className="icon-button"
            href="/approvals"
            aria-label={unreadCount > 0 ? `Approvals, ${unreadCount} waiting` : 'Approvals'}
            title={unreadCount > 0 ? `${unreadCount} approvals waiting` : 'Approvals'}
          >
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path
                d="M8 1.8a3.6 3.6 0 0 0-3.6 3.6v2.3L3.2 11h9.6l-1.2-3.3V5.4A3.6 3.6 0 0 0 8 1.8Z"
                stroke="currentColor"
                strokeWidth="1.3"
                strokeLinejoin="round"
              />
              <path d="M6.4 12.6a1.7 1.7 0 0 0 3.2 0" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
            </svg>
            {unreadCount > 0 && (
              <span className="bell-badge" aria-hidden="true">
                {unreadCount > 99 ? '99+' : unreadCount}
              </span>
            )}
          </a>
        )}

        <div className="more-menu" ref={moreRef}>
          <IconButton
            label="Settings and more"
            aria-expanded={moreOpen}
            aria-haspopup="menu"
            onClick={() => {
              setAccountOpen(false)
              setMoreOpen((open) => !open)
            }}
          >
            {/* Drawn with a toothed outer ring rather than radiating spokes: at 16px a spoked
                gear reads as a sun, which is exactly what it looked like before. */}
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path
                d="M6.6 1.9h2.8l.35 1.6 1.25.72 1.55-.5 1.4 2.42-1.2 1.1v1.44l1.2 1.1-1.4 2.42-1.55-.5-1.25.72-.35 1.6H6.6l-.35-1.6-1.25-.72-1.55.5-1.4-2.42 1.2-1.1V7.24l-1.2-1.1 1.4-2.42 1.55.5 1.25-.72Z"
                stroke="currentColor"
                strokeWidth="1.2"
                strokeLinejoin="round"
              />
              <circle cx="8" cy="8" r="2" stroke="currentColor" strokeWidth="1.2" />
            </svg>
          </IconButton>

          {moreOpen && (
            <div className="menu-panel" role="menu" aria-label="Settings and more">
              {MENU_GROUPS.map((group) => {
                const allowed = group.items.filter((item) => can(item.needs))
                if (allowed.length === 0) return null
                return (
                <div key={group.heading} className="menu-group">
                  <p className="eyebrow menu-heading">{group.heading}</p>
                  {allowed.map((item) => (
                    <a
                      key={item.href}
                      className="menu-item"
                      href={item.href}
                      role="menuitem"
                      aria-current={item.href === currentPath ? 'page' : undefined}
                      onClick={() => setMoreOpen(false)}
                    >
                      <span className="menu-item-label">{item.label}</span>
                      {/* One line of explanation each, so the destination is obvious before
                          somebody has to visit it to find out. */}
                      <span className="menu-item-note">{item.note}</span>
                    </a>
                  ))}
                </div>
                )
              })}
            </div>
          )}
        </div>

        <div className="more-menu" ref={accountRef}>
          <button
            type="button"
            className="avatar"
            aria-label="Your account"
            aria-expanded={accountOpen}
            aria-haspopup="menu"
            onClick={() => {
              setMoreOpen(false)
              setAccountOpen((open) => !open)
            }}
          >
            {initialsOf(name)}
          </button>

          {accountOpen && (
            <div className="menu-panel" role="menu" aria-label="Your account">
              {/* Who is signed in, stated plainly. On a platform where agents act for people,
                  "which account am I" is a question worth answering without a click. */}
              <div className="menu-identity">
                <span className="menu-item-label">{name}</span>
                <span className="menu-item-note">{email}</span>
                {role && (
                  <span className="tag tag-blue" style={{ marginTop: 'var(--space-3)' }}>
                    {role}
                  </span>
                )}
              </div>

              <div className="menu-group">
                <a className="menu-item" href="/profile" role="menuitem" onClick={() => setAccountOpen(false)}>
                  <span className="menu-item-label">Your profile</span>
                  <span className="menu-item-note">Your role and exactly what it allows</span>
                </a>
                {can('member:read') && (
                  <a className="menu-item" href="/members" role="menuitem" onClick={() => setAccountOpen(false)}>
                    <span className="menu-item-label">Members and roles</span>
                    <span className="menu-item-note">Who else is in this workspace</span>
                  </a>
                )}
              </div>

              <div className="menu-group">
                <button
                  type="button"
                  className="menu-item menu-item-danger"
                  role="menuitem"
                  onClick={signOut}
                >
                  <span className="menu-item-label">Sign out</span>
                  <span className="menu-item-note">Ends this session on this device</span>
                </button>
              </div>
            </div>
          )}
        </div>

        <IconButton
          label={menuOpen ? 'Close menu' : 'Open menu'}
          aria-expanded={menuOpen}
          aria-controls="primary-navigation"
          className="nav-toggle"
          onClick={() => setMenuOpen((open) => !open)}
        >
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M2 4h12M2 8h12M2 12h12" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
          </svg>
        </IconButton>
      </div>
    </nav>
  )
}
