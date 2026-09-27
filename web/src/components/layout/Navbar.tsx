import { useEffect, useRef, useState } from 'react'
import type { FocusEvent as ReactFocusEvent, MouseEvent as ReactMouseEvent } from 'react'
import { Brand } from './Brand'
import { IconButton } from '../ui'
import { can, clearSession, initials as initialsOf, profile } from '../../lib/session'
import { roleLabel } from '../../lib/labels'
import { isGuideHidden, setGuideHidden } from '../../lib/onboarding'
import { useApprovals } from '../../lib/queries'
import { useRouter } from '../../lib/router'

/*
 * The primary navigation.
 *
 * Six destinations in the capsule, no more: the brief caps it there, and the cap does real work
 * because a seventh always arrives and the one after that turns the capsule into a menu nobody
 * reads.
 *
 * The other screens are not unreachable, though - that was the mistake worth fixing. They live
 * behind the gear, grouped by what a person is trying to do, and the bell goes straight to the
 * approvals queue because an unread approval is the one thing somebody must not have to hunt for.
 *
 * The gear and account panels are disclosures, not application menus: they hold ordinary links
 * reached with Tab, so they do not announce arrow-key navigation they do not have.
 */

/** `needs` lists every permission the screen needs to be of any use; all of them must be held. */
type NavItem = { label: string; href: string; needs?: readonly string[] }

const NAV_ITEMS: readonly NavItem[] = [
  { label: 'Command Map', href: '/' },
  { label: 'Agents', href: '/agents', needs: ['agent:read'] },
  { label: 'Tasks', href: '/tasks', needs: ['task:read'] },
  { label: 'Runs', href: '/runs', needs: ['run:read'] },
  // Chat opens with chat:use, but the search behind it needs knowledge:query; without that the
  // screen can only say the role does not allow it.
  { label: 'Chat', href: '/chat', needs: ['chat:use', 'knowledge:query'] },
  { label: 'Knowledge', href: '/knowledge', needs: ['knowledge:read'] },
] as const

/*
 * Grouped, because a flat list is a list nobody scans. Each item names the permission it needs,
 * and items the signed-in role cannot use are not offered - a menu entry that opens onto "your
 * role does not include this" is a dead end the menu itself created. Each note says only what
 * the screen actually shows.
 */
const MENU_GROUPS = [
  {
    heading: 'Oversight',
    items: [
      { label: 'Approvals', href: '/approvals', note: 'Actions waiting on a decision', needs: 'approval:read' },
      { label: 'Audit log', href: '/audit', note: 'Approvals and run outcomes', needs: 'audit:read' },
      { label: 'Analytics', href: '/analytics', note: 'Activity and outcomes from the audit log', needs: 'analytics:read' },
    ],
  },
  {
    heading: 'Configuration',
    items: [
      { label: 'Model routing', href: '/routing', note: 'Providers, models and the routing chain', needs: 'provider:read' },
      { label: 'Integrations', href: '/integrations', note: 'Tool servers agents act through', needs: 'integration:read' },
      { label: 'Members and roles', href: '/members', note: 'Who can do what', needs: 'member:read' },
    ],
  },
] as const

const SETTINGS_PANEL_ID = 'settings-panel'
const ACCOUNT_PANEL_ID = 'account-panel'

/**
 * Whether a destination holds the current page: the page itself or anything under it, so an
 * agent's own page still lights up Agents. The Command Map at '/' matches only itself.
 */
function holds(href: string, currentPath: string): boolean {
  if (href === '/') return currentPath === '/'
  return currentPath === href || currentPath.startsWith(`${href}/`)
}

