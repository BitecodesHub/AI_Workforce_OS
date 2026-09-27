import type { ReactElement } from 'react'
import { Tag } from '../../ui'
import { useCountUp } from '../../../hooks/useCountUp'
import { useInView } from '../../../hooks/useInView'
import { LandingSection, SectionHead } from '../shared/LandingSection'
import {
  BUILT_IN_ROLE_COUNT,
  PERMISSION_CODE_COUNT,
  PROVIDERS,
  SERVICE_COUNT,
  TOOL_SERVERS,
} from '../shared/landingFacts'

/*
 * The spec band: what is actually running, as five counts and two rows of names.
 *
 * The counts come from landingFacts, so they cannot drift from the hero or the role explorer.
 * The animated figure is decoration and hidden from assistive technology; the final value sits
 * beside it in visually hidden text. Nothing in the band is focusable.
 */

type Stat = { id: string; value: number; unit: string; note: string }

const STATS: ReadonlyArray<Stat> = [
  { id: 'services', value: SERVICE_COUNT, unit: 'services', note: 'Independent Spring Boot services' },
  { id: 'providers', value: PROVIDERS.length, unit: '+ sandbox', note: 'Model providers, plus the offline sandbox model' },
  { id: 'tools', value: TOOL_SERVERS.length, unit: 'tool servers', note: 'MCP servers, each with a sandbox driver' },
  { id: 'codes', value: PERMISSION_CODE_COUNT, unit: 'permission codes', note: 'Composed into roles' },
  { id: 'roles', value: BUILT_IN_ROLE_COUNT, unit: 'built-in roles', note: 'Plus any roles you compose' },
]

function StatCell({ stat, active }: { stat: Stat; active: boolean }): ReactElement {
  const shown = useCountUp(stat.value, active)
  return (
    <li className="lp-hairline-cell lp-platform-stat">
      <p className="lp-platform-figure">
        <span className="lp-platform-value tabular">
          <span aria-hidden="true">{shown}</span>
          <span className="visually-hidden">{stat.value}</span>
        </span>{' '}
        <span className="lp-platform-unit">{stat.unit}</span>
      </p>
      <p className="lp-platform-note">{stat.note}</p>
    </li>
  )
}

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
          <div className="lp-platform-row">
            <p className="lp-micro lp-platform-label" id="platform-tools-label">
              Tool servers
            </p>
            <ul className="lp-platform-list" aria-labelledby="platform-tools-label">
              {TOOL_SERVERS.map((server) => (
                <li key={server}>
                  <span className="lp-platform-chip">{server}</span>
                </li>
              ))}
            </ul>
          </div>
        </div>
      </div>
    </LandingSection>
  )
}
