// @find: team feed, hand-off conversation, team chat, newest at the bottom, show earlier, new updates announced, screen reader live region, feed announcer, asked, handed it to, waiting to approve, TeamFeed, FeedAnnouncer, Team room
// @what: The team room's conversation: who asked, started, handed over, waited, decided and finished, as a team chat with avatars, newest at the bottom; and the polite announcer of new lines.
// @flow: Rendered by routes/TeamRoom.tsx with the state of useTeamRoomFeed; lines are worded by feedLine in lib/teamRoomQueries.
import { useEffect, useLayoutEffect, useRef } from 'react'
import { Button, Time } from '../ui'
import { AgentAvatar } from '../ui/AgentAvatar'
import { describeApiError } from '../../lib/api'
import { feedLine, feedLink, feedSpeaker } from '../../lib/teamRoomQueries'
import type { FeedEvent, FeedState, PersonNamer } from '../../lib/teamRoomQueries'
import { can, initials, profile } from '../../lib/session'

/** How close to the bottom (pixels) still counts as reading the newest lines. */
const NEAR_BOTTOM_PX = 120

/** The circle beside a line: the agent's avatar, the person's initials, or a clock for a schedule. */
function Speaker({ event, nameOf }: { event: FeedEvent; nameOf: PersonNamer }) {
  const speaker = feedSpeaker(event)
  if (speaker === 'agent') {
    return (
      <AgentAvatar
        name={event.agentName ?? 'Agent'}
        category={event.agentCategory}
        fallback={event.agentFallback}
        size="sm"
      />
    )
  }
  if (speaker === 'schedule') {
    return (
      <span className="avatar avatar-sm tr-line-schedule" aria-hidden="true">
        <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6">
          <circle cx="8" cy="8" r="6" />
          <path d="M8 4.5V8l2.5 1.5" strokeLinecap="round" />
        </svg>
      </span>
    )
  }
  return (
    <span className="avatar avatar-sm tr-line-person" aria-hidden="true">
      {personInitials(nameOf(event.personId))}
    </span>
  )
}

/**
 * The letters in a person's circle: their initials, the viewer's own initials when the line names
 * them "You" (never "YO"), or nothing for "Someone". Reads the session profile.
 */
export function personInitials(name: string): string {
  if (name === 'Someone') return ''
  if (name === 'You') {
    const own = profile()?.displayName?.trim()
    return own ? initials(own) : ''
  }
  return initials(name)
}

/**
 * The polite announcement for lines a poll just added: the newest of them (not the last line on
 * screen, which an event that arrived late does not displace), and how many came with it.
 */
export function announcement(state: FeedState | undefined, nameOf: PersonNamer): string {
  if (!state || state.added <= 0 || !state.latest) return ''
  const newest = feedLine(state.latest, nameOf).text
  return state.added === 1 ? newest : `${state.added} new updates. Latest: ${newest}`
}

// @find: FeedAnnouncer, polite live region for new conversation lines
/**
 * The hidden status region that announces new conversation lines. The page keeps it mounted from
 * the start, while the conversation is still loading or empty, because a screen reader announces a
 * change inside a live region it already knows, not one inserted with its text already in it.
 * Never announces the first page, an earlier page, or a presence change.
 */
export function FeedAnnouncer({ state, nameOf }: { state: FeedState | undefined; nameOf: PersonNamer }) {
  return (
    <div className="visually-hidden tr-feed-announcer" role="status" aria-live="polite" aria-atomic="true">
      {announcement(state, nameOf)}
    </div>
  )
}

// @find: TeamFeed component, team conversation list, show earlier, announce new lines
/**
 * The conversation, oldest at the top and newest at the bottom, in its own scroll box. While the
 * reader is at the bottom, new lines keep it there; once they scroll up to read, nothing moves
 * under them. "Show earlier" loads the page before the oldest line and keeps the reader's place;
 * coming back down to the newest line calls onReachBottom, so the page may let old lines go again.
 * New lines are announced by FeedAnnouncer, which the page renders.
 */
