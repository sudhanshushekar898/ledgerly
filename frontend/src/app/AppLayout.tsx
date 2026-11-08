import type { LucideIcon } from 'lucide-react'
import { Activity, ArrowUpRight, FlaskConical, LayoutGrid, LogOut, Plus, ReceiptText, Search } from 'lucide-react'
import { Link, NavLink, Outlet } from 'react-router-dom'
import { CommandPalette, palette } from '../components/CommandPalette'
import { Avatar, Kbd } from '../components/ui'
import { useAuth } from '../features/auth/useAuth'
import { LiveIndicator, NotificationBell, Toaster } from '../features/notifications/LiveWidgets'
import { useLiveEvents } from '../features/notifications/useLiveEvents'
import { SystemStatus } from './SystemStatus'

interface NavItem {
  to: string
  label: string
  icon: LucideIcon
}

const WALLET_NAV: NavItem[] = [
  { to: '/', label: 'Overview', icon: LayoutGrid },
  { to: '/send', label: 'Send', icon: ArrowUpRight },
  { to: '/add-money', label: 'Add money', icon: Plus },
  { to: '/activity', label: 'Activity', icon: ReceiptText },
]

const ENGINEERING_NAV: NavItem[] = [
  { to: '/inspector', label: 'API inspector', icon: Activity },
  { to: '/lab', label: 'Resilience lab', icon: FlaskConical },
]

const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)

export function Logo({ light = true }: { light?: boolean }) {
  return (
    <span className="relative flex items-center gap-2.5">
      <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-teal-300 via-teal-500 to-cyan-700 font-mono text-sm font-bold text-ink-950 shadow-[0_0_20px_-4px_rgb(45_212_191/0.7)]">
        L
      </span>
      <span className={`text-[17px] font-semibold tracking-tight ${light ? 'text-white' : 'text-slate-900'}`}>Ledgerly</span>
      <span className="hidden rounded border border-teal-400/30 px-1 font-mono text-[9px] uppercase tracking-widest text-teal-300/80 sm:inline">ledger</span>
    </span>
  )
}

function SideLink({ to, label, icon: Icon }: NavItem) {
  return (
    <NavLink
      to={to}
      end={to === '/'}
      className={({ isActive }) =>
        `group relative flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition ${
          isActive ? 'bg-white/[0.06] text-white' : 'text-slate-400 hover:bg-white/[0.03] hover:text-slate-100'
        }`
      }
    >
      {({ isActive }) => (
        <>
          {isActive && <span className="absolute inset-y-1.5 left-0 w-0.5 rounded-full bg-teal-300" aria-hidden />}
          <Icon className={`h-4 w-4 ${isActive ? 'text-teal-300' : 'text-slate-500 group-hover:text-slate-300'}`} aria-hidden />
          {label}
        </>
      )}
    </NavLink>
  )
}

