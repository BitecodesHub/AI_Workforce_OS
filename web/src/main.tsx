import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
// Inter, self-hosted from the package (weight and optical-size axes). See styles/fonts.css.
import '@fontsource-variable/inter/opsz.css'
import './styles/tokens.css'
import './styles/components.css'
import './styles/polish/index.css'
import { App } from './App'
import { RouterProvider } from './lib/router'
import { ToastProvider } from './lib/toast'
import { ApiError } from './lib/api'
import { ErrorBoundary, clearReloadFlagWhenSettled, logRenderError, reloadOnce } from './components/ui/ErrorBoundary'

/*
 * Query defaults chosen for a decision-support interface.
 *
 * Retrying is right for a network blip and wrong for a 403 or a 404: repeating a request the
 * server has already refused wastes a round trip and delays the explanation the person needs. So
 * only failures the server marked retryable are retried.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      refetchOnWindowFocus: true,
      retry: (failureCount, error) =>
        error instanceof ApiError && error.retryable && error.status !== 0 ? failureCount < 2 : false,
    },
  },
})

/*
 * A tab left open across a deploy still names the old build's chunks, which no longer exist.
 * Vite reports a failed preload here; the page reloads once onto the new build rather than
 * showing a broken screen. If this build has already had its reload, the error goes on to the
 * screen's error boundary, which explains it. See components/ui/ErrorBoundary.tsx.
 */
window.addEventListener('vite:preloadError', (event) => {
  if (reloadOnce()) event.preventDefault()
})
clearReloadFlagWhenSettled()

/*
 * Every render failure is logged in one place, with the path it happened on: those an error
 * boundary caught and showed a fallback for, and any that escaped them all.
 */
createRoot(document.getElementById('root')!, {
  onCaughtError: (error, info) => logRenderError('caught', error, info.componentStack),
  onUncaughtError: (error, info) => logRenderError('uncaught', error, info.componentStack),
}).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider>
        <ToastProvider>
          {/* The last resort, for a failure outside any screen (the navigation bar, say): a page
              that says so and can be reloaded, rather than a blank window. */}
          <ErrorBoundary variant="page">
            <App />
          </ErrorBoundary>
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>
  </StrictMode>,
)
