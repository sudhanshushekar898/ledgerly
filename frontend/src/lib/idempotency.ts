import { useMemo, useRef } from 'react'

/** A random Idempotency-Key (8-64 characters of letters, digits and '-', as the API requires). */
export function newIdempotencyKey(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  // randomUUID needs a secure context; plain-HTTP LAN testing still gets a strong random key.
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, '0')).join('')
}

/**
 * One key per PAYMENT, not per click. While the payment's content (recipient, amount, note) stays the same, the key
 * stays the same: pressing "Pay" again after a timeout, or going back and returning unchanged, replays the first
 * request instead of paying twice. Different content is a different payment and gets a new key. After a success,
 * reset() so that an identical payment made on purpose later is a new one.
 */
export function useIdempotencyKey() {
  const state = useRef<{ fingerprint: string; key: string } | null>(null)
  return useMemo(
    () => ({
      forPayment(fingerprint: string): string {
        const current = state.current
        if (current && current.fingerprint === fingerprint) return current.key
        const next = { fingerprint, key: newIdempotencyKey() }
        state.current = next
        return next.key
      },
      reset() {
        state.current = null
      },
    }),
    [],
  )
}
