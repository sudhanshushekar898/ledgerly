import { describe, expect, it } from 'vitest'
import type { CallResult, RequestOptions } from '../../lib/apiClient'
import { ApiError } from '../../lib/errors'
import { fromPaise, parsePaise } from '../../lib/money'
import type { TransferReceipt } from '../../types'
import type { Caller } from './experiments'
import { duplicateStorm, lostResponse, overdrawRace } from './experiments'

/**
 * A tiny in-memory payments server. `correct` behaves like Ledgerly; the broken variants each drop one guarantee,
 * so the tests prove the experiments would actually CATCH a bug, not just print "passed".
 */
function fakeServer(start: string, { idempotent = true, checksBalance = true } = {}) {
  let balance = parsePaise(start)
  let seq = 0
  const byKey = new Map<string, TransferReceipt>()

  const respond = <T,>(result: Partial<CallResult<T>>): CallResult<T> =>
    ({ ok: true, status: 200, replayed: false, durationMs: 3, ...result }) as CallResult<T>

  const call = (async (path: string, options?: RequestOptions) => {
    await Promise.resolve() // let parallel calls interleave
    if (path === '/api/v1/wallet') return respond({ data: { accountId: 'a', currency: 'INR', balance: fromPaise(balance), status: 'ACTIVE' } })
    const key = options?.headers?.['Idempotency-Key'] ?? ''
    const amount = parsePaise((options?.body as { amount: string }).amount)
    const previous = byKey.get(key)
    if (idempotent && previous) return respond({ status: 201, data: previous, replayed: true })
    if (checksBalance && amount > balance) {
      return respond({ ok: false, status: 422, error: new ApiError({ status: 422, code: 'INSUFFICIENT_FUNDS', detail: 'Insufficient balance.' }) })
    }
    balance -= amount
    const receipt: TransferReceipt = { transactionId: `txn-${++seq}`, amount: fromPaise(amount), recipientName: 'Ravi', balanceAfter: fromPaise(balance), createdAt: '' }
    byKey.set(key, receipt)
    return respond({ status: 201, data: receipt })
  }) as Caller

  return { call, balance: () => fromPaise(balance) }
}

const recipientEmail = 'ravi@example.com'

describe('duplicateStorm', () => {
  it('passes when the server is idempotent: one execution, four replays, charged once', async () => {
    const server = fakeServer('100.00')

    const report = await duplicateStorm({ recipientEmail, call: server.call })

    expect(report.passed).toBe(true)
    expect(report.probes.map((p) => p.outcome).sort()).toEqual(['executed', 'replayed', 'replayed', 'replayed', 'replayed'])
    expect(report.balanceAfter).toBe('99.00')
  })

  it('FAILS against a server without idempotency (every copy charges)', async () => {
    const server = fakeServer('100.00', { idempotent: false })

    const report = await duplicateStorm({ recipientEmail, call: server.call })

    expect(report.passed).toBe(false)
    expect(report.checks.find((c) => c.label.startsWith('Exactly one'))?.passed).toBe(false)
    expect(server.balance()).toBe('95.00')
  })

  it('refuses to run without enough balance', async () => {
    await expect(duplicateStorm({ recipientEmail, call: fakeServer('0.50').call })).rejects.toThrow(/Add at least ₹1.00/)
  })
})

describe('overdrawRace', () => {
  it('passes when the balance is protected: one 60% payment wins, the rest are refused', async () => {
    const server = fakeServer('100.00')

    const report = await overdrawRace({ recipientEmail, call: server.call })

    expect(report.passed).toBe(true)
    expect(report.probes.filter((p) => p.outcome === 'executed')).toHaveLength(1)
    expect(report.probes.filter((p) => p.code === 'INSUFFICIENT_FUNDS')).toHaveLength(5)
    expect(report.balanceAfter).toBe('40.00')
  })

  it('caps each payment at the per-transfer maximum', async () => {
    const server = fakeServer('100000.00')

    const report = await overdrawRace({ recipientEmail, call: server.call })

    expect(report.probes.filter((p) => p.outcome === 'executed')).toHaveLength(4)
    expect(report.balanceAfter).toBe('0.00')
  })

  it('FAILS against a server that lets a wallet go negative', async () => {
    const report = await overdrawRace({ recipientEmail, call: fakeServer('100.00', { checksBalance: false }).call })

    expect(report.passed).toBe(false)
    expect(report.checks.find((c) => c.label.includes('below zero'))?.passed).toBe(false)
  })
})

describe('lostResponse', () => {
  it('passes when the retry is replayed with the original transaction', async () => {
    const report = await lostResponse({ recipientEmail, call: fakeServer('10.00').call })

    expect(report.passed).toBe(true)
    expect(report.probes.map((p) => p.outcome)).toEqual(['executed', 'replayed'])
    expect(report.probes[1]?.transactionId).toBe(report.probes[0]?.transactionId)
  })

  it('FAILS when the retry pays again', async () => {
    const report = await lostResponse({ recipientEmail, call: fakeServer('10.00', { idempotent: false }).call })

    expect(report.passed).toBe(false)
    expect(report.balanceAfter).toBe('8.00')
  })
})
