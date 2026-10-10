// @find: browser notifications, notification permission, enable notifications, desktop alerts, settings card, opt in, BrowserNotificationsCard, Settings page
// @what: The settings card where a person opts in to browser notifications, kept in this browser.
// @flow: Uses lib/attention helpers; rendered on the Settings page
import { useState } from 'react'
import { Card, Eyebrow } from '../ui'
import {
  enableBrowserNotifications,
  notificationPermission,
  setBrowserNotifications,
  useBrowserNotifications,
} from '../../lib/attention'
import { profile } from '../../lib/session'

/**
 * The person's own opt-in to browser notifications, kept in this browser.
 *
 * Shown on the profile, which everybody can open, as well as on the workspace settings: it is not a
 * workspace setting, and an employee - who cannot open the settings - is asked questions by agents
 * too (seen 8 Oct 2026: the only switch lived on a page employees are refused).
 */
// @find: browser notifications card component, turn on alerts
export function BrowserNotificationsCard() {
  const userId = profile()?.userId ?? ''
  const on = useBrowserNotifications(userId)
  const [note, setNote] = useState<string | null>(null)
  const permission = notificationPermission()

  async function change(next: boolean) {
    setNote(null)
    if (!next) {
      setBrowserNotifications(userId, false)
      return
    }
    // The browser is asked for permission now, because the person just asked for notifications.
    const outcome = await enableBrowserNotifications(userId)
    if (outcome === 'denied') {
      setNote(
        'Your browser did not allow notifications for this site. Allow them in the browser’s site settings, then turn this on again.',
      )
    } else if (outcome === 'unsupported') {
      setNote('This browser does not support notifications.')
    } else if (outcome === 'not-saved') {
      setNote('This browser would not keep the setting, so it lasts only until you close the tab.')
    }
  }

  return (
    <Card as="section">
      <Eyebrow as="h2">Browser notifications</Eyebrow>
      <label className="question-option" style={{ maxWidth: '520px' }}>
        <input
          type="checkbox"
          role="switch"
          checked={on}
          disabled={permission === 'unsupported'}
          onChange={(event) => void change(event.target.checked)}
        />
        <span className="question-option-label">
          Show a notification in this browser
          <span className="question-option-description">
            When an approval you can decide, or a question an agent asked you, arrives while this tab is not in front.
            Your browser asks for permission when you turn it on. This is your own setting, kept in this browser.
          </span>
        </span>
      </label>
      {on && permission === 'denied' && (
        <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
          This browser is blocking notifications for this site, so none will show until you allow them.
        </p>
      )}
      <p className="caption" role="status" style={{ marginTop: 'var(--space-3)' }}>
        {note}
      </p>
    </Card>
  )
}
