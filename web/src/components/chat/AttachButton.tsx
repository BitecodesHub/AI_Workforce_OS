// @find: attach file, paperclip, upload file in chat, file picker, composer button, attachment
// @what: The paperclip button beside the composer that opens the file picker.
// @flow: Used by Composer.
import { useRef } from 'react'
import { IconButton } from '../ui'
import { ACCEPT } from '../../lib/attachments'
import './attachments.css'

// @find: AttachButton, attach button, attach file, paperclip, upload file in chat, file picker
/**
 * The paperclip beside the composer's other tools. It opens the system file picker (several files
 * at once) and hands back whatever was chosen; the picker is reset afterwards, so choosing the same
 * file again after removing it still counts as a choice.
 */
export function AttachButton({ onFiles, disabled = false }: { onFiles: (files: File[]) => void; disabled?: boolean }) {
  const inputRef = useRef<HTMLInputElement>(null)

  return (
    <>
      <IconButton
        label="Attach files"
        title="Attach files (up to 10, 25 MB each)"
        className="chat-attach-button"
        disabled={disabled}
        onClick={() => inputRef.current?.click()}
      >
        <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path
            d="M13.5 7.6 8.2 12.9a3.3 3.3 0 0 1-4.7-4.7l5.6-5.6a2.2 2.2 0 0 1 3.1 3.1L6.6 11.3a1.1 1.1 0 0 1-1.6-1.6l5-5"
            stroke="currentColor"
            strokeWidth="1.3"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </IconButton>
      <input
        ref={inputRef}
        type="file"
        multiple
        accept={ACCEPT}
        className="visually-hidden"
        tabIndex={-1}
        aria-hidden="true"
        disabled={disabled}
        data-testid="chat-attach-input"
        onChange={(event) => {
          const files = Array.from(event.currentTarget.files ?? [])
          event.currentTarget.value = ''
          if (files.length > 0) onFiles(files)
        }}
      />
    </>
  )
}
