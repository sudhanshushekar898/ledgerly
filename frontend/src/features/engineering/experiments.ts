import type { CallResult, RequestOptions } from '../../lib/apiClient'
import { apiCall } from '../../lib/apiClient'
import { newIdempotencyKey } from '../../lib/idempotency'
import { formatInr, fromPaise, parsePaise } from '../../lib/money'
import type { TransferReceipt, Wallet } from '../../types'

/*
 * Resilience Lab experiments. They run REAL requests against the real API with the user's own (synthetic) money,
 * then check invariants on what came back. Pure functions with an injectable caller, so they are unit-tested too.
 */

export type Outcome = 'executed' | 'replayed' | 'rejected' | 'failed'

export interface Probe {
  index: number
  label: string
  status: number
  outcome: Outcome
  transactionId?: string
  code?: string
  /** Milliseconds after the experiment started that this request was sent. */
  startMs: number
  durationMs: number
}

export interface Check {
  label: string
  passed: boolean
  detail: string
}

export interface Report {
  probes: Probe[]
  checks: Check[]
  passed: boolean
  balanceBefore: string
  balanceAfter: string
  elapsedMs: number
}

export type Caller = <T>(path: string, options?: RequestOptions) => Promise<CallResult<T>>

export interface ExperimentInput {
  recipientEmail: string
  call?: Caller
}

const BUSINESS_REJECTIONS = new Set(['INSUFFICIENT_FUNDS', 'DAILY_LIMIT_EXCEEDED'])

async function balanceOf(call: Caller): Promise<bigint> {
  const result = await call<Wallet>('/api/v1/wallet')
  if (!result.ok || !result.data) throw new Error(result.error?.message ?? 'Could not read your balance.')
  return parsePaise(result.data.balance)
}

function requireBalance(balance: bigint, needed: bigint) {
  if (balance < needed) throw new Error(`Add at least ${formatInr(fromPaise(needed))} to your wallet to run this experiment.`)
}

function outcomeOf(result: CallResult<unknown>): Outcome {
  if (result.status === 0 || result.status >= 500) return 'failed'
  if (!result.ok) return 'rejected'
  return result.replayed ? 'replayed' : 'executed'
}

function pay(call: Caller, recipientEmail: string, amount: string, key: string, memo: string) {
  return call<TransferReceipt>('/api/v1/transfers', {
    method: 'POST',
    body: { recipientEmail, amount, memo },
    headers: { 'Idempotency-Key': key },
  })
}

async function probe(t0: number, index: number, label: string, run: () => Promise<CallResult<TransferReceipt>>): Promise<Probe> {
  const startMs = Math.round(performance.now() - t0)
  const result = await run()
  return {
    index,
    label,
    status: result.status,
    outcome: outcomeOf(result),
    transactionId: result.data?.transactionId,
    code: result.error?.code,
    startMs,
    durationMs: result.durationMs,
  }
}

function report(t0: number, probes: Probe[], checks: Check[], before: bigint, after: bigint): Report {
  return {
    probes,
    checks,
    passed: checks.every((c) => c.passed),
    balanceBefore: fromPaise(before),
    balanceAfter: fromPaise(after),
    elapsedMs: Math.round(performance.now() - t0),
  }
}

/** A user hammering "Pay": N identical requests with ONE Idempotency-Key, all in flight at the same time. */
export async function duplicateStorm({ recipientEmail, call = apiCall, copies = 5, amount = '1.00' }: ExperimentInput & { copies?: number; amount?: string }): Promise<Report> {
  const t0 = performance.now()
  const before = await balanceOf(call)
  requireBalance(before, parsePaise(amount))
  const key = newIdempotencyKey()
  const probes = await Promise.all(
    Array.from({ length: copies }, (_, i) => probe(t0, i, `copy ${i + 1}`, () => pay(call, recipientEmail, amount, key, 'Lab: duplicate storm'))),
  )
  const after = await balanceOf(call)

  const executed = probes.filter((p) => p.outcome === 'executed').length
  const replayed = probes.filter((p) => p.outcome === 'replayed').length
  const ids = new Set(probes.map((p) => p.transactionId).filter(Boolean))
  const moved = before - after
  return report(t0, probes, [
    { label: 'Exactly one request executed the payment', passed: executed === 1, detail: `${executed} executed` },
    { label: `The other ${copies - 1} were answered from the idempotency store`, passed: replayed === copies - 1, detail: `${replayed} replayed` },
    { label: 'Every response carries the same transaction id', passed: ids.size === 1, detail: `${ids.size} distinct id(s)` },
    { label: `Balance moved exactly once (${formatInr(amount)})`, passed: moved === parsePaise(amount), detail: `moved ${formatInr(fromPaise(moved))}` },
  ], before, after)
}

