import { useState } from 'react'

/*
 * Keeps a list's order from shifting under a reader's pointer or focus while it polls (B1.3,
 * C68). While `frozen` is true, new ids are appended at the end and removed ids are dropped, but
 * everything else keeps the position it already had; once it unfreezes, the server's own order is
 * taken again. Adjusted during render, the "previous value" pattern (0.2), never in an effect.
 */
export function useStableOrder(ids: readonly string[], frozen: boolean): string[] {
  const [order, setOrder] = useState<string[]>([...ids])
  const [wasFrozen, setWasFrozen] = useState(frozen)

  const sameOrder = order.length === ids.length && order.every((id, index) => id === ids[index])

  if (!frozen) {
    // Not held: always reflect the latest server order.
    if (!sameOrder) setOrder([...ids])
    if (wasFrozen) setWasFrozen(false)
    return sameOrder ? order : [...ids]
  }

  if (!wasFrozen) {
    // Just froze: start from whatever order is showing right now.
    setWasFrozen(true)
    if (!sameOrder) setOrder([...ids])
    return sameOrder ? order : [...ids]
  }

  // Already frozen: keep known ids in their place, append new ones, drop removed ones.
  const idSet = new Set(ids)
  const kept = order.filter((id) => idSet.has(id))
  const known = new Set(kept)
  const added = ids.filter((id) => !known.has(id))
  const next = [...kept, ...added]
  const changed = next.length !== order.length || next.some((id, index) => id !== order[index])
  if (changed) setOrder(next)
  return changed ? next : order
}
