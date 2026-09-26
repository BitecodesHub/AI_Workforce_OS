import { createContext, useCallback, useContext, useState } from 'react'
import type { ReactNode } from 'react'

/*
 * Feedback for an action that finished.
 *
 * Every action a person takes gets an answer: saved, approved, uploaded, or what went wrong.
 * An action that succeeds silently is indistinguishable from one that did nothing, and people
 * respond to that uncertainty by clicking again - which, for "approve and send", is the one thing
 * they must not do.
 *
 * Messages are announced to screen readers through a live region, and they leave on their own
 * after a few seconds, except errors, which stay until dismissed because somebody may need to
 * read them twice.
 */

type Tone = 'success' | 'error' | 'info'
type Toast = { id: number; tone: Tone; message: string }

const ToastContext = createContext<(tone: Tone, message: string) => void>(() => {})

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])

  const dismiss = useCallback((id: number) => setToasts((all) => all.filter((t) => t.id !== id)), [])

  const push = useCallback(
    (tone: Tone, message: string) => {
      const id = Date.now() + Math.random()
      setToasts((all) => [...all.slice(-3), { id, tone, message }])
      if (tone !== 'error') setTimeout(() => dismiss(id), 4500)
    },
    [dismiss],
  )

  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="toast-region" role="status" aria-live="polite">
        {toasts.map((toast) => (
          <div key={toast.id} className={`toast toast-${toast.tone}`}>
            <span>{toast.message}</span>
            <button type="button" className="toast-close" aria-label="Dismiss" onClick={() => dismiss(toast.id)}>
              <svg width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden="true">
                <path d="M2 2l8 8M10 2l-8 8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
              </svg>
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

export function useToast() {
  const push = useContext(ToastContext)
  return {
    success: (message: string) => push('success', message),
    error: (message: string) => push('error', message),
    info: (message: string) => push('info', message),
  }
}