/**
 * Parallel payments that together ask for more than the balance. Each is 60% of the balance (capped at the
 * per-payment maximum), so at most one can fit unless the daily limit binds first. Row locks decide who wins.
 */
export async function overdrawRace({ recipientEmail, call = apiCall, parallel = 6, maxTransfer = '25000.00' }: ExperimentInput & { parallel?: number; maxTransfer?: string }): Promise<Report> {
  const t0 = performance.now()
  const before = await balanceOf(call)
  requireBalance(before, 100n)
  const cap = parsePaise(maxTransfer)
  const share = (before * 6n) / 10n
  const amountPaise = share > cap ? cap : share
  const amount = fromPaise(amountPaise)
  const probes = await Promise.all(
    Array.from({ length: parallel }, (_, i) =>
      probe(t0, i, `payment ${i + 1}`, () => pay(call, recipientEmail, amount, newIdempotencyKey(), 'Lab: overdraw race')),
    ),
  )
  const after = await balanceOf(call)

  const succeeded = probes.filter((p) => p.outcome === 'executed').length
  const rejected = probes.filter((p) => p.outcome === 'rejected')
  return report(t0, probes, [
    { label: 'No request failed with a server or network error', passed: probes.every((p) => p.outcome !== 'failed'), detail: `${probes.filter((p) => p.outcome === 'failed').length} failed` },
    {
      label: 'Every refusal is a clean business rejection',
      passed: rejected.every((p) => BUSINESS_REJECTIONS.has(p.code ?? '')),
      detail: [...new Set(rejected.map((p) => p.code))].join(', ') || 'none',
    },
    { label: 'The balance never went below zero', passed: after >= 0n, detail: `after ${formatInr(fromPaise(after))}` },
    {
      label: 'Money moved equals the payments that succeeded',
      passed: before - after === amountPaise * BigInt(succeeded),
      detail: `${succeeded} × ${formatInr(amount)}`,
    },
    { label: 'Contention observed: some payments were refused', passed: rejected.length > 0, detail: `${rejected.length} of ${parallel} refused` },
  ], before, after)
}

/**
 * The classic mobile failure: the server commits the payment but the response is lost on the way back. The app
 * retries with the same key and must get the original result back instead of paying twice.
 */
export async function lostResponse({ recipientEmail, call = apiCall, amount = '1.00' }: ExperimentInput & { amount?: string }): Promise<Report> {
  const t0 = performance.now()
  const before = await balanceOf(call)
  requireBalance(before, parsePaise(amount))
  const key = newIdempotencyKey()
  // The response to this first request is deliberately ignored, as if the connection had dropped.
  const original = await probe(t0, 0, 'original (response dropped)', () => pay(call, recipientEmail, amount, key, 'Lab: lost response'))
  const retry = await probe(t0, 1, 'retry, same key', () => pay(call, recipientEmail, amount, key, 'Lab: lost response'))
  const after = await balanceOf(call)

  return report(t0, [original, retry], [
    { label: 'The retry was answered from the idempotency store', passed: retry.outcome === 'replayed', detail: retry.outcome },
    {
      label: 'It returned the original transaction',
      passed: !!retry.transactionId && retry.transactionId === original.transactionId,
      detail: retry.transactionId ? `txn ${retry.transactionId.slice(0, 8)}` : 'no id',
    },
    { label: `Charged exactly once (${formatInr(amount)})`, passed: before - after === parsePaise(amount), detail: `moved ${formatInr(fromPaise(before - after))}` },
  ], before, after)
}
