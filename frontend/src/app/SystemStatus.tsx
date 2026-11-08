import { useQuery } from '@tanstack/react-query'

interface Health {
  up: boolean
  latencyMs: number
}

/** Readiness probe through the same origin as the app; not recorded in the API inspector (it would be noise). */
async function probe(): Promise<Health> {
  const startedAt = performance.now()
  try {
    const response = await fetch('/actuator/health/readiness', { headers: { Accept: 'application/json' } })
    const body = (await response.json()) as { status?: string }
    return { up: response.ok && body.status === 'UP', latencyMs: Math.round(performance.now() - startedAt) }
  } catch {
    return { up: false, latencyMs: Math.round(performance.now() - startedAt) }
  }
}

/** Live "is the backend healthy, and how fast" indicator, refreshed every 15 seconds. */
export function SystemStatus() {
  const health = useQuery({ queryKey: ['system', 'health'], queryFn: probe, refetchInterval: 15_000, retry: false, staleTime: 0 })
  const up = health.data?.up
  const label = health.isPending ? 'Checking API…' : up ? `API online · ${health.data?.latencyMs} ms` : 'API unreachable'

  return (
    <div role="status" aria-label="System status" className="flex items-center gap-2 rounded-lg bg-white/[0.03] px-3 py-2 ring-1 ring-white/[0.06]">
      <span className="relative flex h-2 w-2">
        {up && <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-60" />}
        <span className={`relative inline-flex h-2 w-2 rounded-full ${health.isPending ? 'bg-slate-500' : up ? 'bg-emerald-400' : 'bg-rose-400'}`} />
      </span>
      <span className="font-mono text-[11px] text-slate-400">{label}</span>
    </div>
  )
}
