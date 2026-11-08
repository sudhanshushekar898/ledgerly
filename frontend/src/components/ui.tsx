import type { LucideIcon } from 'lucide-react'
import { Check, CircleAlert, CircleCheck, Copy, Info, Loader2 } from 'lucide-react'
import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode } from 'react'
import { forwardRef, useId, useState } from 'react'

/* Dark "console" design system: ink surfaces, hairline borders, teal accent, monospace for anything technical. */

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger'

const VARIANTS: Record<Variant, string> = {
  primary: 'bg-teal-400 text-ink-950 shadow-[0_0_24px_-6px_rgb(45_212_191/0.6)] hover:bg-teal-300 focus-visible:outline-teal-300',
  secondary: 'bg-white/[0.06] text-slate-100 ring-1 ring-inset ring-white/10 hover:bg-white/10 focus-visible:outline-teal-300',
  ghost: 'text-slate-400 hover:bg-white/5 hover:text-slate-100 focus-visible:outline-teal-300',
  danger: 'bg-rose-500 text-white hover:bg-rose-400 focus-visible:outline-rose-300',
}

const BASE =
  'inline-flex items-center justify-center gap-2 rounded-lg px-4 py-2.5 text-sm font-semibold transition focus-visible:outline-2 focus-visible:outline-offset-2'

export function Button({
  variant = 'primary',
  loading = false,
  loadingText = 'Please wait…',
  icon: Icon,
  className = '',
  children,
  disabled,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; loading?: boolean; loadingText?: string; icon?: LucideIcon }) {
  return (
    <button
      className={`${BASE} disabled:cursor-not-allowed disabled:opacity-40 disabled:shadow-none ${VARIANTS[variant]} ${className}`}
      disabled={disabled || loading}
      {...rest}
    >
      {loading ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : Icon && <Icon className="h-4 w-4" aria-hidden />}
      {loading ? loadingText : children}
    </button>
  )
}

/** Same look as Button, for router links. */
export const linkButton = (variant: Variant = 'primary') => `${BASE} ${VARIANTS[variant]}`

const FIELD =
  'mt-1.5 block w-full rounded-lg border-0 bg-white/[0.04] px-3 py-2.5 text-sm text-slate-100 ring-1 ring-inset ring-white/10 placeholder:text-slate-500 focus:bg-white/[0.06] focus:ring-2 focus:ring-inset focus:ring-teal-400 aria-[invalid=true]:ring-rose-400'

export const TextField = forwardRef<
  HTMLInputElement,
  InputHTMLAttributes<HTMLInputElement> & { label: string; error?: string; hint?: string; prefix?: string }
>(function TextField({ label, error, hint, prefix, className = '', ...rest }, ref) {
  const id = useId()
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined
  return (
    <div className={className}>
      <label htmlFor={id} className="block text-sm font-medium text-slate-300">
        {label}
      </label>
      <div className="relative">
        {prefix && (
          <span className="pointer-events-none absolute inset-y-0 left-0 mt-1.5 flex items-center pl-3 text-sm text-slate-500" aria-hidden>
            {prefix}
          </span>
        )}
        <input
          id={id}
          ref={ref}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={`${FIELD} ${prefix ? 'pl-7' : ''}`}
          {...rest}
        />
      </div>
      {hint && !error && (
        <p id={`${id}-hint`} className="mt-1.5 text-xs text-slate-500">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} className="mt-1.5 text-sm text-rose-300">
          {error}
        </p>
      )}
    </div>
  )
})

export function Alert({ tone = 'error', children }: { tone?: 'error' | 'success' | 'info' | 'warning'; children: ReactNode }) {
  const styles = {
    error: { box: 'bg-rose-500/10 text-rose-200 ring-rose-400/25', Icon: CircleAlert },
    success: { box: 'bg-emerald-500/10 text-emerald-200 ring-emerald-400/25', Icon: CircleCheck },
    info: { box: 'bg-sky-500/10 text-sky-200 ring-sky-400/25', Icon: Info },
    warning: { box: 'bg-amber-500/10 text-amber-200 ring-amber-400/25', Icon: CircleAlert },
  }[tone]
  return (
    <div role={tone === 'error' ? 'alert' : 'status'} className={`flex items-start gap-2.5 rounded-lg p-3 text-sm ring-1 ${styles.box}`}>
      <styles.Icon className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
      <div className="min-w-0">{children}</div>
    </div>
  )
}

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return (
    <div role="status" aria-label={label} className="flex justify-center p-10">
      <Loader2 className="h-6 w-6 animate-spin text-teal-300" aria-hidden />
    </div>
  )
}

export function Skeleton({ className = '' }: { className?: string }) {
  return <div aria-hidden className={`animate-pulse rounded-md bg-white/[0.06] ${className}`} />
}

