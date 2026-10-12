// @find: push notifications on this device, enable push, disable push, send a test notification, add to home screen iPhone, PushNotificationsSection, settings card push
// @what: The part of the Browser notifications card that turns push notifications on or off for this device and sends a test.
// @flow: Rendered by BrowserNotificationsCard; uses lib/push.ts (enablePush, disablePush, isPushOn, sendTestPush)
import { useEffect, useState } from 'react'
import { Button } from '../ui'
import { describeApiError } from '../../lib/api'
import { disablePush, enablePush, isPushOn, pushPermission, pushSupport, sendTestPush } from '../../lib/push'

/** What each way of turning push on can end, said to the person. Null for success. */
const OUTCOME_NOTE: Record<string, string | null> = {
  on: null,
  denied:
    'Your browser did not allow notifications for this site. Allow them in the browser’s site settings, then turn this on again.',
  unsupported: 'This browser cannot receive push notifications.',
  'needs-home-screen': 'Add the app to your Home Screen first, then open it from there and turn this on.',
  unavailable: 'Push notifications are not available on this server.',
  failed: 'Push notifications could not be turned on. Check your connection and try again.',
}

// @find: push notifications section component
/**
 * "Push notifications on this device": a switch, a test button once it is on, and the note that on
 * iPhone and iPad it works after Add to Home Screen. Push belongs to this browser and the signed-in
 * person; the switch reads its state from the browser itself, so it is right after a reload too.
 * Every outcome is said in words in a status line.
 */
export function PushNotificationsSection({ userId }: { userId: string }) {
  const support = pushSupport()
  const [on, setOn] = useState<boolean | null>(support === 'supported' ? null : false)
  const [busy, setBusy] = useState(false)
  const [testing, setTesting] = useState(false)
  const [note, setNote] = useState<string | null>(null)

  useEffect(() => {
    if (support !== 'supported' || !userId) return
    let live = true
    void isPushOn(userId).then((value) => {
      if (live) setOn(value)
    })
    return () => {
      live = false
    }
  }, [support, userId])

  async function change(next: boolean) {
    setNote(null)
    setBusy(true)
    try {
      if (next) {
        const outcome = await enablePush(userId)
        setOn(outcome === 'on')
        setNote(OUTCOME_NOTE[outcome] ?? null)
        if (outcome === 'on') setNote('Push notifications are on for this device.')
      } else {
        await disablePush()
        setOn(false)
        setNote('Push notifications are off for this device.')
      }
    } finally {
      setBusy(false)
    }
  }

  async function test() {
    setNote(null)
    setTesting(true)
    try {
      const result = await sendTestPush()
      setNote(result.message)
    } catch (error) {
      setNote(describeApiError(error))
    } finally {
      setTesting(false)
    }
  }

  const blocked = on === true && pushPermission() === 'denied'

  return (
    <div className="push-section">
      <label className="question-option" style={{ maxWidth: '520px' }}>
        <input
          type="checkbox"
          role="switch"
          checked={on === true}
          disabled={support !== 'supported' || busy || on === null}
          aria-busy={busy || undefined}
          onChange={(event) => void change(event.target.checked)}
        />
        <span className="question-option-label">
          Push notifications on this device
          <span className="question-option-description">
            An approval you can decide, or a question an agent asked you, reaches this phone or computer even when the
            app is closed. A notification names the kind of action that is waiting, never what would be sent.
          </span>
        </span>
      </label>
      {on === true && (
        <div style={{ marginTop: 'var(--space-3)' }}>
          <Button variant="outline" onClick={() => void test()} loading={testing}>
            Send a test notification
          </Button>
        </div>
      )}
      {support === 'unsupported' && (
        <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
          This browser cannot receive push notifications.
        </p>
      )}
      {blocked && (
        <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
          This browser is blocking notifications for this site, so none will show until you allow them.
        </p>
      )}
      <p className="caption muted" style={{ marginTop: 'var(--space-3)', maxWidth: '62ch' }}>
        On iPhone and iPad, push notifications work after you add the app to your Home Screen: tap Share, then Add to
        Home Screen, and open the app from there.
      </p>
      <p className="caption" role="status" style={{ marginTop: 'var(--space-2)' }}>
        {note}
      </p>
    </div>
  )
}
