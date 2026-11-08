import { Activity, ShieldCheck, Trash2 } from 'lucide-react'
import { Badge, Button, Card, EmptyState, PageHeader, Stat } from '../../components/ui'
import type { ApiCallRecord } from '../../lib/inspector'
import { callStats, inspector, useApiCalls } from '../../lib/inspector'

const pad = (n: number, width = 2) => String(n).padStart(width, '0')

function clock(at: number): string {
  const d = new Date(at)
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`
}

function statusTone(status: number): 'green' | 'amber' | 'red' {
  if (status >= 200 && status < 300) return 'green'
  if (status >= 400 && status < 500) return 'amber'
  return 'red'
}

/** Latency of the most recent calls, oldest on the left. */
function Sparkline({ calls }: { calls: readonly ApiCallRecord[] }) {
  const recent = calls.slice(0, 60).reverse()
  if (recent.length < 2) return null
  const max = Math.max(1, ...recent.map((c) => c.durationMs))
  const points = recent.map((c, i) => `${((i / (recent.length - 1)) * 300).toFixed(1)},${(38 - (c.durationMs / max) * 34).toFixed(1)}`).join(' ')
  return (
    <svg viewBox="0 0 300 40" className="h-10 w-full" role="img" aria-label={`Latency of the last ${recent.length} calls, peak ${max} ms`}>
      <polyline points={points} fill="none" stroke="rgb(94 234 212)" strokeWidth={1.5} strokeLinejoin="round" />
    </svg>
  )
}

export function InspectorPage() {
  const calls = useApiCalls()
  const stats = callStats(calls)

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Engineering · API inspector"
        title="API inspector"
        subtitle="Every request this tab has made: status, latency, request id, Idempotency-Key and whether the server replayed it."
        actions={
          <Button variant="secondary" icon={Trash2} onClick={inspector.clear} disabled={calls.length === 0}>
            Clear
          </Button>
        }
      />

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <Stat label="Requests" value={stats.count} />
        <Stat label="p50 latency" value={`${stats.p50} ms`} />
        <Stat label="p95 latency" value={`${stats.p95} ms`} />
        <Stat label="Replays" value={stats.replays} hint="answered from the idempotency store" />
        <Stat label="4xx" value={stats.rejected} hint="refused by a rule" />
        <Stat label="5xx / network" value={stats.failed} tone={stats.failed > 0 ? 'red' : 'neutral'} />
      </div>

      <Card eyebrow="Live" title="Latency, most recent calls" action={<span className="h-2 w-2 animate-pulse rounded-full bg-teal-300" aria-hidden />}>
        {calls.length < 2 ? <p className="text-sm text-slate-500">Use the app to see traffic here.</p> : <Sparkline calls={calls} />}
      </Card>

      <Card padded={false}>
        {calls.length === 0 ? (
          <EmptyState icon={Activity} title="No requests yet">
            Navigate around or run an experiment in the Resilience lab.
          </EmptyState>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full text-xs">
              <thead>
                <tr className="border-b border-white/[0.06] text-left font-mono text-[10px] uppercase tracking-[0.14em] text-slate-500">
                  <th className="px-4 py-2.5 font-medium">Time</th>
                  <th className="px-4 py-2.5 font-medium">Request</th>
                  <th className="px-4 py-2.5 font-medium">Status</th>
                  <th className="px-4 py-2.5 text-right font-medium">Latency</th>
                  <th className="px-4 py-2.5 font-medium">Request id</th>
                  <th className="px-4 py-2.5 font-medium">Idempotency-Key</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-white/[0.04] font-mono">
                {calls.map((call) => (
                  <tr key={call.id} className="text-slate-300">
                    <td className="whitespace-nowrap px-4 py-2 text-slate-500">{clock(call.at)}</td>
                    <td className="max-w-[320px] px-4 py-2">
                      <span className="flex items-center gap-2">
                        <span className={`w-10 text-[10px] font-semibold ${call.method === 'POST' ? 'text-amber-300' : 'text-sky-300'}`}>{call.method}</span>
                        <span className="truncate" title={call.path}>{call.path}</span>
                      </span>
                    </td>
                    <td className="whitespace-nowrap px-4 py-2">
                      <span className="flex items-center gap-1.5">
                        <Badge mono tone={statusTone(call.status)}>{call.status || 'ERR'}</Badge>
                        {call.replayed && <Badge mono tone="violet">replayed</Badge>}
                        {call.code && <span className="text-[10px] text-slate-500">{call.code}</span>}
                      </span>
                    </td>
                    <td className="whitespace-nowrap px-4 py-2 text-right text-slate-400">{call.durationMs} ms</td>
                    <td className="whitespace-nowrap px-4 py-2 text-slate-500" title={call.requestId}>{call.requestId ? `${call.requestId.slice(0, 8)}…` : '—'}</td>
                    <td className="whitespace-nowrap px-4 py-2 text-slate-500" title={call.idempotencyKey}>
                      {call.idempotencyKey ? `${call.idempotencyKey.slice(0, 8)}…` : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      <p className="flex items-center justify-center gap-2 text-center text-xs text-slate-500">
        <ShieldCheck className="h-3.5 w-3.5 text-teal-400/70" aria-hidden />
        Kept in this tab's memory only. Bodies and tokens are never recorded, and email addresses in URLs are masked.
      </p>
    </div>
  )
}
