import { ShieldCheck } from 'lucide-react'
import { Card } from '../../components/ui'
import { formatInr, parsePaise } from '../../lib/money'
import type { Limits } from '../../types'

const R = 44
const C = 2 * Math.PI * R

/** Today's transfer allowance as a ring. The numbers come from the same query the server uses to enforce it. */
export function LimitCard({ limits }: { limits: Limits }) {
  const daily = parsePaise(limits.daily)
  const sent = parsePaise(limits.sentToday)
  // Integer maths on paise, then one division for display: 1 decimal place of percent.
  const usedPercent = daily > 0n ? Number((sent * 1000n) / daily) / 10 : 0
  const dash = (Math.min(100, usedPercent) / 100) * C
  const ringColor = usedPercent >= 90 ? 'rgb(251 113 133)' : usedPercent >= 60 ? 'rgb(251 191 36)' : 'rgb(45 212 191)'

  return (
    <Card eyebrow="Daily transfer limit" title="Today's allowance" className="h-full">
      <div className="flex items-center gap-6">
        <div className="relative h-28 w-28 shrink-0">
          <svg viewBox="0 0 100 100" className="h-full w-full -rotate-90" aria-hidden>
            <circle cx="50" cy="50" r={R} fill="none" stroke="rgb(255 255 255 / 0.07)" strokeWidth="8" />
            <circle
              cx="50"
              cy="50"
              r={R}
              fill="none"
              stroke={ringColor}
              strokeWidth="8"
              strokeLinecap="round"
              strokeDasharray={`${dash} ${C}`}
              className="transition-[stroke-dasharray] duration-700"
            />
          </svg>
          <div className="absolute inset-0 flex flex-col items-center justify-center">
            <span className="font-mono text-lg font-semibold text-white">{usedPercent}%</span>
            <span className="font-mono text-[10px] uppercase tracking-wider text-slate-500">used</span>
          </div>
        </div>
        <dl className="min-w-0 flex-1 space-y-2.5 text-sm">
          <div>
            <dt className="text-xs text-slate-500">Left to send today</dt>
            <dd className="font-mono text-xl font-semibold text-teal-300" data-testid="remaining-today">
              {formatInr(limits.remainingToday)}
            </dd>
          </div>
          <div className="flex justify-between gap-2">
            <dt className="text-slate-500">Sent today</dt>
            <dd className="font-mono text-slate-200">{formatInr(limits.sentToday)}</dd>
          </div>
          <div className="flex justify-between gap-2">
            <dt className="text-slate-500">Per payment</dt>
            <dd className="font-mono text-slate-200">{formatInr(limits.perTransfer)}</dd>
          </div>
        </dl>
      </div>
      <p className="mt-4 flex items-start gap-2 text-xs text-slate-500">
        <ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0 text-teal-400/70" aria-hidden />
        Checked under a database row lock, so even parallel payments cannot exceed {formatInr(limits.daily)} a day.
      </p>
    </Card>
  )
}
