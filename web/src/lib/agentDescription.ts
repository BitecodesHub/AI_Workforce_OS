// @find: agent description, agent summary, welcome screen, what an agent does, second person to third person, describeAgent, agentDescription, category fallback
// @what: Rewrites an agent summary written in the second person ("You triage...") into a plain third-person description for the welcome screen.
// @flow: Called by the chat welcome screen and agent pickers
/*
 * What the welcome screen says an agent does. An agent's summary is often its own instructions,
 * written to it in the second person ("You triage support tickets..."), which reads oddly to the
 * person choosing it. A leading "You <verb>" becomes "<Verbs>"; "You are the X: you <verb>..."
 * keeps the part after the colon the same way. Anything still addressed to "you", or empty, falls
 * back to `fallback` (the agent's category).
 */
const IRREGULAR_VERBS: Record<string, string> = { have: 'has', do: 'does', go: 'goes', be: 'is' }

function thirdPerson(verb: string): string {
  const lower = verb.toLowerCase()
  const irregular = IRREGULAR_VERBS[lower]
  if (irregular) return irregular
  if (/(s|x|z|ch|sh|o)$/.test(lower)) return `${lower}es`
  if (/[^aeiou]y$/.test(lower)) return `${lower.slice(0, -1)}ies`
  return `${lower}s`
}

/* Verbs an agent's instructions commonly list after the first ("You triage tickets and draft
   replies"): one of these straight after "and" or a comma is conjugated too. */
const LISTED_VERBS = new Set(
  (
    'analyse analyze answer build check collect compile create draft escalate explain find flag gather handle keep manage ' +
    'monitor organise organize prepare propose reply research review route schedule send suggest summarise summarize take ' +
    'track triage update write'
  ).split(' '),
)

function conjugateListedVerbs(text: string): string {
  return text.replace(/(,\s*(?:and\s+)?|\s+and\s+)([a-z]+)\b/g, (match, joiner: string, word: string) =>
    LISTED_VERBS.has(word) ? `${joiner}${thirdPerson(word)}` : match,
  )
}

function capitalise(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1)
}

// @find: describe agent, rewrite agent summary, you to third person
export function describeAgent(summary: string | null | undefined, fallback: string): string {
  const clean = (summary ?? '').replace(/\s+/g, ' ').trim()
  if (!clean) return fallback
  let rest = clean
  if (/^you are\b/i.test(rest)) {
    const after = /:\s*you\s+(.+)$/i.exec(rest)
    if (!after) return fallback
    rest = `you ${after[1]}`
  }
  const lead = /^you\s+([a-z]+)\b(.*)$/i.exec(rest)
  if (lead) {
    const verb = lead[1]!
    if (/^(are|were|will|can|should|must|may|might|would|could)$/i.test(verb)) return fallback
    rest = `${thirdPerson(verb)}${conjugateListedVerbs(lead[2] ?? '')}`
  } else if (/^you\b/i.test(rest)) {
    return fallback
  }
  // The first sentence is enough for a tooltip.
  const sentence = /^(.+?[.!?])(\s|$)/.exec(rest)
  return capitalise((sentence ? sentence[1]! : rest).trim())
}

// @find: agent description, welcome screen text, category fallback
/**
 * What an agent does, in one line written about it: the description somebody wrote for it, else a
 * line derived from its instructions (describeAgent), else `fallback`. Every place that says what
 * an agent does uses this, so a card, a page header and a chat chip never disagree.
 */
export function agentDescription(
  agent: { description?: string | null; summary?: string | null },
  fallback = '',
): string {
  const written = (agent.description ?? '').replace(/\s+/g, ' ').trim()
  return written || describeAgent(agent.summary, fallback)
}
