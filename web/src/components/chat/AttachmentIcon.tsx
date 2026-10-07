import type { AttachmentKind } from '../../lib/attachments'

const LABEL: Record<AttachmentKind, string> = {
  pdf: 'PDF',
  document: 'DOC',
  presentation: 'PPT',
  spreadsheet: 'XLS',
  text: 'TXT',
  image: 'IMG',
}

/** A small page glyph with the kind of file written on it, coloured by kind in attachments.css. */
export function AttachmentIcon({ kind }: { kind: AttachmentKind | null }) {
  return (
    <span className="chat-attach-icon" data-kind={kind ?? 'unknown'} aria-hidden="true">
      <svg width="18" height="22" viewBox="0 0 18 22" fill="none">
        <path d="M2.5 1.5h9l4 4v15h-13z" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round" />
        <path d="M11.5 1.5v4h4" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round" />
      </svg>
      <span className="chat-attach-icon-label">{kind ? LABEL[kind] : 'FILE'}</span>
    </span>
  )
}