/** Console-style shell: sidebar on desktop, top bar plus bottom tabs on phones, command palette everywhere. */
export function AppLayout() {
  const { user, logout } = useAuth()
  const customer = user?.role === 'USER'
  const engineering = customer ? ENGINEERING_NAV : ENGINEERING_NAV.filter((item) => item.to === '/inspector')
  const live = useLiveEvents(customer)

  return (
    <div className="min-h-screen">
      <div className="pointer-events-none fixed inset-x-0 top-0 h-80 bg-[radial-gradient(ellipse_at_top,rgb(45_212_191/0.08),transparent_60%)]" aria-hidden />

      <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 flex-col border-r border-white/[0.06] bg-ink-900/60 backdrop-blur md:flex">
        <div className="flex items-center justify-between pl-5 pr-3 pt-5">
          <Link to="/" aria-label="Ledgerly home">
            <Logo />
          </Link>
          {customer && <NotificationBell />}
        </div>
        <button
          type="button"
          onClick={palette.open}
          className="mx-3 mt-6 flex items-center gap-2 rounded-lg bg-white/[0.03] px-3 py-2 text-left text-sm text-slate-500 ring-1 ring-white/[0.06] transition hover:text-slate-300"
        >
          <Search className="h-4 w-4" aria-hidden />
          <span className="flex-1">Search or run…</span>
          <Kbd>{isMac ? '⌘' : 'Ctrl'} K</Kbd>
        </button>
        <nav aria-label="Primary" className="mt-6 flex-1 space-y-6 overflow-y-auto px-3">
          {customer && (
            <div className="space-y-1">
              <p className="px-3 pb-1 font-mono text-[10px] uppercase tracking-[0.16em] text-slate-600">Wallet</p>
              {WALLET_NAV.map((item) => (
                <SideLink key={item.to} {...item} />
              ))}
            </div>
          )}
          <div className="space-y-1">
            <p className="px-3 pb-1 font-mono text-[10px] uppercase tracking-[0.16em] text-slate-600">Engineering</p>
            {engineering.map((item) => (
              <SideLink key={item.to} {...item} />
            ))}
          </div>
        </nav>
        <div className="space-y-2 px-3 pb-4">
          {customer && <LiveIndicator status={live} />}
          <SystemStatus />
          <div className="flex items-center gap-3 rounded-xl bg-white/[0.03] p-3 ring-1 ring-white/[0.06]">
            <Avatar name={user?.name} size="sm" />
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium text-slate-100">{user?.name}</p>
              <p className="truncate font-mono text-[11px] text-slate-500">{user?.email}</p>
            </div>
            <button
              type="button"
              onClick={() => void logout()}
              aria-label="Sign out"
              title="Sign out"
              className="rounded-lg p-2 text-slate-500 transition hover:bg-white/5 hover:text-slate-100"
            >
              <LogOut className="h-4 w-4" aria-hidden />
            </button>
          </div>
        </div>
      </aside>

      <header className="sticky top-0 z-20 flex items-center justify-between border-b border-white/[0.06] bg-ink-950/80 px-4 py-3 backdrop-blur md:hidden">
        <Link to="/" aria-label="Ledgerly home">
          <Logo />
        </Link>
        <div className="flex items-center gap-1">
          {customer && <NotificationBell align="right" />}
          <button type="button" onClick={palette.open} aria-label="Open command palette" className="rounded-lg p-2 text-slate-400 hover:bg-white/5">
            <Search className="h-5 w-5" aria-hidden />
          </button>
          <button type="button" onClick={() => void logout()} aria-label="Sign out" className="rounded-lg p-2 text-slate-400 hover:bg-white/5">
            <LogOut className="h-5 w-5" aria-hidden />
          </button>
        </div>
      </header>

      <main className="relative md:pl-64">
        <div className="mx-auto max-w-6xl px-4 pb-28 pt-6 sm:px-8 sm:pt-8 md:pb-10">
          <Outlet />
        </div>
      </main>

      {customer && (
        <nav aria-label="Bottom" className="fixed inset-x-0 bottom-0 z-20 grid grid-cols-5 border-t border-white/[0.06] bg-ink-950/90 backdrop-blur md:hidden">
          {[...WALLET_NAV, { to: '/lab', label: 'Lab', icon: FlaskConical }].map(({ to, label, icon: Icon }) => (
            <NavLink
              key={to}
              to={to}
              end={to === '/'}
              className={({ isActive }) => `flex flex-col items-center gap-1 py-2.5 text-[10px] font-medium ${isActive ? 'text-teal-300' : 'text-slate-500'}`}
            >
              <Icon className="h-5 w-5" aria-hidden />
              {label}
            </NavLink>
          ))}
        </nav>
      )}

      <CommandPalette customer={customer} />
      <Toaster />
    </div>
  )
}

export function NotFoundPage() {
  return (
    <div className="py-20 text-center">
      <p className="font-mono text-sm text-teal-300">404</p>
      <h1 className="mt-2 text-2xl font-semibold text-white">Page not found</h1>
      <p className="mt-2 text-slate-400">
        <Link to="/" className="font-medium text-teal-300 hover:underline">
          Back to your wallet
        </Link>
      </p>
    </div>
  )
}
