// @find: takeover controls, you have control, the agent is waiting, hand back to agent, click on live page, type into page, press keys, scroll page, address bar, interactive browser frame
// @what: The Agent desk while you hold a run's web browser: the live page you can click, an address bar, a text box with Type, common keys, scrolling, and Hand back with an optional note.
// @flow: Rendered by AgentDesk when the takeover is yours; calls useTakeoverAction and useReleaseTakeover; frame from useBrowserLive's cache; tells AgentDesk when control was handed back
import { useEffect, useId, useRef, useState } from 'react'
import type { FormEvent, MouseEvent } from 'react'
import { Button, Input, Notice, Textarea } from '../ui'
import { describeApiError } from '../../lib/api'
import {
  DEFAULT_FRAME,
  SCROLL_STEP,
  TAKEOVER_KEYS,
  framePoint,
  minutesLeft,
  wheelDistance,
} from '../../lib/agentDesk'
import { useReleaseTakeover, useTakeoverAction } from '../../lib/agentDeskQueries'
import type { TakeoverAction, TakeoverView } from '../../lib/agentDeskQueries'
import type { BrowserLive } from '../../lib/queries'
import { useToast } from '../../lib/toast'
import { useNow } from '../../lib/useNow'

/** How long wheel movement is gathered before it is sent as one scroll. */
const WHEEL_GATHER_MS = 250

// @find: TakeoverControls, you have control, hand back, interactive frame
/**
 * Everything a person needs while they hold a run's web browser.
 * Flow: a banner says "You have control. The agent is waiting." with the minutes left before control
 * returns by itself, and Hand back to the agent (an optional note, then Hand back). The banner takes
 * focus when control is given, so it is read out once; it is not a live region, so the minutes
 * countdown is not announced every minute. Opening the note moves focus into it; Keep control
 * returns focus to Hand back; a hand back that succeeds says so in a toast and calls onHandedBack,
 * where the desk moves focus somewhere that stays. The live picture
 * is clickable (framePoint maps the click to the page) and wheel movement over it is gathered into
 * one scroll every quarter second; an address bar opens a page; a text box types into the field
 * last clicked (with an option to hide what is typed); buttons press common keys and scroll. One
 * action at a time: controls wait while one is in flight. Every action's answer replaces the
 * picture; failures show in a notice. Works with mouse, touch (a tap is a click) and keyboard (every
 * control is a button or field).
 */
