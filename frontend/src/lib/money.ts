import { z } from 'zod'

/*
 * Money arrives from the API as a decimal string ("1500.00") and is never turned into a JavaScript number:
 * 0.1 + 0.2 !== 0.3 in floating point. Formatting and comparisons use BigInt paise (1 rupee = 100 paise).
 */

const DECIMAL = /^(-?)(\d+)(?:\.(\d+))?$/
/** What a person may type: up to 13 digits of rupees and at most 2 decimals (paise), no sign, no separators. */
const AMOUNT_INPUT = /^\d{1,13}(\.\d{1,2})?$/
const INDIAN_GROUPING = new Intl.NumberFormat('en-IN')

/** "1234567.5" -> "₹12,34,567.50" (Indian digit grouping). Unparseable input is returned unchanged. */
export function formatInr(amount: string, { signed = false }: { signed?: boolean } = {}): string {
  const match = DECIMAL.exec(amount.trim())
  if (!match) return amount
  const negative = match[1] === '-'
  const rupees = INDIAN_GROUPING.format(BigInt(match[2] ?? '0'))
  // The API sends exactly 2 decimals; pad shorter input rather than round anything here.
  const paise = `${match[3] ?? ''}00`.slice(0, 2)
  const sign = negative ? '−' : signed ? '+' : ''
  return `${sign}₹${rupees}.${paise}`
}

/** Exact paise of a decimal string from the API ("-250.50", "1500.00"); 0 if unparseable. */
export function parsePaise(amount: string): bigint {
  const match = DECIMAL.exec(amount.trim())
  if (!match) return 0n
  const value = BigInt(match[2] ?? '0') * 100n + BigInt(`${match[3] ?? ''}00`.slice(0, 2))
  return match[1] === '-' ? -value : value
}

/** 123456n -> "1234.56"; the inverse of parsePaise. */
export function fromPaise(paise: bigint): string {
  const negative = paise < 0n
  const abs = negative ? -paise : paise
  return `${negative ? '-' : ''}${abs / 100n}.${String(abs % 100n).padStart(2, '0')}`
}

/** Exact value in paise, or null if the text is not a valid amount. */
export function toPaise(amount: string): bigint | null {
  const trimmed = amount.trim()
  if (!AMOUNT_INPUT.test(trimmed)) return null
  const [rupees = '0', paise = ''] = trimmed.split('.')
  return BigInt(rupees) * 100n + BigInt(`${paise}00`.slice(0, 2))
}

/** Client-side mirror of the server's amount rules, for instant feedback. The server stays the authority. */
export function amountSchema(max: string, action: 'send' | 'add') {
  const maxPaise = toPaise(max) ?? 0n
  return z
    .string()
    .min(1, 'Enter an amount')
    .refine((value) => toPaise(value) !== null, 'Enter an amount like 250 or 250.50')
    .refine((value) => {
      const paise = toPaise(value)
      return paise === null || paise > 0n
    }, 'Amount must be more than ₹0')
    .refine((value) => {
      const paise = toPaise(value)
      return paise === null || paise <= maxPaise
    }, `You can ${action} at most ${formatInr(max)} at a time`)
}
