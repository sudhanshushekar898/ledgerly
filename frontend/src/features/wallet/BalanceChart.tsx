import type { MouseEvent } from 'react'
import { useId, useState } from 'react'
import { formatShortDate } from '../../lib/dates'
import { formatInr, parsePaise } from '../../lib/money'
import type { DayFlow } from '../../types'

const W = 640
const AREA_TOP = 12
const AREA_H = 132
const FLOW_TOP = AREA_TOP + AREA_H + 16
const FLOW_H = 44
const H = FLOW_TOP + FLOW_H + 4

/** Display only: drawing needs numbers. Every label and tooltip still shows the exact decimal string. */
const rupees = (amount: string) => Number(parsePaise(amount)) / 100

/**
 * Hand-drawn SVG: closing balance as an area chart, and daily money in (up) and out (down) as bars underneath,
 * the way trading tools show volume under price. No chart library, about 130 lines.
 */
export function BalanceChart({ series }: { series: DayFlow[] }) {
  const gradient = `area${useId().replace(/[^a-zA-Z0-9]/g, '')}`
  const [hover, setHover] = useState<number | null>(null)

  if (series.length === 0) return null
  const balances = series.map((d) => rupees(d.closingBalance))
  const ins = series.map((d) => rupees(d.moneyIn))
  const outs = series.map((d) => rupees(d.moneyOut))
  const maxBalance = Math.max(1, ...balances)
  const maxFlow = Math.max(1, ...ins, ...outs)
  const n = series.length
  const x = (i: number) => (n === 1 ? W / 2 : (i / (n - 1)) * W)
  const y = (value: number) => AREA_TOP + AREA_H - (value / maxBalance) * AREA_H
  const line = balances.map((b, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(b).toFixed(1)}`).join(' ')
  const area = `${line} L${x(n - 1).toFixed(1)},${AREA_TOP + AREA_H} L${x(0).toFixed(1)},${AREA_TOP + AREA_H} Z`
  const mid = FLOW_TOP + FLOW_H / 2
  const barWidth = Math.max(2, (W / n) * 0.42)
  const first = series[0]
  const last = series[n - 1]

  const onMove = (event: MouseEvent<SVGRectElement>) => {
    const box = event.currentTarget.getBoundingClientRect()
    const ratio = Math.min(1, Math.max(0, (event.clientX - box.left) / box.width))
    setHover(Math.round(ratio * (n - 1)))
  }
  const point = hover === null ? null : series[hover]

  return (
    <div className="relative">
      <svg
        viewBox={`0 0 ${W} ${H}`}
        className="h-auto w-full"
        role="img"
        aria-label={`Balance over the last ${n} days: from ${formatInr(first?.closingBalance ?? '0')} to ${formatInr(last?.closingBalance ?? '0')}`}
      >
        <defs>
          <linearGradient id={gradient} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="rgb(45 212 191)" stopOpacity="0.35" />
            <stop offset="100%" stopColor="rgb(45 212 191)" stopOpacity="0" />
          </linearGradient>
        </defs>
        {[0, 0.5, 1].map((t) => (
          <line key={t} x1={0} x2={W} y1={AREA_TOP + AREA_H * t} y2={AREA_TOP + AREA_H * t} stroke="rgb(255 255 255 / 0.06)" strokeDasharray="3 5" />
        ))}
        <path d={area} fill={`url(#${gradient})`} />
        <path d={line} fill="none" stroke="rgb(94 234 212)" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
        <line x1={0} x2={W} y1={mid} y2={mid} stroke="rgb(255 255 255 / 0.08)" />
        {series.map((d, i) => {
          const inH = (ins[i]! / maxFlow) * (FLOW_H / 2)
          const outH = (outs[i]! / maxFlow) * (FLOW_H / 2)
          return (
            <g key={d.date} opacity={hover === null || hover === i ? 1 : 0.45}>
              {inH > 0 && <rect x={x(i) - barWidth / 2} y={mid - inH} width={barWidth} height={inH} rx={1} fill="rgb(52 211 153)" />}
              {outH > 0 && <rect x={x(i) - barWidth / 2} y={mid} width={barWidth} height={outH} rx={1} fill="rgb(251 113 133)" />}
            </g>
          )
        })}
        {point && hover !== null && (
          <g>
            <line x1={x(hover)} x2={x(hover)} y1={AREA_TOP} y2={FLOW_TOP + FLOW_H} stroke="rgb(255 255 255 / 0.25)" strokeDasharray="2 3" />
            <circle cx={x(hover)} cy={y(balances[hover]!)} r={4.5} fill="rgb(10 15 28)" stroke="rgb(94 234 212)" strokeWidth={2} />
          </g>
        )}
        <rect x={0} y={0} width={W} height={H} fill="transparent" onMouseMove={onMove} onMouseLeave={() => setHover(null)} />
      </svg>

      <div className="mt-2 flex justify-between font-mono text-[10px] text-slate-500">
        <span>{first && formatShortDate(first.date)}</span>
        <span>{last && formatShortDate(last.date)}</span>
      </div>

      {point && hover !== null && (
        <div
          className="pointer-events-none absolute top-0 z-10 w-44 -translate-x-1/2 rounded-lg bg-ink-800/95 p-2.5 text-xs ring-1 ring-white/10 shadow-xl"
          style={{ left: `${Math.min(88, Math.max(12, (x(hover) / W) * 100))}%` }}
        >
          <p className="font-mono text-[10px] uppercase tracking-wider text-slate-500">{formatShortDate(point.date)}</p>
          <p className="mt-1 font-mono font-semibold text-white">{formatInr(point.closingBalance)}</p>
          <p className="mt-1 flex justify-between font-mono text-emerald-300">
            <span>in</span>
            <span>{formatInr(point.moneyIn)}</span>
          </p>
          <p className="flex justify-between font-mono text-rose-300">
            <span>out</span>
            <span>{formatInr(point.moneyOut)}</span>
          </p>
        </div>
      )}
    </div>
  )
}
