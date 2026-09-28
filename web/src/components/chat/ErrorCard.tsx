import { Notice } from '../ui'
import { sentenceCase } from '../../lib/format'
import type { ChatMessage } from '../../lib/queries'

/** Something the coordinator or an agent could not do, said plainly. */
export function ErrorCard({ message }: { message: ChatMessage }) {
  const reason = message.detail.reason
  return (
    <Notice tone="warning" live>
      {message.content || (reason ? sentenceCase(reason) : 'Something went wrong with this request.')}
    </Notice>
  )
}