export function TeamFeed({
  state,
  nameOf,
  onLoadOlder,
  loadingOlder,
  olderError,
  onReachBottom,
}: {
  state: FeedState
  nameOf: PersonNamer
  onLoadOlder: () => void
  loadingOlder: boolean
  olderError: unknown
  /** The reader scrolled back down to the newest line. */
  onReachBottom?: (() => void) | undefined
}) {
  const box = useRef<HTMLDivElement>(null)
  const nearBottom = useRef(true)
  const before = useRef<{ height: number; top: number } | null>(null)
  const firstId = state.events[0]?.id ?? null
  const lastId = state.events[state.events.length - 1]?.id ?? null
  // Null until the first layout, so the first page opens scrolled to its newest line.
  const previousFirst = useRef<string | null>(null)
  const previousLast = useRef<string | null>(null)

  // Kept in a ref so the scroll listener is added once and still calls the latest callback.
  const reachedBottom = useRef(onReachBottom)
  useEffect(() => {
    reachedBottom.current = onReachBottom
  }, [onReachBottom])

  useEffect(() => {
    const element = box.current
    if (!element) return
    const onScroll = () => {
      const atBottom = element.scrollHeight - element.scrollTop - element.clientHeight < NEAR_BOTTOM_PX
      if (atBottom && !nearBottom.current) reachedBottom.current?.()
      nearBottom.current = atBottom
    }
    element.addEventListener('scroll', onScroll, { passive: true })
    return () => element.removeEventListener('scroll', onScroll)
  }, [])

  useLayoutEffect(() => {
    const element = box.current
    if (!element) return
    const grewAtTop = firstId !== previousFirst.current && previousFirst.current !== null
    const grewAtBottom = lastId !== previousLast.current
    if (grewAtTop && before.current && !grewAtBottom) {
      // Earlier lines arrived above the reader: keep what they were reading where it was.
      element.scrollTop = before.current.top + (element.scrollHeight - before.current.height)
    } else if (grewAtBottom && (nearBottom.current || previousLast.current === null)) {
      element.scrollTop = element.scrollHeight
    }
    before.current = null
    previousFirst.current = firstId
    previousLast.current = lastId
  }, [firstId, lastId])

  const loadOlder = () => {
    const element = box.current
    if (element) before.current = { height: element.scrollHeight, top: element.scrollTop }
    onLoadOlder()
  }

  return (
    <div className="tr-feed-body">
      <div
        ref={box}
        className="tr-feed-scroll"
        tabIndex={0}
        role="region"
        aria-label="Team conversation, newest at the bottom"
      >
        {state.hasOlder ? (
          <div className="tr-feed-older">
            <Button variant="quiet" onClick={loadOlder} loading={loadingOlder}>
              Show earlier
            </Button>
            {olderError != null && (
              <p className="caption" role="alert">
                Earlier updates could not be loaded. {describeApiError(olderError)}
              </p>
            )}
          </div>
        ) : (
          <p className="tr-feed-start caption">This is where the team's conversation begins.</p>
        )}
        <ol className="tr-feed-list">
          {state.events.map((event) => {
            const line = feedLine(event, nameOf)
            const link = feedLink(event, can)
            return (
              <li key={event.id} className="tr-line" data-kind={event.kind} data-speaker={feedSpeaker(event)}>
                <Speaker event={event} nameOf={nameOf} />
                <div className="tr-line-bubble">
                  <p className="tr-line-text">{line.text}</p>
                  {line.detail && <p className="tr-line-detail caption">{line.detail}</p>}
                  <p className="tr-line-meta caption">
                    <Time iso={event.at} />
                    {link && (
                      <a className="link" href={link.href}>
                        {link.label}
                      </a>
                    )}
                  </p>
                </div>
              </li>
            )
          })}
        </ol>
      </div>
    </div>
  )
}
