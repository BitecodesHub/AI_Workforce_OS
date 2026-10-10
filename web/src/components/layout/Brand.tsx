// @find: brand, logo, wordmark, aiworkforce logo, home link, navbar logo, Brand mark, header logo
// @what: The product wordmark and inline SVG mark linking home.
// @flow: Rendered at the left of Navbar.
// @find: brand logo wordmark, home link
/**
 * The wordmark and its mark.
 *
 * <p>The mark is two strokes rather than an image: an L-shape and a square dot, both in the
 * accent colour. Drawing it inline means it is crisp at any density, it inherits the accent when
 * the theme changes, and it costs no request.
 */
export function Brand() {
  return (
    <a className="brand" href="/" aria-label="AI Workforce OS, home">
      <svg width="20" height="25" viewBox="0 0 20 25" fill="none" aria-hidden="true">
        {/* The L: a vertical stroke with a foot, 6px wide, 1px corner radius. */}
        <path
          d="M3 0.5 V21.5 H14"
          stroke="var(--blue)"
          strokeWidth="6"
          strokeLinecap="square"
          strokeLinejoin="miter"
        />
        {/* The dot, set clear of the foot so the two read as separate marks. */}
        <rect x="12" y="0.5" width="8" height="8" rx="1" fill="var(--blue)" />
      </svg>
      <span className="brand-wordmark">
        aiworkforce<span className="brand-stop">.</span>
      </span>
    </a>
  )
}
