// @find: count figure, counted number, count-up figure, final value once, figure read by crawlers, prerendered count, CountFigure
// @what: A counted figure that shows its final value once, and only while it counts up shows the moving number beside the final value for assistive technology.
// @flow: Used by PlatformBand and RoleSwitcher with the number useCountUp returns

import type { ReactElement } from 'react'

/*
 * A count-up figure has two readers besides the sighted visitor: assistive technology, which must
 * never hear the intermediate numbers, and crawlers that do not run JavaScript, which read the
 * prerendered HTML with its tags removed and the stylesheet ignored.
 *
 * So the figure is written once whenever it is not moving: in the prerendered page (the server
 * render shows the final value) and after the count has finished. Only while it counts are there
 * two spans, the moving number hidden from assistive technology and the final value in visually
 * hidden text. Writing both all the time would make a crawler read "88 services" for 8.
 */

// @find: CountFigure component, count up figure markup
/** The figure `value`, given `shown`, the number useCountUp currently displays: one plain span when they are equal, otherwise the moving number (aria-hidden) and the final value (visually hidden). */
export function CountFigure({ shown, value }: { shown: number; value: number }): ReactElement {
  if (shown === value) return <span>{value}</span>
  return (
    <>
      <span aria-hidden="true">{shown}</span>
      <span className="visually-hidden">{value}</span>
    </>
  )
}
