// @find: agents section, AI team, example agents, HR engineering research support agents, tool servers, grants, AgentsSection, #agents
// @what: Section showing the four example AI assistants and what each may do in each tool.
// @flow: Uses landingFacts AGENTS and agentGrants
import { useCallback, useRef, useState } from 'react'
import type { KeyboardEvent, ReactElement } from 'react'
import { Tag } from '../../ui'
import { revealStyle, useReveal } from '../../../hooks/useReveal'
import { usePointerSpot } from '../../../hooks/usePointerSpot'
import { Icon } from '../shared/Icon'
import { LandingSection, SectionHead } from '../shared/LandingSection'
import { AGENTS, TOOL_LABEL } from '../shared/landingFacts'
import type { AgentFact, ToolServer } from '../shared/landingFacts'
import { AGENT_GRANTS, defaultGrant } from './agentGrants'

/*
 * The AI team: the four example assistants the demo workspace comes with, each with the tools it
 * works in and what it may do in each without a person. They are examples, not something every
 * new workspace receives, and in the demo their tools work with practice data, so the copy says
 * both.
 *
 * The chips are toggle buttons in a roving group: one tab stop per tile, Left and Right (and Home
 * and End) move between servers and select as they go. The sentence below them is a polite live
 * region, re-keyed per server so it fades in and is announced as a whole.
 */

// @find: AgentsSection component, AI team section
export function AgentsSection(): ReactElement {
  return (
    <LandingSection id="team" labelledBy="team-title">
      <SectionHead
        eyebrow="Your AI team"
        title="Example assistants for the work your team repeats"
        titleId="team-title"
        lead="Four examples from the demo workspace, each with a job, the tools it needs and a clear line it will not cross without asking. In the demo their tools work with practice data. Pick a tool to see what it may do there."
      />
      <div className="lp-agents-grid">
        {AGENTS.map((agent, index) => (
          <AgentTile key={agent.id} agent={agent} index={index} />
        ))}
      </div>
    </LandingSection>
  )
}

function AgentTile({ agent, index }: { agent: AgentFact; index: number }): ReactElement {
  const grants = AGENT_GRANTS[agent.id]
  const [pressed, setPressed] = useState<ToolServer | null>(() => defaultGrant(agent.id))
  const chips = useRef<Partial<Record<ToolServer, HTMLButtonElement | null>>>({})
  const revealRef = useReveal<HTMLElement>()
  const spotRef = usePointerSpot<HTMLElement>()

  const ref = useCallback(
    (node: HTMLElement | null) => {
      if (!node) return undefined
      const releaseReveal = revealRef(node)
      const releaseSpot = spotRef(node)
      return () => {
        if (typeof releaseReveal === 'function') releaseReveal()
        if (typeof releaseSpot === 'function') releaseSpot()
      }
    },
    [revealRef, spotRef],
  )

  const current = grants.find((grant) => grant.server === pressed) ?? grants[0]
  const titleId = `agent-${agent.id}-title`

  function choose(server: ToolServer, moveFocus: boolean) {
    if (moveFocus) chips.current[server]?.focus()
    setPressed(server)
  }

  function onChipKeyDown(event: KeyboardEvent<HTMLButtonElement>, position: number) {
    const last = grants.length - 1
    let next: number
    switch (event.key) {
      case 'ArrowRight':
        next = position === last ? 0 : position + 1
        break
      case 'ArrowLeft':
        next = position === 0 ? last : position - 1
        break
      case 'Home':
        next = 0
        break
      case 'End':
        next = last
        break
      default:
        return
    }
    event.preventDefault()
    const grant = grants[next]
    if (grant) choose(grant.server, true)
  }

  return (
    <article
      ref={ref}
      className="lp-reveal lp-agents-tile lp-frost lp-lift lp-spot"
      style={revealStyle(index)}
      aria-labelledby={titleId}
    >
      <div className="lp-agents-head">
        <Tag tone={agent.category} withDot>
          {agent.name}
        </Tag>
      </div>
      <h3 id={titleId} className="lp-h3 lp-agents-title">
        {agent.title}
      </h3>
      <p className="muted lp-agents-detail">{agent.detail}</p>
      {/* One tool is selected at a time with a roving tabindex, which is the radio-group
          interaction, not the independent-toggle one aria-pressed describes - a screen reader
          announcing "toggle button, pressed" implies picking another leaves this one on too. */}
      <div role="radiogroup" aria-label={`${agent.name} tools`} className="lp-agents-tools">
        {grants.map((grant, position) => {
          const isSelected = grant.server === current?.server
          return (
            <button
              key={grant.server}
              ref={(node) => {
                chips.current[grant.server] = node
              }}
              type="button"
              role="radio"
              className="lp-chip"
              aria-checked={isSelected}
              tabIndex={isSelected ? 0 : -1}
              onClick={() => choose(grant.server, false)}
              onKeyDown={(event) => onChipKeyDown(event, position)}
            >
              {TOOL_LABEL[grant.server]}
            </button>
          )
        })}
      </div>
      <p className="lp-agents-grant" aria-live="polite" aria-atomic="true">
        {current && (
          <span key={current.server} className="lp-agents-grant-line lp-anim-fade">
            {current.gated && <Icon name="lock" className="lp-agents-lock" />}
            <span>{current.sentence}</span>
          </span>
        )}
      </p>
    </article>
  )
}