/** Small uppercase monospace label above a value or section ("BALANCE", "LEDGER ENTRIES"). */
export function Eyebrow({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <p className={`font-mono text-[11px] font-medium uppercase tracking-[0.14em] text-slate-500 ${className}`}>{children}</p>
}

export function Card({
  title,
  eyebrow,
  action,
  children,
  padded = true,
  className = '',
}: {
  title?: string
  eyebrow?: string
  action?: ReactNode
  children: ReactNode
  padded?: boolean
  className?: string
}) {
  return (
    <section
      className={`overflow-hidden rounded-2xl bg-ink-900/80 ring-1 ring-white/[0.07] shadow-[0_24px_48px_-32px_rgb(0_0_0/0.8)] ${className}`}
    >
      {(title || action || eyebrow) && (
        <div className="flex items-center justify-between gap-3 border-b border-white/[0.06] px-5 py-3.5">
          <div>
            {eyebrow && <Eyebrow>{eyebrow}</Eyebrow>}
            {title && <h2 className="text-sm font-semibold text-slate-100">{title}</h2>}
          </div>
          {action}
        </div>
      )}
      <div className={padded ? 'p-5' : ''}>{children}</div>
    </section>
  )
}

export type Tone = 'neutral' | 'green' | 'red' | 'amber' | 'teal' | 'violet'

const TONES: Record<Tone, string> = {
  neutral: 'bg-white/5 text-slate-300 ring-white/10',
  green: 'bg-emerald-400/10 text-emerald-300 ring-emerald-400/25',
  red: 'bg-rose-400/10 text-rose-300 ring-rose-400/25',
  amber: 'bg-amber-400/10 text-amber-300 ring-amber-400/25',
  teal: 'bg-teal-400/10 text-teal-300 ring-teal-400/25',
  violet: 'bg-violet-400/10 text-violet-300 ring-violet-400/25',
}

export function Badge({ tone = 'neutral', mono = false, children }: { tone?: Tone; mono?: boolean; children: ReactNode }) {
  return (
    <span
      className={`inline-flex items-center gap-1 whitespace-nowrap rounded-md px-1.5 py-0.5 text-[11px] font-medium ring-1 ring-inset ${mono ? 'font-mono' : ''} ${TONES[tone]}`}
    >
      {children}
    </span>
  )
}

export function EmptyState({ icon: Icon, title, children }: { icon: LucideIcon; title: string; children?: ReactNode }) {
  return (
    <div className="flex flex-col items-center px-6 py-10 text-center">
      <span className="flex h-11 w-11 items-center justify-center rounded-xl bg-white/[0.04] text-slate-500 ring-1 ring-white/10">
        <Icon className="h-5 w-5" aria-hidden />
      </span>
      <p className="mt-3 text-sm font-semibold text-slate-200">{title}</p>
      {children && <div className="mt-1 max-w-sm text-sm text-slate-500">{children}</div>}
    </div>
  )
}

export function PageHeader({ eyebrow, title, subtitle, actions }: { eyebrow?: string; title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div>
        {eyebrow && <Eyebrow className="mb-1.5 text-teal-300/80">{eyebrow}</Eyebrow>}
        <h1 className="text-2xl font-semibold tracking-tight text-white">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-slate-400">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  )
}

const AVATAR_COLORS = [
  'bg-teal-400/15 text-teal-300 ring-teal-400/30',
  'bg-violet-400/15 text-violet-300 ring-violet-400/30',
  'bg-amber-400/15 text-amber-300 ring-amber-400/30',
  'bg-sky-400/15 text-sky-300 ring-sky-400/30',
  'bg-rose-400/15 text-rose-300 ring-rose-400/30',
  'bg-emerald-400/15 text-emerald-300 ring-emerald-400/30',
]

/** Initials on a colour picked from the name, so the same person always gets the same colour. */
export function Avatar({ name, size = 'md' }: { name?: string; size?: 'sm' | 'md' | 'lg' }) {
  const safe = name?.trim() || '?'
  const initials = safe.split(/\s+/).slice(0, 2).map((part) => part[0]?.toUpperCase()).join('')
  const hash = [...safe].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7)
  const dims = { sm: 'h-8 w-8 text-[11px]', md: 'h-10 w-10 text-xs', lg: 'h-14 w-14 text-base' }[size]
  return (
    <span aria-hidden className={`inline-flex shrink-0 items-center justify-center rounded-full font-semibold ring-1 ${dims} ${AVATAR_COLORS[hash % AVATAR_COLORS.length]}`}>
      {initials}
    </span>
  )
}

/** A monospace value (id, key, hash) with a copy button. */
export function MonoValue({ value, display, label }: { value: string; display?: string; label: string }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(value)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // Clipboard can be unavailable (insecure context, permissions): the value is still selectable.
    }
  }
  return (
    <span className="inline-flex max-w-full items-center gap-1.5">
      <code className="truncate rounded bg-white/[0.04] px-1.5 py-0.5 font-mono text-xs text-slate-300 ring-1 ring-white/[0.06]" title={value}>
        {display ?? value}
      </code>
      <button
        type="button"
        onClick={() => void copy()}
        aria-label={`Copy ${label}`}
        className="rounded p-1 text-slate-500 transition hover:bg-white/5 hover:text-slate-200"
      >
        {copied ? <Check className="h-3.5 w-3.5 text-emerald-300" aria-hidden /> : <Copy className="h-3.5 w-3.5" aria-hidden />}
      </button>
    </span>
  )
}

export function Kbd({ children }: { children: ReactNode }) {
  return (
    <kbd className="rounded border border-white/10 bg-white/[0.04] px-1.5 py-0.5 font-mono text-[10px] text-slate-400">{children}</kbd>
  )
}

export function Stat({ label, value, hint, tone = 'neutral' }: { label: string; value: ReactNode; hint?: ReactNode; tone?: 'neutral' | 'green' | 'red' }) {
  const color = { neutral: 'text-white', green: 'text-emerald-300', red: 'text-rose-300' }[tone]
  return (
    <div className="rounded-xl bg-white/[0.03] p-4 ring-1 ring-white/[0.06]">
      <Eyebrow>{label}</Eyebrow>
      <p className={`mt-2 font-mono text-lg font-semibold tabular-nums ${color}`}>{value}</p>
      {hint && <p className="mt-0.5 text-xs text-slate-500">{hint}</p>}
    </div>
  )
}
