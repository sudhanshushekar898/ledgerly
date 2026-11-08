import { ArrowUpRight, Eye, EyeOff, Plus, Snowflake } from 'lucide-react'
import { useLayoutEffect, useRef } from 'react'
import { Link } from 'react-router-dom'
import { Alert, Eyebrow, Skeleton } from '../../components/ui'
import { describeError } from '../../lib/errors'
import { formatInr, fromPaise, parsePaise } from '../../lib/money'
import { balancePrivacy, useBalanceHidden } from '../../lib/privacy'
import { useWallet } from './api'

/**
 * Animates the displayed amount from the previous value to the new one. It writes the DOM text directly (no React
 * state per frame) and always finishes on the exact formatted string: the animation never changes what is shown
 * at rest. Skipped when the user prefers reduced motion.
 */
function CountUp({ value }: { value: string }) {
  const ref = useRef<HTMLSpanElement>(null)
  const previous = useRef<bigint | null>(null)

  useLayoutEffect(() => {
    const element = ref.current
    const target = parsePaise(value)
    const from = previous.current ?? 0n
    previous.current = target
    const reduced = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
    if (!element || from === target || reduced || typeof window.requestAnimationFrame !== 'function') return

    const start = performance.now()
    const fromValue = Number(from)
    const toValue = Number(target)
    let frame = 0
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / 700)
      const eased = 1 - (1 - t) ** 3
      if (t < 1) {
        element.textContent = formatInr(fromPaise(BigInt(Math.round(fromValue + (toValue - fromValue) * eased))))
        frame = requestAnimationFrame(tick)
      } else {
        element.textContent = formatInr(value)
      }
    }
    frame = requestAnimationFrame(tick)
    return () => {
      cancelAnimationFrame(frame)
      element.textContent = formatInr(value)
    }
  }, [value])

  return <span ref={ref}>{formatInr(value)}</span>
}

const ACTION =
  'inline-flex items-center gap-2 rounded-lg px-4 py-2.5 text-sm font-semibold transition focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white'

export function BalanceCard() {
  const wallet = useWallet()
  const hidden = useBalanceHidden()

  if (wallet.isPending) return <Skeleton className="h-[244px] rounded-3xl" />
  if (wallet.isError) return <Alert>{describeError(wallet.error)}</Alert>

  const { balance, status, accountId } = wallet.data
  const frozen = status === 'FROZEN'
  const last4 = accountId.replace(/-/g, '').slice(-4).toUpperCase()

  return (
    <section
      aria-label="Balance"
      className="relative h-full overflow-hidden rounded-3xl bg-[linear-gradient(135deg,#0f766e_0%,#0e7490_45%,#1e1b4b_100%)] p-6 text-white ring-1 ring-white/15 shadow-[0_40px_80px_-40px_rgb(45_212_191/0.55)] sm:p-7"
    >
      <div className="bg-grid absolute inset-0 opacity-60 [mask-image:radial-gradient(ellipse_at_top_right,black,transparent_70%)]" aria-hidden />
      <div className="absolute -right-20 -top-24 h-64 w-64 rounded-full bg-teal-300/25 blur-3xl" aria-hidden />
      <div className="absolute -bottom-28 -left-10 h-56 w-56 rounded-full bg-indigo-500/25 blur-3xl" aria-hidden />

      <div className="relative flex items-start justify-between gap-4">
        <div>
          <Eyebrow className="text-teal-50/70">Available balance</Eyebrow>
          <p data-testid="balance" className="mt-3 font-mono text-4xl font-semibold tracking-tight tabular-nums sm:text-[44px]">
            {hidden ? <span aria-label="Balance hidden">₹ ••••••</span> : <CountUp value={balance} />}
          </p>
        </div>
        <button
          type="button"
          onClick={balancePrivacy.toggle}
          aria-label={hidden ? 'Show balance' : 'Hide balance'}
          className="rounded-lg p-2 text-teal-50/80 ring-1 ring-white/15 transition hover:bg-white/10"
        >
          {hidden ? <Eye className="h-4 w-4" aria-hidden /> : <EyeOff className="h-4 w-4" aria-hidden />}
        </button>
      </div>

      <div className="relative mt-6 flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="font-mono text-[10px] uppercase tracking-[0.22em] text-teal-50/60">INR wallet · Ledgerly</p>
          <p className="mt-1 font-mono text-sm tracking-[0.3em] text-teal-50/90">•••• •••• {last4}</p>
        </div>
        {frozen ? (
          <span className="inline-flex items-center gap-1.5 rounded-full bg-rose-500/20 px-3 py-1 text-xs font-semibold text-rose-100 ring-1 ring-rose-300/40">
            <Snowflake className="h-3.5 w-3.5" aria-hidden />
            Frozen: payments are paused. Contact support.
          </span>
        ) : (
          <span className="inline-flex items-center gap-1.5 rounded-full bg-white/10 px-2.5 py-1 font-mono text-[11px] text-teal-50 ring-1 ring-white/20">
            <span className="h-1.5 w-1.5 rounded-full bg-emerald-300" aria-hidden />
            ACTIVE
          </span>
        )}
      </div>

      <div className="relative mt-6 flex flex-wrap gap-3">
        <Link to="/send" className={`${ACTION} bg-white text-ink-950 hover:bg-teal-50`}>
          <ArrowUpRight className="h-4 w-4" aria-hidden />
          Send money
        </Link>
        <Link to="/add-money" className={`${ACTION} bg-white/10 text-white ring-1 ring-white/25 hover:bg-white/20`}>
          <Plus className="h-4 w-4" aria-hidden />
          Add money
        </Link>
      </div>
    </section>
  )
}
