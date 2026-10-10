// @find: setup banner, finish setting up your workspace, continue setup, review setup, setup progress, remaining steps, owner admin banner, SetupBanner, Command Map banner, onboarding reminder
// @what: Banner telling an owner or admin how many setup steps remain, linking to the Setup page.
// @flow: Shown on the Command Map and workspace settings; reads useSetupSteps and canSetUp from lib/setupQueries.
import { Notice } from '../ui'
import { canSetUp, useSetupSteps } from '../../lib/setupQueries'

// @find: setup progress banner, continue setup link, steps remaining
/*
 * The line that sends an owner or admin to "Set up your workspace" while something essential is
 * left: on the Command Map and the workspace settings. It says nothing until every fact is known,
 * and nothing at all once setup is done; the optional steps never hold it up.
 */
export function SetupBanner({ always = false }: { always?: boolean }) {
  const allowed = canSetUp()
  const { summary } = useSetupSteps({ enabled: allowed })
  if (!allowed) return null
  if (!summary.complete && summary.remaining > 0) {
    return (
      <Notice tone="info">
        <span>
          Finish setting up your workspace: {summary.done} of {summary.total} done,{' '}
          {summary.remaining} {summary.remaining === 1 ? 'step needs' : 'steps need'} attention.{' '}
          <a className="link" href="/setup">
            Continue setup
          </a>
        </span>
      </Notice>
    )
  }
  if (!always || !summary.complete) return null
  return (
    <p className="caption" style={{ margin: 0 }}>
      Your workspace is set up.{' '}
      <a className="link" href="/setup">
        Review setup
      </a>
    </p>
  )
}
