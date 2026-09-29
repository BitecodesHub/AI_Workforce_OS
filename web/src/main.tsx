import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import './styles/tokens.css'
import './styles/components.css'
import './styles/polish/index.css'
import { App } from './App'
import { RouterProvider } from './lib/router'
import { ToastProvider } from './lib/toast'
import { ApiError } from './lib/api'

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

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider>
        <ToastProvider>
          <App />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>
  </StrictMode>,
)
