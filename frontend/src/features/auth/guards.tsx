import { ShieldCheck } from 'lucide-react'
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { EmptyState, Spinner } from '../../components/ui'
import { useAuth } from './useAuth'

/** Signed-in users only. */
export function RequireAuth() {
  const { status, user, signedOut } = useAuth()
  const location = useLocation()

  if (status === 'loading') return <Spinner label="Restoring session" />
  if (!user) {
    // Only an expired session or a deep link comes back to the same page after sign-in. After a deliberate
    // sign-out the next person starts fresh and never lands on someone else's last page.
    if (signedOut || location.pathname === '/') return <Navigate to="/welcome" replace />
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  return <Outlet />
}

/**
 * Wallet pages are for customers. Operators have no wallet (the API answers 403); their tools are API-only for
 * now. The server enforces this; the UI only explains it.
 */
export function RequireCustomer() {
  const { user } = useAuth()
  if (user?.role !== 'USER') {
    return (
      <EmptyState icon={ShieldCheck} title="Operator account">
        Operator tools (reconciliation, freeze, reversals) are available through the admin API.
      </EmptyState>
    )
  }
  return <Outlet />
}

/** Public pages (sign in, sign up) send signed-in users to the app instead. */
export function PublicOnly() {
  const { status, user } = useAuth()
  if (status === 'loading') return <Spinner label="Restoring session" />
  if (user) return <Navigate to="/" replace />
  return <Outlet />
}
