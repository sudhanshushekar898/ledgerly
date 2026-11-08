import { describe, expect, it } from 'vitest'
import { item } from '../test/fixtures'
import { statementCsv } from './csv'

describe('statementCsv', () => {
  it('writes signed exact amounts with a header row', () => {
    const csv = statementCsv([
      item({ kind: 'TRANSFER', direction: 'DEBIT', amount: '250.50', balanceAfter: '749.50', counterpartyName: 'Ravi Kumar', memo: 'Dinner', transactionId: 't-2' }),
      item({ kind: 'TOPUP', direction: 'CREDIT', amount: '1000.00', balanceAfter: '1000.00', transactionId: 't-1' }),
    ])

    const [header, sent, added] = csv.split('\r\n')
    expect(header).toBe('created_at,type,direction,amount_inr,balance_after_inr,counterparty,note,transaction_id')
    expect(sent).toBe('2026-11-08T10:00:00Z,TRANSFER,DEBIT,-250.50,749.50,Ravi Kumar,Dinner,t-2')
    expect(added).toBe('2026-11-08T10:00:00Z,TOPUP,CREDIT,1000.00,1000.00,,,t-1')
  })

  it('quotes commas and quotes, and neutralises spreadsheet formulas in text from other people', () => {
    const csv = statementCsv([item({ memo: '=HYPERLINK("http://evil")', counterpartyName: 'Rao, Asha' })])

    const row = csv.split('\r\n')[1]
    expect(row).toContain('"Rao, Asha"')
    expect(row).toContain(`"'=HYPERLINK(""http://evil"")"`)
  })
})
