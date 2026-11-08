import { lazy, Suspense, type ReactNode } from 'react'
import { Route, Routes } from 'react-router-dom'
import { PublicOnly, RequireAuth, RequireCustomer } from '../features/auth/guards'
import { LoginPage } from '../features/auth/LoginPage'
import { RegisterPage } from '../features/auth/RegisterPage'
import { ActivityPage } from '../features/wallet/ActivityPage'
import { AddMoneyPage } from '../features/wallet/AddMoneyPage'
import { OverviewPage } from '../features/wallet/OverviewPage'
import { SendMoneyPage } from '../features/wallet/SendMoneyPage'
import { AppLayout, NotFoundPage } from './AppLayout'

// Split out of the main bundle: the landing page (signed-out visitors only) and the receipt and engineering pages,
// which most sessions never open. The everyday wallet screens stay in the first download.
const LandingPage = lazy(() => import('../features/landing/LandingPage').then((m) => ({ default: m.LandingPage })))
const TransactionPage = lazy(() =>
  import('../features/wallet/TransactionPage').then((m) => ({ default: m.TransactionPage })),
)
const LabPage = lazy(() => import('../features/engineering/LabPage').then((m) => ({ default: m.LabPage })))
const InspectorPage = lazy(() =>
  import('../features/engineering/InspectorPage').then((m) => ({ default: m.InspectorPage })),
)

function Loading({ children }: { children: ReactNode }) {
  return (
    <Suspense
      fallback={
        <p role="status" className="p-8 font-mono text-xs text-slate-500">
          Loading…
        </p>
      }
    >
      {children}
    </Suspense>
  )
}

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<PublicOnly />}>
        <Route path="/welcome" element={<Loading><LandingPage /></Loading>} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
      </Route>

      <Route element={<RequireAuth />}>
        <Route element={<AppLayout />}>
          <Route element={<RequireCustomer />}>
            <Route index element={<OverviewPage />} />
            <Route path="/send" element={<SendMoneyPage />} />
            <Route path="/add-money" element={<AddMoneyPage />} />
            <Route path="/activity" element={<ActivityPage />} />
            <Route path="/transactions/:transactionId" element={<Loading><TransactionPage /></Loading>} />
            <Route path="/lab" element={<Loading><LabPage /></Loading>} />
          </Route>
          {/* The inspector shows this tab's own traffic, so any signed-in role may open it. */}
          <Route path="/inspector" element={<Loading><InspectorPage /></Loading>} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Route>
    </Routes>
  )
}
