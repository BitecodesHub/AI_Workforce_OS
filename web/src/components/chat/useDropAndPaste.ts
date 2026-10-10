// @find: drag and drop files, paste image, paste file, file drop, drop zone, screenshot paste
// @what: Hooks for attaching files by dropping onto the chat panel or pasting.
// @flow: Used by Composer.
import { useCallback, useRef, useState } from 'react'
import type { ClipboardEvent as ReactClipboardEvent, DragEvent } from 'react'

/*
 * Two more ways to attach a file besides the paperclip: dragging files onto the chat panel, and
 * pasting them (a screenshot, usually) into the text box. Only a drag that carries files reacts,
 * so dragging selected text around the page behaves as it always has.
 */

function carriesFiles(event: DragEvent): boolean {
  return Array.from(event.dataTransfer?.types ?? []).includes('Files')
}

// @find: useFileDrop, use file drop, drag and drop files, paste image, paste file, file drop
export function useFileDrop({ onFiles, disabled = false }: { onFiles: (files: File[]) => void; disabled?: boolean }) {
  const [dragging, setDragging] = useState(false)
  // dragenter and dragleave fire for every child the pointer crosses; the count says whether the
  // pointer is still somewhere inside the panel.
  const depth = useRef(0)

  const onDragEnter = useCallback(
    (event: DragEvent) => {
      if (!carriesFiles(event)) return
      event.preventDefault()
      depth.current += 1
      if (!disabled) setDragging(true)
    },
    [disabled],
  )

  const onDragOver = useCallback(
    (event: DragEvent) => {
      if (!carriesFiles(event)) return
      // Refusing the default even when disabled stops the browser leaving the page to open the file.
      event.preventDefault()
      if (event.dataTransfer) event.dataTransfer.dropEffect = disabled ? 'none' : 'copy'
    },
    [disabled],
  )

  const onDragLeave = useCallback((event: DragEvent) => {
    if (!carriesFiles(event)) return
    depth.current = Math.max(0, depth.current - 1)
    if (depth.current === 0) setDragging(false)
  }, [])

  const onDrop = useCallback(
    (event: DragEvent) => {
      if (!carriesFiles(event)) return
      event.preventDefault()
      depth.current = 0
      setDragging(false)
      if (disabled) return
      const files = Array.from(event.dataTransfer?.files ?? [])
      if (files.length > 0) onFiles(files)
    },
    [disabled, onFiles],
  )

  return { dragging: dragging && !disabled, bind: { onDragEnter, onDragOver, onDragLeave, onDrop } }
}

const EXTENSION: Record<string, string> = {
  'image/png': 'png',
  'image/jpeg': 'jpg',
  'image/webp': 'webp',
  'image/gif': 'gif',
  'image/heic': 'heic',
  'image/heif': 'heif',
}

let pastedImages = 0

// @find: filesFromPaste, files from paste, drag and drop files, paste image, paste file, file drop
/**
 * The files in a paste, or none. A screenshot arrives with no name (or Chrome's generic
 * "image.png"), so it is given one, "pasted-image-1.png", the person can recognise in the list.
 * The composer calls this in onPaste and prevents the default only when files came back, so
 * pasting text is untouched.
 */
export function filesFromPaste(event: ClipboardEvent | ReactClipboardEvent): File[] {
  const data = event.clipboardData
  if (!data) return []
  const fromItems = Array.from(data.items ?? [])
    .filter((item) => item.kind === 'file')
    .map((item) => item.getAsFile())
    .filter((file): file is File => file !== null)
  const files = fromItems.length > 0 ? fromItems : Array.from(data.files ?? [])
  return files.map((file) => {
    if (file.name && file.name !== 'image.png') return file
    if (!file.type.startsWith('image/')) return file.name ? file : new File([file], 'pasted-file', { type: file.type })
    pastedImages += 1
    const extension = EXTENSION[file.type] ?? 'png'
    return new File([file], `pasted-image-${pastedImages}.${extension}`, { type: file.type, lastModified: file.lastModified })
  })
}
