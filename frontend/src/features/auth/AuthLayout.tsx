import { BookCheck, RefreshCcw, ShieldCheck, Zap } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Logo } from '../../app/AppLayout'

const POINTS = [
  { icon: BookCheck, title: 'Double-entry ledger', text: 'Every rupee is recorded twice; the database rejects anything that does not balance.' },
  { icon: RefreshCcw, title: 'Idempotent payments', text: 'Retries and double-clicks replay the first result. Never charged twice.' },
  { icon: Zap, title: 'Race-proof', text: 'Row locks in a fixed order: parallel payments cannot overdraw or deadlock.' },
  { icon: ShieldCheck, title: 'Private by design', text: 'Tokens in memory only, rotating refresh cookies, no PII in logs.' },
]

/** Split screen shared by the signed-out pages: product story on the left, the form on the right. */
export function AuthLayout({ title, subtitle, children }: { title: string; subtitle?: ReactNode; children: ReactNode }) {
  return (
    <main className="flex min-h-screen">
      <div className="relative hidden w-[46%] flex-col overflow-hidden border-r border-white/[0.06] bg-ink-900 p-12 lg:flex">
        <div className="bg-grid absolute inset-0 [mask-image:radial-gradient(ellipse_at_top_left,black,transparent_75%)]" aria-hidden />
        <div className="absolute -left-24 -top-24 h-96 w-96 rounded-full bg-teal-400/15 blur-3xl" aria-hidden />
        <div className="absolute -bottom-32 right-0 h-96 w-96 rounded-full bg-indigo-500/15 blur-3xl" aria-hidden />
        <Link to="/welcome" aria-label="Ledgerly home" className="relative w-fit">
          <Logo />
        </Link>
        <div className="relative mt-auto max-w-md">
          <p className="font-mono text-[11px] uppercase tracking-[0.2em] text-teal-300/80">Event-driven digital wallet</p>
          <h2 className="mt-3 text-3xl font-semibold leading-tight tracking-tight text-white">Money that stays correct under pressure.</h2>
          <ul className="mt-8 space-y-5">
            {POINTS.map(({ icon: Icon, title, text }) => (
              <li key={title} className="flex gap-3">
                <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-white/[0.04] text-teal-300 ring-1 ring-white/10">
                  <Icon className="h-4 w-4" aria-hidden />
                </span>
                <span>
                  <span className="block text-sm font-medium text-slate-100">{title}</span>
                  <span className="block text-sm text-slate-400">{text}</span>
                </span>
              </li>
            ))}
          </ul>
        </div>
        <p className="relative mt-12 font-mono text-[11px] text-slate-500">Spring Boot · PostgreSQL · React · synthetic money only</p>
      </div>

      <div className="flex flex-1 items-center justify-center px-4 py-12 sm:px-8">
        <div className="w-full max-w-sm">
          <div className="lg:hidden">
            <Logo />
          </div>
          <h1 className="mt-8 text-2xl font-semibold tracking-tight text-white lg:mt-0">{title}</h1>
          {subtitle && <p className="mt-2 text-sm text-slate-400">{subtitle}</p>}
          <div className="mt-8">{children}</div>
        </div>
      </div>
    </main>
  )
}
