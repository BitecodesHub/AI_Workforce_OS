// @find: answer block card, render answer card, card dispatch, email draft, table, chart, checklist, choices, AnswerBlockCard
// @what: Picks the card component for one validated answer block.
// @flow: Called by components/ui/Markdown.tsx for a code block that resolveCards read as a card; renders EmailDraftCard, TableCard, ChartCard, ChecklistCard or ChoicesCard.
import type { AnswerBlock } from '../../lib/answerBlocks'
import { ChartCard } from './ChartCard'
import { ChecklistCard } from './ChecklistCard'
import { ChoicesCard } from './ChoicesCard'
import { EmailDraftCard } from './EmailDraftCard'
import { TableCard } from './TableCard'

// @find: AnswerBlockCard, card for answer block
/**
 * Renders the card for one block by its kind. `index` is the card's place among the answer's cards
 * (resolveCards): a card that sends names itself by it, and finds by it what was already sent from
 * it. Pure dispatch; each card keeps its own view state.
 */
export function AnswerBlockCard({ block, index }: { block: AnswerBlock; index: number }) {
  switch (block.kind) {
    case 'email-draft':
      return <EmailDraftCard block={block} index={index} />
    case 'table':
      return <TableCard block={block} />
    case 'chart':
      return <ChartCard block={block} />
    case 'checklist':
      return <ChecklistCard block={block} index={index} />
    case 'choices':
      return <ChoicesCard block={block} index={index} />
  }
}
