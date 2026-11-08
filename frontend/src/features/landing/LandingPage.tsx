import type { LucideIcon } from 'lucide-react'
import { ArrowRight, BookCheck, CircleCheck, FlaskConical, Gauge, Lock, RefreshCcw, ShieldCheck, Undo2, Zap } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Logo } from '../../app/AppLayout'
import { SystemStatus } from '../../app/SystemStatus'
import { Badge, Eyebrow, linkButton } from '../../components/ui'

/* Public product page. Every number on it is backed by an automated test or a recorded Resilience Lab run. */

const LAB_RUN = [
  { label: 'copy 1', outcome: 'replayed', ms: 255 },
  { label: 'copy 2', outcome: 'replayed', ms: 255 },
  { label: 'copy 3', outcome: 'executed', ms: 240 },
  { label: 'copy 4', outcome: 'replayed', ms: 254 },
  { label: 'copy 5', outcome: 'replayed', ms: 253 },
] as const

const GUARANTEES = [
  { value: '1 charge', text: 'for 5 identical payments in flight at once' },
  { value: '0', text: 'negative balances across 200 parallel transfers' },
  { value: '0', text: 'deadlocks in 100 opposing A↔B transfers' },
  { value: '₹0.00', text: 'sum of every balance in the ledger, always' },
]

const FEATURES: { icon: LucideIcon; title: string; text: string }[] = [
  { icon: BookCheck, title: 'Double-entry ledger', text: 'Every rupee is written twice. A deferred database trigger rejects any transaction whose debits and credits differ, and history is append-only.' },
  { icon: RefreshCcw, title: 'Idempotent payments', text: 'Each payment carries an Idempotency-Key. Retries, double-clicks and lost responses replay the first result instead of paying twice.' },
  { icon: Zap, title: 'Race-proof transfers', text: 'Accounts are row-locked in one fixed order, so parallel transfers can neither overdraw a wallet nor deadlock each other.' },
  { icon: Gauge, title: 'Limits checked under the lock', text: 'The ₹50,000 daily limit is checked inside the same transaction, after the lock: ten parallel payments cannot sneak past it.' },
  { icon: Undo2, title: 'Reversals, freeze, reconciliation', text: 'Operators undo payments with new opposite entries, freeze wallets with an audit trail, and reconcile the whole ledger to ₹0.00.' },
  { icon: ShieldCheck, title: 'Private by design', text: 'Tokens live in memory, refresh tokens rotate with reuse detection, other people’s balances are never returned, logs carry IDs only.' },
]

const PIPELINE = [
  { step: '01', title: 'Client', text: 'One Idempotency-Key per payment, resent on every retry' },
  { step: '02', title: 'Auth', text: 'RS256 JWT, deny by default, owner checks' },
  { step: '03', title: 'Lock', text: 'SELECT … FOR UPDATE in ascending id order' },
  { step: '04', title: 'Post', text: 'Debit + credit, balances updated, entries numbered' },
  { step: '05', title: 'Verify', text: 'Limits under the lock; debits = credits checked at COMMIT' },
  { step: '06', title: 'Publish', text: 'Transactional outbox → Kafka → live notification in the browser', next: false },
]

const STACK: { group: string; items: string[]; planned?: boolean }[] = [
  { group: 'Backend', items: ['Java 21', 'Spring Boot 3.5', 'Spring Security', 'PostgreSQL 16', 'Flyway', 'Hibernate', 'Apache Kafka', 'Server-sent events'] },
  { group: 'Quality', items: ['JUnit 5', 'Testcontainers', 'ArchUnit', 'Vitest', 'MSW', 'JaCoCo', 'Gatling'] },
  { group: 'Frontend', items: ['React 19', 'TypeScript', 'Vite', 'TanStack Query', 'Tailwind CSS'] },
  { group: 'Ops', items: ['Docker Compose', 'Nginx', 'GitHub Actions', 'gitleaks', 'Micrometer', 'Prometheus', 'Grafana'] },
  { group: 'Next', items: ['Redis rate limiting', '2 instances'], planned: true },
]

function Section({ id, eyebrow, title, children }: { id?: string; eyebrow: string; title: string; children: ReactNode }) {
  return (
    <section id={id} className="mx-auto max-w-6xl scroll-mt-20 px-4 py-20 sm:px-8">
      <Eyebrow className="text-teal-300/80">{eyebrow}</Eyebrow>
      <h2 className="mt-2 max-w-2xl text-3xl font-semibold tracking-tight text-white">{title}</h2>
      <div className="mt-10">{children}</div>
    </section>
  )
}

