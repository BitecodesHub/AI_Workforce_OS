// @find: push notifications on this device, enable push, disable push, send a test notification, add to home screen iPhone, my push devices, remove a device, lost phone push, PushNotificationsSection, settings card push
// @what: The part of the Browser notifications card that turns push notifications on or off for this device, sends a test, and lists the person's devices with push on so one can be removed.
// @flow: Rendered by BrowserNotificationsCard; uses lib/push.ts (enablePush, disablePush, isPushOn, syncPush, sendTestPush, listPushDevices, removePushDevice, currentSubscription)
import { useCallback, useEffect, useRef, useState } from 'react'
import { Button, Tag, Time } from '../ui'
import { describeApiError } from '../../lib/api'
import { formatDateTime } from '../../lib/format'
import {
  currentSubscription,
  disablePush,
  enablePush,
  isPushOn,
  listPushDevices,
  pushPermission,
  pushSupport,
  removePushDevice,
  sendTestPush,
  syncPush,
} from '../../lib/push'
import type { PushDevice } from '../../lib/push'

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
 * "Push notifications on this device": a switch, a test button once it is on, the note that on
 * iPhone and iPad it works after Add to Home Screen, and the person's devices with push on in this
 * workspace - this one marked, every other with a Remove button, for a phone that was lost or
 * handed on. Push belongs to this browser and the signed-in person; the switch reads its state
 * from the browser itself, so it is right after a reload too. Every outcome is said in words in a
 * status line.
 */
export function PushNotificationsSection({ userId }: { userId: string }) {
  const support = pushSupport()
  const [on, setOn] = useState<boolean | null>(support === 'supported' ? null : false)
  const [busy, setBusy] = useState(false)
  const [testing, setTesting] = useState(false)
  const [note, setNote] = useState<string | null>(null)
  const [devices, setDevices] = useState<PushDevice[] | null>(null)
  const [devicesFailed, setDevicesFailed] = useState(false)
  const [thisEndpoint, setThisEndpoint] = useState<string | null>(null)
  const [removing, setRemoving] = useState<string | null>(null)
  // The line that says how the last change went; focus lands here when the control that made it
  // has gone (a removed device's own Remove button), rather than falling to the top of the page.
  const statusRef = useRef<HTMLParagraphElement>(null)
  const refocusAfterRemoval = useRef(false)

  /*
   * The person's devices, and which one is this browser. Read again after anything changes them.
   * When push is on here but the server no longer lists this browser (it was removed from another
   * device, or its sign-in ended), it is told again at once, so the switch never says on while
   * nothing would arrive.
   */
  const loadDevices = useCallback(
    async (pushOnHere?: boolean) => {
      try {
        let [listed, subscription] = await Promise.all([listPushDevices(), currentSubscription()])
        if (pushOnHere && subscription && !listed.some((device) => device.endpoint === subscription?.endpoint)) {
          await syncPush(userId, undefined, { force: true })
          ;[listed, subscription] = await Promise.all([listPushDevices(), currentSubscription()])
        }
        setDevices(listed)
        setThisEndpoint(subscription?.endpoint ?? null)
        setDevicesFailed(false)
      } catch {
        setDevicesFailed(true)
      }
    },
    [userId],
  )

  useEffect(() => {
    let live = true
    void (async () => {
      let value = false
      if (support === 'supported' && userId) {
        value = await isPushOn(userId)
        if (live) setOn(value)
      }
      if (live) await loadDevices(value)
    })()
    return () => {
      live = false
    }
  }, [support, userId, loadDevices])

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
      void loadDevices()
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
      void loadDevices()
    }
  }

  async function remove(device: PushDevice) {
    setNote(null)
    setRemoving(device.id)
    try {
      await removePushDevice(device.id)
      setNote(`Push notifications are off on ${device.label || 'that device'}.`)
      refocusAfterRemoval.current = true
    } catch (error) {
      setNote(describeApiError(error))
    } finally {
      setRemoving(null)
      void loadDevices()
    }
  }

  /*
   * After a removal the reloaded list no longer has the row, nor the Remove button that had focus;
   * the browser would drop focus to the page itself, so it moves to the status line, which says
   * what happened. Focus the person has since moved elsewhere is left alone.
   */
  useEffect(() => {
    if (!refocusAfterRemoval.current || (devices === null && !devicesFailed)) return
    refocusAfterRemoval.current = false
    const active = document.activeElement
    if (!active || active === document.body || !active.isConnected) statusRef.current?.focus()
  }, [devices, devicesFailed])

  const blocked = on === true && pushPermission() === 'denied'
  const listed = devices ?? []

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
            app is closed, for each workspace you open here. A notification names the kind of action that is waiting,
            never what would be sent. Signing out here turns it off.
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

      {(listed.length > 0 || devicesFailed) && (
        <div className="push-devices">
          <h3 className="push-devices-heading">Devices with push notifications in this workspace</h3>
          {devicesFailed ? (
            <p className="caption muted">Your devices could not be loaded. Try reloading the page.</p>
          ) : (
            <ul className="push-device-list" aria-label="Devices with push notifications">
              {listed.map((device) => {
                const here = thisEndpoint !== null && device.endpoint === thisEndpoint
                return (
                  <li key={device.id} className="push-device">
                    <div className="push-device-text">
                      <span className="push-device-name">
                        {device.label || 'A browser'}
                        {here && <Tag tone="blue">This device</Tag>}
                      </span>
                      <span className="caption muted">
                        Added <Time iso={device.createdAt} />
                        {device.lastSuccessAt ? (
                          <>
                            {' · last notified '}
                            <Time iso={device.lastSuccessAt} />
                          </>
                        ) : null}
                      </span>
                    </div>
                    {!here && (
                      <Button
                        variant="outline"
                        onClick={() => void remove(device)}
                        loading={removing === device.id}
                        aria-label={`Remove ${device.label || 'a browser'}, added ${formatDateTime(device.createdAt)}`}
                      >
                        Remove
                      </Button>
                    )}
                  </li>
                )
              })}
            </ul>
          )}
          <p className="caption muted" style={{ maxWidth: '62ch' }}>
            A device that is signed out, here or from your Profile page, stops receiving notifications on its own.
            Remove one you no longer have.
          </p>
        </div>
      )}

      <p ref={statusRef} tabIndex={-1} className="caption" role="status" style={{ marginTop: 'var(--space-2)' }}>
        {note}
      </p>
    </div>
  )
}
