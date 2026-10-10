// @find: run ratings, who rated a run, answer feedback on a run, rating list, members who rated, RunRatings
// @what: Lists the ratings people gave to a run, with member names.
// @flow: Shown in run detail; uses useRunRatings and useMemberNames
import { Card, Eyebrow, Tag, Time } from '../ui'
import { useMemberNames } from '../../lib/queries'
import { useRunRatings } from '../../lib/insightsQueries'
import { can } from '../../lib/session'

/*
 * What people said of this run's answers: a thumbs up or down for each, with the reason when the
 * person gave one. A reviewer who followed a thumbs down from Analytics lands here and reads why,
 * beside the steps and tools behind the answer.
 *
 * Shown only when there is at least one rating, so a run nobody rated has no empty card, and a
 * failed read says nothing rather than a guess. A rater is named only to someone who can see the
 * member list; everyone else sees "A member of the workspace".
 */
// @find: RunRatings, ratings on a run
export function RunRatings({ runId }: { runId: string }) {
  const ratings = useRunRatings(runId, { enabled: can('run:read') })
  const members = useMemberNames({ enabled: can('member:read') })

  const rows = ratings.data ?? []
  if (rows.length === 0) return null

  return (
    <Card as="section">
      <Eyebrow as="h2">Ratings</Eyebrow>
      <ul className="stack" style={{ gap: 'var(--space-4)', listStyle: 'none', margin: 0, padding: 0 }}>
        {rows.map((row) => {
          const who = (row.userId && members[row.userId]?.displayName) || 'A member of the workspace'
          return (
            <li key={`${row.messageId}-${row.userId ?? 'unknown'}`}>
              <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap', alignItems: 'center' }}>
                <Tag tone={row.rating === 1 ? 'success' : 'warning'} withDot>
                  {row.rating === 1 ? 'Helpful' : 'Not helpful'}
                </Tag>
                <span className="caption">
                  {who}
                  {row.updatedAt && (
                    <>
                      {' '}
                      <Time iso={row.updatedAt} />
                    </>
                  )}
                </span>
              </div>
              {row.reason && (
                <p style={{ margin: 'var(--space-2) 0 0', overflowWrap: 'anywhere' }}>
                  <span className="visually-hidden">Reason: </span>
                  {row.reason}
                </p>
              )}
            </li>
          )
        })}
      </ul>
    </Card>
  )
}