function LabConsole() {
  return (
    <div className="overflow-hidden rounded-2xl bg-ink-900/90 ring-1 ring-white/10 shadow-[0_40px_100px_-40px_rgb(45_212_191/0.45)]">
      <div className="flex items-center gap-2 border-b border-white/[0.06] px-4 py-3">
        <span className="h-2.5 w-2.5 rounded-full bg-rose-400/70" />
        <span className="h-2.5 w-2.5 rounded-full bg-amber-400/70" />
        <span className="h-2.5 w-2.5 rounded-full bg-emerald-400/70" />
        <span className="ml-2 font-mono text-[11px] text-slate-500">resilience-lab · double-click storm</span>
      </div>
      <div className="space-y-2 p-5 font-mono text-xs">
        <p className="text-slate-500">$ 5 × POST /api/v1/transfers  Idempotency-Key: 3971f911…</p>
        {LAB_RUN.map((row, i) => (
          <div key={row.label} className="grid animate-fade-up grid-cols-[56px_1fr_auto] items-center gap-3" style={{ animationDelay: `${150 + i * 120}ms` }}>
            <span className="text-slate-400">{row.label}</span>
            <span className="relative h-2 rounded-full bg-white/[0.05]">
              <span className={`absolute inset-y-0 left-[4%] rounded-full ${row.outcome === 'executed' ? 'bg-teal-400' : 'bg-violet-400'}`} style={{ width: `${(row.ms / 300) * 90}%` }} />
            </span>
            <span className={row.outcome === 'executed' ? 'text-teal-300' : 'text-violet-300'}>
              201 {row.outcome} <span className="text-slate-500">{row.ms}ms</span>
            </span>
          </div>
        ))}
        <p className="animate-fade-up pt-2 text-emerald-300" style={{ animationDelay: '900ms' }}>
          ✓ 1 executed · 4 replayed · 1 transaction id · balance moved ₹1.00 once
        </p>
      </div>
    </div>
  )
}