export function Navbar({ currentPath = '/', glass = false }: { currentPath?: string; glass?: boolean }) {
  // Who is signed in comes from the session, never from a default. A hard-coded name here made
  // every demo account appear to be the same person.
  const me = profile()
  const name = me?.displayName ?? 'Signed in'
  const email = me?.email ?? ''
  const role = me?.role ?? null
  const canReadApprovals = can('approval:read')
  // Not requested at all for a role that cannot read approvals, rather than asked for and refused.
  const approvals = useApprovals({ enabled: canReadApprovals })
  const unreadCount = canReadApprovals ? (approvals.data?.length ?? 0) : 0
  const { navigate } = useRouter()
  const [menuOpen, setMenuOpen] = useState(false)
  const [moreOpen, setMoreOpen] = useState(false)
  const [accountOpen, setAccountOpen] = useState(false)
  const navRef = useRef<HTMLElement>(null)
  const capsuleRef = useRef<HTMLDivElement>(null)
  const moreRef = useRef<HTMLDivElement>(null)
  const accountRef = useRef<HTMLDivElement>(null)
  const visibleNavItems = NAV_ITEMS.filter((item) => (item.needs ?? []).every((permission) => can(permission)))
  const menuGroups = MENU_GROUPS.map((group) => ({
    heading: group.heading,
    items: group.items.filter((item) => can(item.needs)),
  })).filter((group) => group.items.length > 0)
  const settingsHoldsPage = menuGroups.some((group) => group.items.some((item) => holds(item.href, currentPath)))
  // Read when the account panel is drawn, so it reflects a guide hidden since the last visit.
  const guideHidden = accountOpen && me?.userId ? isGuideHidden(me.userId) : false

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

  /** Brings the getting-started guide back and goes to the Command Map, where it lives. */
  function showGuide(event: ReactMouseEvent<HTMLAnchorElement>) {
    setAccountOpen(false)
    if (me?.userId) setGuideHidden(me.userId, false)
    if (currentPath === '/') {
      // Already there: the guide re-reads its state on its own, so only bring it into view
      // rather than adding a second history entry for the same page. The link closes with the
      // panel, so focus goes to the page heading just above the guide instead of the document.
      event.preventDefault()
      window.scrollTo({ top: 0 })
      document.querySelector<HTMLElement>('main h1')?.focus({ preventScroll: true })
    }
  }

  /** Closes a panel when focus moves to something outside it, as Tab past its last link does. */
  function closeOnFocusOut(close: () => void) {
    return (event: ReactFocusEvent<HTMLDivElement>) => {
      const next = event.relatedTarget
      // A null target is a click on something that cannot take focus; the outside-click handler
      // below deals with that, and closing here would swallow clicks in Safari.
      if (next instanceof Node && !event.currentTarget.contains(next)) close()
    }
  }

  // A menu that only closes by clicking the button again is a menu people leave open by
  // accident and then click through. Escape closes whichever panel is open and puts focus back
  // on the button that opened it, so a keyboard user is not dropped at the top of the page.
  useEffect(() => {
    if (!menuOpen && !moreOpen && !accountOpen) return
    const toggle = () => navRef.current?.querySelector<HTMLElement>('.nav-toggle') ?? null
    const triggerOf = (wrapper: HTMLDivElement | null) =>
      wrapper?.querySelector<HTMLElement>(':scope > button') ?? null

    function onPointerDown(event: MouseEvent) {
      const target = event.target as Node
      if (moreRef.current && !moreRef.current.contains(target)) setMoreOpen(false)
      if (accountRef.current && !accountRef.current.contains(target)) setAccountOpen(false)
      if (!capsuleRef.current?.contains(target) && !toggle()?.contains(target)) setMenuOpen(false)
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== 'Escape') return
      const trigger = moreOpen
        ? triggerOf(moreRef.current)
        : accountOpen
          ? triggerOf(accountRef.current)
          : menuOpen
            ? toggle()
            : null
      setMoreOpen(false)
      setAccountOpen(false)
      setMenuOpen(false)
      trigger?.focus()
    }
    document.addEventListener('mousedown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [menuOpen, moreOpen, accountOpen])

  return (
    <nav ref={navRef} className={`navbar ${glass ? 'navbar-glass' : ''}`.trim()} aria-label="Primary">
      <Brand />

      <div className="nav-capsule" data-open={menuOpen} id="primary-navigation" ref={capsuleRef}>
        {visibleNavItems.map((item) => (
          <a
            key={item.href}
            className="nav-pill"
            href={item.href}
            // aria-current is what tells a screen reader which page this is. The blue pill
            // conveys it to sighted users only.
            aria-current={holds(item.href, currentPath) ? 'page' : undefined}
            // On a phone the capsule is a dropdown; choosing a destination closes it rather than
            // leaving it open over the new page.
            onClick={() => setMenuOpen(false)}
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
            title={
              unreadCount > 0 ? `${unreadCount} ${unreadCount === 1 ? 'approval' : 'approvals'} waiting` : 'Approvals'
            }
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

        {menuGroups.length > 0 && (
          <div className="more-menu" ref={moreRef} onBlur={closeOnFocusOut(() => setMoreOpen(false))}>
            <IconButton
              label="Settings and more"
              aria-expanded={moreOpen}
              aria-controls={moreOpen ? SETTINGS_PANEL_ID : undefined}
              // Lit like a pill when the current page is one of its destinations, so a person on
              // Model routing can still see where they are.
              data-active={settingsHoldsPage ? 'true' : undefined}
              onClick={() => {
                setAccountOpen(false)
                setMenuOpen(false)
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
              <div className="menu-panel" id={SETTINGS_PANEL_ID} role="group" aria-label="Settings and more">
                {menuGroups.map((group) => (
                  <div key={group.heading} className="menu-group">
                    <p className="eyebrow menu-heading">{group.heading}</p>
                    {group.items.map((item) => (
                      <a
                        key={item.href}
                        className="menu-item"
                        href={item.href}
                        aria-current={holds(item.href, currentPath) ? 'page' : undefined}
                        onClick={() => setMoreOpen(false)}
                      >
                        <span className="menu-item-label">{item.label}</span>
                        {/* One line of explanation each, so the destination is obvious before
                            somebody has to visit it to find out. */}
                        <span className="menu-item-note">{item.note}</span>
                      </a>
                    ))}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        <div className="more-menu" ref={accountRef} onBlur={closeOnFocusOut(() => setAccountOpen(false))}>
          <button
            type="button"
            className="avatar"
            aria-label="Your account"
            aria-expanded={accountOpen}
            aria-controls={accountOpen ? ACCOUNT_PANEL_ID : undefined}
            data-active={currentPath === '/profile' ? 'true' : undefined}
            onClick={() => {
              setMoreOpen(false)
              setMenuOpen(false)
              setAccountOpen((open) => !open)
            }}
          >
            {initialsOf(name)}
          </button>

          {accountOpen && (
            <div className="menu-panel" id={ACCOUNT_PANEL_ID} role="group" aria-label="Your account">
              {/* Who is signed in, stated plainly. On a platform where agents act for people,
                  "which account am I" is a question worth answering without a click. */}
              <div className="menu-identity">
                <span className="menu-item-label">{name}</span>
                <span className="menu-item-note">{email}</span>
                {role && (
                  <span className="tag tag-blue" style={{ marginTop: 'var(--space-3)' }}>
                    {roleLabel(role)}
                  </span>
                )}
              </div>

              <div className="menu-group">
                <a
                  className="menu-item"
                  href="/profile"
                  aria-current={currentPath === '/profile' ? 'page' : undefined}
                  onClick={() => setAccountOpen(false)}
                >
                  <span className="menu-item-label">Your profile</span>
                  <span className="menu-item-note">Your role and exactly what it allows</span>
                </a>
                {guideHidden && (
                  <a className="menu-item" href="/" onClick={showGuide}>
                    <span className="menu-item-label">Show getting started</span>
                    <span className="menu-item-note">The guide on the Command Map</span>
                  </a>
                )}
              </div>

              <div className="menu-group">
                <button type="button" className="menu-item menu-item-danger" onClick={signOut}>
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
          onClick={() => {
            setMoreOpen(false)
            setAccountOpen(false)
            setMenuOpen((open) => !open)
          }}
        >
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M2 4h12M2 8h12M2 12h12" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
          </svg>
        </IconButton>
      </div>
    </nav>
  )
}
