import type { RefObject } from 'react'
import { useLayoutEffect, useRef, useState } from 'react'

/*
 * The thread's own scroll behaviour (B1.8): stay pinned to the bottom while the reader is already
 * there, count what they have missed while they are not, and jump back on request. All the actual
 * scrolling happens in a layout effect, which only ever touches the DOM; the counts themselves are
 * plain state updated from the scroll handler, never written during render (0.2).
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
  opts: { itemCount: number; resetKey: string | null; reducedMotion: boolean },
): StickToBottom {
  const { itemCount, resetKey, reducedMotion } = opts
  const [nearBottom, setNearBottom] = useState(true)
  const [farFromBottom, setFarFromBottom] = useState(false)
  const [countAtBottom, setCountAtBottom] = useState(itemCount)
  const previousResetKey = useRef(resetKey)
  const previousItemCount = useRef(itemCount)

  const newCount = nearBottom ? 0 : Math.max(0, itemCount - countAtBottom)

  function measure() {
    const el = ref.current
    if (!el) return
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight
    const atBottom = distance < NEAR_BOTTOM_PX
    setNearBottom(atBottom)
    setFarFromBottom(distance > el.clientHeight)
    if (atBottom) setCountAtBottom(itemCount)
  }

  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const onScroll = () => measure()
    el.addEventListener('scroll', onScroll)
    return () => el.removeEventListener('scroll', onScroll)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const resetChanged = previousResetKey.current !== resetKey
    const grew = itemCount > previousItemCount.current
    previousResetKey.current = resetKey
    previousItemCount.current = itemCount
    if (resetChanged) {
      el.scrollTo({ top: el.scrollHeight })
      setCountAtBottom(itemCount)
      setNearBottom(true)
      setFarFromBottom(false)
      return
    }
    if (grew && nearBottom) {
      el.scrollTo({ top: el.scrollHeight, behavior: reducedMotion ? 'auto' : 'smooth' })
      setCountAtBottom(itemCount)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [itemCount, resetKey])

  function jump() {
    const el = ref.current
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: reducedMotion ? 'auto' : 'smooth' })
    setCountAtBottom(itemCount)
    setNearBottom(true)
    setFarFromBottom(false)
  }

  /** Runs `fn` (prepending earlier messages, an automatic collapse), then restores the visual position. */
  function keepPosition(fn: () => void) {
    const el = ref.current
    if (!el) {
      fn()
      return
    }
    const before = el.scrollHeight - el.scrollTop
    fn()
    requestAnimationFrame(() => {
      el.scrollTop = el.scrollHeight - before
    })
  }

  return { nearBottom, farFromBottom, newCount, jump, keepPosition }
}
