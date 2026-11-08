import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '../lib/errors'

export function createQueryClient({ retry = true }: { retry?: boolean } = {}) {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // A 4xx will not fix itself on retry; only transient failures are worth another attempt.
        retry: retry
          ? (failureCount, error) => !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failureCount < 2
          : false,
        refetchOnWindowFocus: false,
        staleTime: 10_000,
      },
      // Payments are never retried automatically: the person decides, and the Idempotency-Key makes it safe.
      mutations: { retry: false },
    },
  })
}
