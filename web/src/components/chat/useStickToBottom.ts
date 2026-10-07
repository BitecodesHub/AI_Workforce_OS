import type { RefObject } from 'react'
import { useLayoutEffect, useRef, useState } from 'react'
import { flushSync } from 'react-dom'

/*
 * The thread's own scroll behaviour (B1.8): stay pinned to the bottom while the reader is already
 * there, count what they have missed while they are not, and jump back on request. All the actual
 * scrolling happens in a layout effect, which only ever touches the DOM; the counts themselves are
 * plain state updated from the scroll handler, never written during render (0.2).
 *
 * It follows the newest message's position, not how many messages are loaded: a long thread's
 * live window holds a fixed number of messages, so its length stops changing once it is full, and
 * earlier messages loaded above the reader change the length without anything new arriving.
 * Positions are consecutive within a conversation, so the difference between two of them is the
 * number of messages in between.
 */

const NEAR_BOTTOM_PX = 140

export type StickToBottom = {
  nearBottom: boolean
  farFromBottom: boolean
  newCount: number
  jump: () => void
  keepPosition: (fn: () => void) => void
}

export function useStickToBottom(
  ref: RefObject<HTMLElement | null>,
  opts: { newestPosition: number; resetKey: string | null; reducedMotion: boolean },
): StickToBottom {
  const { newestPosition, resetKey, reducedMotion } = opts
  const [nearBottom, setNearBottom] = useState(true)
  const [farFromBottom, setFarFromBottom] = useState(false)
  const [positionAtBottom, setPositionAtBottom] = useState(newestPosition)
  const previousResetKey = useRef(resetKey)
  const previousNewest = useRef(newestPosition)
  // The scroll listener is attached once, so it reads the newest position through a ref.
  const newest = useRef(newestPosition)

  const newCount = nearBottom ? 0 : Math.max(0, newestPosition - positionAtBottom)

  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const onScroll = () => {
      const distance = el.scrollHeight - el.scrollTop - el.clientHeight
      const atBottom = distance < NEAR_BOTTOM_PX
      setNearBottom(atBottom)
      setFarFromBottom(distance > el.clientHeight)
      if (atBottom) setPositionAtBottom(newest.current)
    }
    el.addEventListener('scroll', onScroll)
    return () => el.removeEventListener('scroll', onScroll)
  }, [ref])

  useLayoutEffect(() => {
    newest.current = newestPosition
    const el = ref.current
    if (!el) return
    const resetChanged = previousResetKey.current !== resetKey
    const grew = newestPosition > previousNewest.current
    previousResetKey.current = resetKey
    previousNewest.current = newestPosition
    if (resetChanged) {
      el.scrollTo({ top: el.scrollHeight })
      setPositionAtBottom(newestPosition)
      setNearBottom(true)
      setFarFromBottom(false)
      return
    }
    if (grew && nearBottom) {
      el.scrollTo({ top: el.scrollHeight, behavior: reducedMotion ? 'auto' : 'smooth' })
      setPositionAtBottom(newestPosition)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [newestPosition, resetKey])

  function jump() {
    const el = ref.current
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: reducedMotion ? 'auto' : 'smooth' })
    setPositionAtBottom(newestPosition)
    setNearBottom(true)
    setFarFromBottom(false)
  }

  /**
   * Runs `fn` (earlier messages going in above the reader, an automatic collapse) and keeps what
   * the reader was looking at where it was. The change is rendered at once (flushSync), so the
   * new height can be measured straight away; call it from an event or after an await, never
   * during a render or an effect.
   */
  function keepPosition(fn: () => void) {
    const el = ref.current
    if (!el) {
      fn()
      return
    }
    const fromBottom = el.scrollHeight - el.scrollTop
    flushSync(fn)
    el.scrollTop = el.scrollHeight - fromBottom
  }

  return { nearBottom, farFromBottom, newCount, jump, keepPosition }
}
