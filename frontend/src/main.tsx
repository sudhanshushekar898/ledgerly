// Must stay the first import: configures Zod before any schema module is evaluated.
import './lib/zodConfig'
import { QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { AppRoutes } from './app/AppRoutes'
import { createQueryClient } from './app/queryClient'
import { bootstrapSession } from './features/auth/useAuth'
import './index.css'

const queryClient = createQueryClient()

// Restore the session from the refresh cookie before routes decide where to send the user.
void bootstrapSession()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