export function TakeoverControls({
  runId,
  agentName,
  live,
  view,
  onHandedBack,
}: {
  runId: string
  agentName: string
  live: BrowserLive | null
  view: TakeoverView
  /** Called once control has been handed back, before these controls go away. */
  onHandedBack?: () => void
}) {
  const act = useTakeoverAction(runId)
  const release = useReleaseTakeover(runId)
  const toast = useToast()
  const [error, setError] = useState<string | null>(null)
  const [text, setText] = useState('')
  const [hidden, setHidden] = useState(false)
  // What the person has typed into the address bar; null while it simply shows the page's address.
  const [typedAddress, setTypedAddress] = useState<string | null>(null)
  const address = typedAddress ?? live?.url ?? ''
  const [handingBack, setHandingBack] = useState(false)
  const [note, setNote] = useState('')
  const image = useRef<HTMLImageElement>(null)
  const banner = useRef<HTMLDivElement>(null)
  const handBackForm = useRef<HTMLFormElement>(null)
  // Where focus goes after the hand-back form opens or closes; set by the buttons, applied below.
  const [focusAfter, setFocusAfter] = useState<'note' | 'handBack' | null>(null)
  const wheel = useRef<{ deltas: number[]; timer: number | null }>({ deltas: [], timer: null })
  const hintId = useId()
  const now = useNow(30_000)
  const left = minutesLeft(view.endsAt, now)
  const busy = act.isPending

  // Focus lands on the banner when control is taken, so a screen reader hears it at once.
  useEffect(() => {
    banner.current?.focus()
  }, [])

  // The note field when the hand-back form opens; the Hand back button again when it closes.
  useEffect(() => {
    if (focusAfter === 'note') handBackForm.current?.querySelector('textarea')?.focus()
    if (focusAfter === 'handBack') banner.current?.querySelector('button')?.focus()
  }, [focusAfter])

  /** Sends one action; shows the server's sentence when it is refused. Resolves true when it was done. */
  const send = async (action: TakeoverAction): Promise<boolean> => {
    setError(null)
    try {
      await act.mutateAsync(action)
      return true
    } catch (thrown) {
      setError(describeApiError(thrown))
      return false
    }
  }

  // The newest send, for the wheel listener below, which is added once per picture element.
  const latestSend = useRef(send)
  useEffect(() => {
    latestSend.current = send
  })

  // Wheel movement over the picture scrolls the page, not the desk: a native listener, because
  // React's wheel handler is passive and cannot stop the desk from scrolling.
  const hasFrame = Boolean(live?.frame)
  useEffect(() => {
    const node = image.current
    if (!node) return
    const gathered = wheel.current
    const onWheel = (event: WheelEvent) => {
      event.preventDefault()
      gathered.deltas.push(event.deltaY)
      if (gathered.timer !== null) return
      gathered.timer = window.setTimeout(() => {
        const dy = wheelDistance(gathered.deltas)
        gathered.deltas = []
        gathered.timer = null
        if (dy !== 0) void latestSend.current({ type: 'scroll', dy })
      }, WHEEL_GATHER_MS)
    }
    node.addEventListener('wheel', onWheel, { passive: false })
    return () => {
      node.removeEventListener('wheel', onWheel)
      if (gathered.timer !== null) window.clearTimeout(gathered.timer)
      gathered.timer = null
    }
  }, [hasFrame])

  const onFrameClick = (event: MouseEvent<HTMLImageElement>) => {
    if (busy) return
    const box = event.currentTarget.getBoundingClientRect()
    const natural = {
      width: event.currentTarget.naturalWidth || DEFAULT_FRAME.width,
      height: event.currentTarget.naturalHeight || DEFAULT_FRAME.height,
    }
    const click = framePoint(event, box, natural)
    if (click) void send({ type: 'click', ...click })
  }

  const onType = async (event: FormEvent) => {
    event.preventDefault()
    if (!text) return
    if (await send({ type: 'type', text })) setText('')
  }

  const onGo = async (event: FormEvent) => {
    event.preventDefault()
    const url = address.trim()
    if (!url) return
    if (await send({ type: 'navigate', url: /^https?:\/\//i.test(url) ? url : `https://${url}` })) {
      setTypedAddress(null)
    }
  }

  const onHandBack = async (event: FormEvent) => {
    event.preventDefault()
    setError(null)
    try {
      await release.mutateAsync({ note })
      toast.success(`Control handed back to ${agentName}.`)
      onHandedBack?.()
    } catch (thrown) {
      setError(describeApiError(thrown))
    }
  }

  return (
    <div className="takeover" aria-busy={busy || undefined}>
      <div className="takeover-banner" ref={banner} tabIndex={-1}>
        <div className="takeover-banner-text">
          <p className="takeover-banner-title">You have control. The agent is waiting.</p>
          <p className="caption muted">
            {left === null
              ? `${agentName} carries on when you hand it back.`
              : `${agentName} carries on when you hand it back, or by itself in ${left} ${left === 1 ? 'minute' : 'minutes'}.`}
          </p>
        </div>
        {!handingBack && (
          <Button
            className="takeover-handback-open"
            onClick={() => {
              setHandingBack(true)
              setFocusAfter('note')
            }}
          >
            {`Hand back to ${agentName}`}
          </Button>
        )}
      </div>

      {handingBack && (
        <form className="takeover-handback" onSubmit={onHandBack} ref={handBackForm}>
          <Textarea
            label={`Note for ${agentName}`}
            optional
            hint="For example, what you did or what it should do next. Do not include passwords."
            value={note}
            maxLength={500}
            rows={2}
            onChange={(event) => setNote(event.target.value)}
          />
          <div className="takeover-row">
            <Button type="submit" loading={release.isPending}>
              Hand back
            </Button>
            <Button
              variant="quiet"
              onClick={() => {
                setHandingBack(false)
                setFocusAfter('handBack')
              }}
              disabled={release.isPending}
            >
              Keep control
            </Button>
          </div>
        </form>
      )}

      {error && (
        <Notice tone="warning" live>
          {error}
        </Notice>
      )}

      <form className="takeover-address" onSubmit={onGo}>
        <Input
          label="Address"
          type="text"
          inputMode="url"
          autoComplete="off"
          spellCheck={false}
          value={address}
          onChange={(event) => setTypedAddress(event.target.value)}
          disabled={busy}
        />
        <Button type="submit" variant="outline" disabled={busy || !address.trim()}>
          Go
        </Button>
      </form>

      <div className="takeover-frame" data-busy={busy || undefined}>
        {live?.frame ? (
          <img
            ref={image}
            className="takeover-image"
            src={`data:image/jpeg;base64,${live.frame}`}
            alt={`The page you control: ${live.title || live.url || 'the page'}`}
            aria-describedby={hintId}
            draggable={false}
            onClick={onFrameClick}
          />
        ) : (
          <p className="browser-preview-empty caption muted">Loading the page…</p>
        )}
      </div>
      <p id={hintId} className="caption muted">
        Click or tap the picture to click on the page there. To type, click the field on the page first, then use the box
        below.
      </p>

      <form className="takeover-type" onSubmit={onType}>
        <Input
          label="Text to type"
          type={hidden ? 'password' : 'text'}
          autoComplete="off"
          spellCheck={false}
          value={text}
          maxLength={2000}
          onChange={(event) => setText(event.target.value)}
          disabled={busy}
        />
        <Button type="submit" variant="outline" disabled={busy || !text}>
          Type
        </Button>
      </form>
      <label className="takeover-hide caption">
        <input type="checkbox" checked={hidden} onChange={(event) => setHidden(event.target.checked)} />
        Hide what I type
      </label>

      <div className="takeover-keys" role="group" aria-label="Keys and scrolling">
        {TAKEOVER_KEYS.map((entry) => (
          <button
            key={entry.key}
            type="button"
            className="takeover-key"
            aria-label={entry.label}
            title={entry.label}
            disabled={busy}
            onClick={() => void send({ type: 'key', key: entry.key })}
          >
            {entry.text}
          </button>
        ))}
        <button
          type="button"
          className="takeover-key"
          disabled={busy}
          onClick={() => void send({ type: 'scroll', dy: -SCROLL_STEP })}
        >
          Scroll up
        </button>
        <button
          type="button"
          className="takeover-key"
          disabled={busy}
          onClick={() => void send({ type: 'scroll', dy: SCROLL_STEP })}
        >
          Scroll down
        </button>
      </div>
    </div>
  )
}
