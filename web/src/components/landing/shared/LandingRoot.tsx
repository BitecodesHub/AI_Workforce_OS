// @find: landing root, motion, reduced motion, pause motion, data-motion, data-ambient, useLandingMotion, LandingRoot
// @what: Root of the public page that owns the motion and ambient switches.
// @flow: Used by routes/Landing and Trust; read via useLandingMotion
import { createContext, useContext, useMemo, useState } from 'react'
import type { ReactElement, ReactNode } from 'react'
import { useReducedMotion } from '../../../hooks/useReducedMotion'

/*
 * The root of the public page.
 *
 * It owns the two switches every landing stylesheet reads: data-motion, which follows the
 * visitor's reduced-motion setting, and data-ambient, which the hero's "Pause motion" control
 * flips to stop the aurora drift, every pulse and the hero loop at once (WCAG 2.2.2).
 */

export type LandingMotion = {
  reduced: boolean
  ambientPaused: boolean
  setAmbientPaused: (paused: boolean) => void
}

const LandingMotionContext = createContext<LandingMotion | null>(null)

function ignorePause(): void {
  // Outside a LandingRoot there is no ambient motion to pause.
}

// @find: LandingRoot component, motion root
export function LandingRoot({ children }: { children: ReactNode }): ReactElement {
  const reduced = useReducedMotion()
  const [ambientPaused, setAmbientPaused] = useState(false)
  const motion = useMemo<LandingMotion>(
    () => ({ reduced, ambientPaused, setAmbientPaused }),
    [reduced, ambientPaused],
  )

  return (
    <LandingMotionContext.Provider value={motion}>
      <div
        className="lp"
        data-motion={reduced ? 'off' : 'on'}
        data-ambient={ambientPaused ? 'paused' : 'running'}
      >
        <div className="lp-aurora" aria-hidden="true">
          <span className="lp-aurora-blob" />
          <span className="lp-aurora-blob" />
          <span className="lp-aurora-blob" />
        </div>
        {children}
      </div>
    </LandingMotionContext.Provider>
  )
}

/**
 * The page's motion state. Outside a LandingRoot it still reports the visitor's reduced-motion
 * setting, with ambient motion running and a pause that does nothing, so every landing component
 * renders standalone in a test.
 */
// @find: useLandingMotion hook, reduced motion, pause motion
export function useLandingMotion(): LandingMotion {
  const context = useContext(LandingMotionContext)
  const reduced = useReducedMotion()
  const standalone = useMemo<LandingMotion>(
    () => ({ reduced, ambientPaused: false, setAmbientPaused: ignorePause }),
    [reduced],
  )
  return context ?? standalone
}
