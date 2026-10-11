// @find: hero globe, wireframe globe, glowing globe, orbit lines, connected world picture, HeroGlobe
// @what: The decorative wireframe globe that rises from the bottom of the hero stage.
// @flow: Rendered by Hero; styled and animated by styles/landing/sky.css
import type { ReactElement } from 'react'

/*
 * A globe drawn as loose loops rather than a tidy grid: a handful of meridians at different
 * angles and a few tilted orbits, so it reads as a connected network more than a map. It is pure
 * decoration, hidden from assistive technology, and turns slowly only when motion is on.
 */

/** Radius, in view box units, of the globe. */
const R = 470

/** Meridians: the width of each loop, as a share of the radius, and the angle it leans at. */
const MERIDIANS: ReadonlyArray<[number, number]> = [
  [1, 0],
  [0.92, 18],
  [0.74, -24],
  [0.52, 32],
  [0.3, -12],
  [0.16, 48],
  [0.62, 70],
  [0.42, -58],
]

/** Orbits: the height of each loop, as a share of the radius, and the angle it leans at. */
const ORBITS: ReadonlyArray<[number, number]> = [
  [0.34, -16],
  [0.22, 24],
  [0.5, 8],
]

// @find: HeroGlobe component
/** Decorative SVG globe drawn as loose ellipses (MERIDIANS and ORBITS) so it reads as a connected network more than a map. Hidden from assistive technology and not focusable; its group is given a class so the stylesheet can turn it slowly only when motion is on. No props or state. Rendered by Hero. */
export function HeroGlobe(): ReactElement {
  return (
    <svg className="lp-globe" viewBox="0 0 1000 1000" fill="none" aria-hidden="true" focusable="false">
      <g className="lp-globe-spin">
        {MERIDIANS.map(([width, angle]) => (
          <ellipse
            key={`m${width}${angle}`}
            cx="500"
            cy="500"
            rx={R * width}
            ry={R}
            transform={`rotate(${angle} 500 500)`}
          />
        ))}
        {ORBITS.map(([height, angle]) => (
          <ellipse
            key={`o${height}${angle}`}
            className="lp-globe-orbit"
            cx="500"
            cy="500"
            rx={R}
            ry={R * height}
            transform={`rotate(${angle} 500 500)`}
          />
        ))}
      </g>
    </svg>
  )
}