export function LandingPage() {
  return (
    <div className="min-h-screen overflow-x-hidden">
      <header className="sticky top-0 z-30 border-b border-white/[0.06] bg-ink-950/80 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center gap-6 px-4 py-3 sm:px-8">
          <Link to="/welcome" aria-label="Ledgerly home">
            <Logo />
          </Link>
          <nav aria-label="Sections" className="hidden flex-1 items-center gap-6 text-sm text-slate-400 md:flex">
            <a href="#features" className="hover:text-white">Features</a>
            <a href="#how" className="hover:text-white">How it works</a>
            <a href="#proof" className="hover:text-white">Proof</a>
            <a href="#stack" className="hover:text-white">Stack</a>
          </nav>
          <div className="ml-auto flex items-center gap-1 whitespace-nowrap sm:gap-2">
            <Link to="/login" className={`${linkButton('ghost')} px-3`}>Sign in</Link>
            <Link to="/register" className={`${linkButton('primary')} px-3`}>Create wallet</Link>
          </div>
        </div>
      </header>

      <main>
        <section className="relative">
          <div className="bg-grid absolute inset-0 [mask-image:radial-gradient(ellipse_at_top,black,transparent_70%)]" aria-hidden />
          <div className="absolute left-1/2 top-0 h-[420px] w-[720px] -translate-x-1/2 rounded-full bg-teal-400/10 blur-3xl" aria-hidden />
          <div className="relative mx-auto grid max-w-6xl items-center gap-12 px-4 pb-20 pt-16 sm:px-8 lg:grid-cols-[1.05fr_1fr] lg:pt-24">
            <div className="animate-fade-up">
              <div className="w-fit">
                <SystemStatus />
              </div>
              <h1 className="mt-6 text-4xl font-semibold leading-[1.1] tracking-tight text-white sm:text-5xl">
                The digital wallet that <span className="bg-gradient-to-r from-teal-300 to-cyan-400 bg-clip-text text-transparent">proves its own balance.</span>
              </h1>
              <p className="mt-5 max-w-xl text-base text-slate-400 sm:text-lg">
                Double-entry ledger, idempotent payments and race-proof transfers, built with Spring Boot, PostgreSQL and React. Open the
                Resilience Lab and try to break it.
              </p>
              <div className="mt-8 flex flex-wrap gap-3">
                <Link to="/register" className={linkButton('primary')}>
                  Create a wallet <ArrowRight className="h-4 w-4" aria-hidden />
                </Link>
                <Link to="/lab" className={linkButton('secondary')}>
                  <FlaskConical className="h-4 w-4" aria-hidden /> Open the Resilience Lab
                </Link>
              </div>
              <p className="mt-4 font-mono text-[11px] text-slate-500">Synthetic money only · no real payments are taken</p>
            </div>
            <LabConsole />
          </div>
        </section>

        <section aria-label="Guarantees" className="border-y border-white/[0.06] bg-ink-900/40">
          <dl className="mx-auto grid max-w-6xl grid-cols-2 gap-6 px-4 py-10 sm:px-8 lg:grid-cols-4">
            {GUARANTEES.map((g) => (
              <div key={g.text}>
                <dt className="font-mono text-3xl font-semibold text-white">{g.value}</dt>
                <dd className="mt-1 text-sm text-slate-400">{g.text}</dd>
              </div>
            ))}
          </dl>
          <p className="pb-6 text-center font-mono text-[11px] text-slate-600">Each figure is asserted by an automated test against real PostgreSQL</p>
        </section>

        <Section id="features" eyebrow="Features" title="Built like a payments system, not a CRUD app.">
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {FEATURES.map(({ icon: Icon, title, text }) => (
              <div key={title} className="rounded-2xl bg-ink-900/70 p-6 ring-1 ring-white/[0.07] transition hover:ring-teal-400/30">
                <span className="flex h-10 w-10 items-center justify-center rounded-lg bg-teal-400/10 text-teal-300 ring-1 ring-teal-400/25">
                  <Icon className="h-5 w-5" aria-hidden />
                </span>
                <h3 className="mt-4 font-semibold text-white">{title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-slate-400">{text}</p>
              </div>
            ))}
          </div>
        </Section>

        <Section id="how" eyebrow="How a payment works" title="Six steps, one database transaction.">
          <ol className="grid gap-4 md:grid-cols-3 lg:grid-cols-6">
            {PIPELINE.map((p) => (
              <li key={p.step} className={`relative rounded-xl p-4 ring-1 ${p.next ? 'border border-dashed border-amber-400/30 bg-white/[0.02] ring-transparent' : 'bg-ink-900/70 ring-white/[0.07]'}`}>
                <p className="font-mono text-xs text-teal-300">{p.step}</p>
                <p className="mt-2 font-semibold text-white">{p.title}</p>
                <p className="mt-1 font-mono text-[11px] leading-relaxed text-slate-400">{p.text}</p>
                {p.next && <div className="mt-3"><Badge tone="amber">next milestone</Badge></div>}
              </li>
            ))}
          </ol>
        </Section>

        <Section id="proof" eyebrow="Proof" title="Don’t trust the claims. Inspect them.">
          <div className="grid gap-6 lg:grid-cols-2">
            <div className="rounded-2xl bg-ink-900/70 ring-1 ring-white/[0.07]">
              <div className="border-b border-white/[0.06] px-5 py-3.5">
                <Eyebrow>Every receipt shows the ledger</Eyebrow>
                <p className="text-sm font-semibold text-white">Sent ₹250.50 to Ravi</p>
              </div>
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left font-mono text-[10px] uppercase tracking-wider text-slate-500">
                    <th className="px-5 py-2.5 font-medium">Account</th>
                    <th className="px-5 py-2.5 text-right font-medium">Debit</th>
                    <th className="px-5 py-2.5 text-right font-medium">Credit</th>
                  </tr>
                </thead>
                <tbody className="font-mono">
                  <tr className="border-t border-white/[0.04]">
                    <td className="px-5 py-3 font-sans text-slate-200">Your wallet</td>
                    <td className="px-5 py-3 text-right text-rose-300">₹250.50</td>
                    <td className="px-5 py-3 text-right text-slate-600">—</td>
                  </tr>
                  <tr className="border-t border-white/[0.04]">
                    <td className="px-5 py-3 font-sans text-slate-200">
                      Ravi’s wallet <span className="ml-1 inline-flex items-center gap-1 text-[11px] text-slate-500"><Lock className="h-3 w-3" aria-hidden />balance private</span>
                    </td>
                    <td className="px-5 py-3 text-right text-slate-600">—</td>
                    <td className="px-5 py-3 text-right text-emerald-300">₹250.50</td>
                  </tr>
                  <tr className="border-t border-white/10 bg-white/[0.02]">
                    <td className="px-5 py-3 text-[11px] uppercase tracking-wider text-slate-500">Totals</td>
                    <td className="px-5 py-3 text-right text-white">₹250.50</td>
                    <td className="px-5 py-3 text-right text-white">₹250.50</td>
                  </tr>
                </tbody>
              </table>
              <p className="flex items-center gap-2 px-5 py-4 text-sm text-emerald-300">
                <CircleCheck className="h-4 w-4" aria-hidden /> Balanced: debits = credits, enforced by the database
              </p>
            </div>
            <div className="rounded-2xl bg-ink-900/70 p-6 ring-1 ring-white/[0.07]">
              <Eyebrow>Resilience Lab</Eyebrow>
              <p className="mt-1 text-sm font-semibold text-white">Three live experiments against the real API</p>
              <ul className="mt-5 space-y-4">
                {[
                  ['Double-click storm', '5 identical payments, one key → charged once'],
                  ['Overdraw race', '6 parallel payments over the balance → never below zero'],
                  ['Lost response', 'Retry after a dropped response → original replayed'],
                ].map(([title, text]) => (
                  <li key={title} className="flex items-start gap-3">
                    <Badge tone="green">PASSED</Badge>
                    <span>
                      <span className="block text-sm font-medium text-slate-100">{title}</span>
                      <span className="block font-mono text-[11px] text-slate-500">{text}</span>
                    </span>
                  </li>
                ))}
              </ul>
              <Link to="/lab" className={`${linkButton('secondary')} mt-6`}>
                Run them yourself <ArrowRight className="h-4 w-4" aria-hidden />
              </Link>
            </div>
          </div>
        </Section>

        <Section id="stack" eyebrow="Stack" title="Boring, proven technology, used carefully.">
          <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-5">
            {STACK.map((s) => (
              <div key={s.group}>
                <p className="font-mono text-[11px] uppercase tracking-[0.16em] text-slate-500">
                  {s.group} {s.planned && <span className="text-amber-300/80">· planned</span>}
                </p>
                <ul className="mt-3 flex flex-wrap gap-1.5">
                  {s.items.map((item) => (
                    <li key={item} className={`rounded-md px-2 py-1 font-mono text-[11px] ring-1 ${s.planned ? 'text-slate-500 ring-white/[0.06]' : 'bg-white/[0.04] text-slate-300 ring-white/10'}`}>
                      {item}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </Section>

        <section className="mx-auto max-w-6xl px-4 pb-24 sm:px-8">
          <div className="relative overflow-hidden rounded-3xl bg-[linear-gradient(135deg,#0f766e_0%,#0e7490_45%,#1e1b4b_100%)] p-10 text-center ring-1 ring-white/15">
            <div className="bg-grid absolute inset-0 opacity-50" aria-hidden />
            <h2 className="relative text-3xl font-semibold tracking-tight text-white">Try to break it.</h2>
            <p className="relative mx-auto mt-3 max-w-lg text-teal-50/80">Create a wallet with synthetic money, then run the experiments and watch the API inspector.</p>
            <div className="relative mt-7 flex flex-wrap justify-center gap-3">
              <Link to="/register" className="inline-flex items-center gap-2 rounded-lg bg-white px-5 py-2.5 text-sm font-semibold text-ink-950 hover:bg-teal-50">
                Create a wallet <ArrowRight className="h-4 w-4" aria-hidden />
              </Link>
              <Link to="/login" className="inline-flex items-center rounded-lg px-5 py-2.5 text-sm font-semibold text-white ring-1 ring-white/30 hover:bg-white/10">
                Sign in
              </Link>
            </div>
          </div>
        </section>
      </main>

      <footer className="border-t border-white/[0.06]">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-3 px-4 py-6 font-mono text-[11px] text-slate-500 sm:px-8">
          <span>Ledgerly · portfolio project by Sudhanshu Shekhar</span>
          <span>Synthetic money only · not a real bank</span>
        </div>
      </footer>
    </div>
  )
}
