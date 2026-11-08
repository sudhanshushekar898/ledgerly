import { ApiError, describeError } from '../../lib/errors'

export type PaymentField = 'recipientEmail' | 'amount'

/**
 * Turns an API failure into what the person should read, and which field it belongs to. The network case matters
 * most: the payment may or may not have happened, and pressing the button again is safe because the retry reuses
 * the same Idempotency-Key.
 */
export function paymentError(error: unknown): { field?: PaymentField; message: string } {
  if (!(error instanceof ApiError)) return { message: describeError(error) }
  switch (error.code) {
    case 'NETWORK_ERROR':
      return {
        message:
          "We couldn't reach Ledgerly, so we can't confirm this payment yet. Press the button again: it's safe. " +
          'The retry reuses this payment’s key, so you can never be charged twice.',
      }
    case 'INSUFFICIENT_FUNDS':
      return { field: 'amount', message: 'Your balance is too low for this payment.' }
    case 'AMOUNT_INVALID':
      return { field: 'amount', message: error.message }
    case 'RECIPIENT_NOT_FOUND':
      return { field: 'recipientEmail', message: 'No Ledgerly customer has this email.' }
    case 'SAME_ACCOUNT':
      return { field: 'recipientEmail', message: 'You cannot send money to yourself.' }
    case 'WALLET_FROZEN':
      return { message: 'This payment is blocked because a wallet involved is frozen. Contact support if it is yours.' }
    case 'DAILY_LIMIT_EXCEEDED':
      return { message: error.message }
    default:
      return { message: describeError(error) }
  }
}
