import { useSyncExternalStore } from 'react'

/**
 * Every API call the app makes, kept in this tab's memory for the API inspector. Deliberately metadata only:
 * no request or response bodies, no tokens, and query values that could identify a person are masked.
 */
export interface ApiCallRecord {
  id: number
  at: number
  method: string
  path: string
  /** 0 when the request never got an HTTP response (network failure). */
  status: number
  durationMs: number
  requestId?: string
  idempotencyKey?: string
  replayed: boolean
  code?: string
}

const LIMIT = 200
let records: readonly ApiCallRecord[] = []
let nextId = 1
const listeners = new Set<() => void>()

function emit() {
  listeners.forEach((listener) => listener())
}

export const inspector = {
  subscribe(listener: () => void) {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },
  getSnapshot: () => records,
  record(entry: Omit<ApiCallRecord, 'id' | 'at'>) {
    records = [{ ...entry, id: nextId++, at: Date.now() }, ...records].slice(0, LIMIT)
    emit()
  },
  clear() {
    records = []
    emit()
  },
}

export function useApiCalls(): readonly ApiCallRecord[] {
  return useSyncExternalStore(inspector.subscribe, inspector.getSnapshot)
}

/** Nearest-rank percentile of already sorted values (0 for an empty list). */
export function percentile(sorted: number[], p: number): number {
  if (sorted.length === 0) return 0
  const rank = Math.ceil((p / 100) * sorted.length)
  return sorted[Math.min(sorted.length, Math.max(1, rank)) - 1] ?? 0
}

export interface CallStats {
  count: number
  p50: number
  p95: number
  /** 4xx: the server understood and refused (validation, limits, auth). */
  rejected: number
  /** 5xx or no response at all. */
  failed: number
  replays: number
}

export function callStats(calls: readonly ApiCallRecord[]): CallStats {
  const durations = calls.map((c) => c.durationMs).sort((a, b) => a - b)
  return {
    count: calls.length,
    p50: percentile(durations, 50),
    p95: percentile(durations, 95),
    rejected: calls.filter((c) => c.status >= 400 && c.status < 500).length,
    failed: calls.filter((c) => c.status === 0 || c.status >= 500).length,
    replays: calls.filter((c) => c.replayed).length,
  }
}
