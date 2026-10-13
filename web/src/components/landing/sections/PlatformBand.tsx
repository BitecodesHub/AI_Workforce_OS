// @find: platform band, spec band, counts, services providers connectors permission codes, what is running, PlatformBand
// @what: The band of counts and names showing what is actually running.
// @flow: Facts from landingFacts
import type { ReactElement } from 'react'
import { Tag } from '../../ui'
import { useCountUp } from '../../../hooks/useCountUp'
import { useInView } from '../../../hooks/useInView'
import { CountFigure } from '../shared/CountFigure'
import { LandingSection, SectionHead } from '../shared/LandingSection'
import {
  BUILT_IN_ROLE_COUNT,
  CONNECTOR_COUNT,
  CONNECTOR_LABEL,
  LIVE_CONNECTORS,
  PERMISSION_CODE_COUNT,
  PRACTICE_CONNECTORS,
  PROVIDERS,
  SERVICE_COUNT,
} from '../shared/landingFacts'
import type { ConnectorId } from '../shared/landingFacts'

/*
 * The spec band: what is actually running, as five counts and three rows of names.
 *
 * The counts come from landingFacts, so they cannot drift from the hero or the role explorer.
 * The connectors are split the way mcp-core's ConnectorCatalog splits them: the few that connect
 * to a real account with a token, and the rest, which work with practice data only.
 * While a figure counts up, the moving number is hidden from assistive technology and the final
 * value sits beside it in visually hidden text; otherwise the figure is written once (CountFigure),
 * so a crawler reading the prerendered page sees "8 services", never "88 services". Nothing in the
 * band is focusable.
 */

/** One count in the band: id, target value, unit and the note under it. */
type Stat = { id: string; value: number; unit: string; note: string }

/** The five counts (services, providers plus sandbox, connectors, permission codes, built-in roles), taken from landingFacts so they cannot drift from the hero or the role explorer. */
const STATS: ReadonlyArray<Stat> = [
  { id: 'services', value: SERVICE_COUNT, unit: 'services', note: 'Independent Spring Boot services' },
  { id: 'providers', value: PROVIDERS.length, unit: '+ sandbox', note: 'Model providers, plus the offline sandbox model' },
  {
    id: 'connectors',
    value: CONNECTOR_COUNT,
    unit: 'connectors',
    note: `MCP servers: all ${LIVE_CONNECTORS.length} connect live once an administrator adds a token or signs in with the provider, and use practice data until then`,
  },
  { id: 'codes', value: PERMISSION_CODE_COUNT, unit: 'permission codes', note: 'Composed into roles' },
  { id: 'roles', value: BUILT_IN_ROLE_COUNT, unit: 'built-in roles', note: 'Plus any roles you compose' },
]

/** One count. The figure counts up (useCountUp) once the band is in view; CountFigure writes it once when still, and while it moves hides the moving number from assistive technology with the final value beside it in visually hidden text. */
function StatCell({ stat, active }: { stat: Stat; active: boolean }): ReactElement {
  const shown = useCountUp(stat.value, active)
  return (
    <li className="lp-hairline-cell lp-platform-stat">
      <p className="lp-platform-figure">
        <span className="lp-platform-value tabular">
          <CountFigure shown={shown} value={stat.value} />
        </span>{' '}
        <span className="lp-platform-unit">{stat.unit}</span>
      </p>
      <p className="lp-platform-note">{stat.note}</p>
    </li>
  )
}

/** A labelled list of connector names, named by its label through aria-labelledby. */
function ConnectorRow({
  id,
  label,
  connectors,
}: {
  id: string
  label: string
  connectors: readonly ConnectorId[]
}): ReactElement {
  return (
    <div className="lp-platform-row">
      <p className="lp-micro lp-platform-label" id={id}>
        {label}
      </p>
      <ul className="lp-platform-list" aria-labelledby={id}>
        {connectors.map((connector) => (
          <li key={connector}>
            <span className="lp-platform-chip">{CONNECTOR_LABEL[connector]}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

// @find: PlatformBand component, spec band
/** Section "What is actually running" (id platform): the five counts and rows of names for model providers, connectors that connect live (once an administrator adds a token or signs in) and, when there are any, connectors that work with practice data only. Counting starts when the list is 30 percent in view (useInView). Nothing in the band is focusable. Rendered by the Trust page. */
export function PlatformBand(): ReactElement {
  const { ref, inView } = useInView<HTMLUListElement>({ threshold: 0.3 })

  return (
    <LandingSection id="platform" labelledBy="platform-title">
      <SectionHead eyebrow="Under the hood" title="What is actually running" titleId="platform-title" band />
      <div className="lp-platform lp-frost">
        <ul ref={ref} className="lp-platform-stats lp-hairline-grid">
          {STATS.map((stat) => (
            <StatCell key={stat.id} stat={stat} active={inView} />
          ))}
        </ul>
        <div className="lp-platform-rows">
          <div className="lp-platform-row">
            <p className="lp-micro lp-platform-label" id="platform-providers-label">
              Model providers
            </p>
            <ul className="lp-platform-list" aria-labelledby="platform-providers-label">
              {PROVIDERS.map((provider) => (
                <li key={provider}>
                  <Tag tone="neutral">{provider}</Tag>
                </li>
              ))}
              <li>
                <Tag tone="neutral">Sandbox</Tag>
              </li>
            </ul>
          </div>
          <ConnectorRow id="platform-live-label" label="Connect live" connectors={LIVE_CONNECTORS} />
          {PRACTICE_CONNECTORS.length > 0 && (
            <ConnectorRow id="platform-practice-label" label="Practice data only" connectors={PRACTICE_CONNECTORS} />
          )}
        </div>
      </div>
    </LandingSection>
  )
}
