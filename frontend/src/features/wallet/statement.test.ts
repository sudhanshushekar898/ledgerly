import { describe, expect, it } from 'vitest'
import { item } from '../../test/fixtures'
import { describeItem, signedAmount } from './statement'

describe('describeItem', () => {
  it.each([
    [item({ kind: 'TOPUP', direction: 'CREDIT' }), 'Added money'],
    [item({ kind: 'TRANSFER', direction: 'DEBIT', counterpartyName: 'Ravi Kumar' }), 'Sent to Ravi Kumar'],
    [item({ kind: 'TRANSFER', direction: 'CREDIT', counterpartyName: 'Meera Shah' }), 'Received from Meera Shah'],
    [item({ kind: 'REVERSAL', direction: 'CREDIT', counterpartyName: 'Ravi Kumar' }), 'Payment to Ravi Kumar reversed'],
    [item({ kind: 'REVERSAL', direction: 'DEBIT', counterpartyName: 'Asha Rao' }), 'Payment from Asha Rao reversed'],
    [item({ kind: 'REVERSAL', direction: 'DEBIT' }), 'Top-up reversed'],
    [item({ kind: 'TRANSFER', direction: 'DEBIT' }), 'Sent to another customer'],
  ])('labels the entry as %#', (entry, title) => {
    expect(describeItem(entry).title).toBe(title)
  })
})

describe('signedAmount', () => {
  it('shows money in with + and money out with −', () => {
    expect(signedAmount(item({ direction: 'CREDIT', amount: '250.00' }))).toBe('+₹250.00')
    expect(signedAmount(item({ direction: 'DEBIT', amount: '1250.50' }))).toBe('−₹1,250.50')
  })
})
