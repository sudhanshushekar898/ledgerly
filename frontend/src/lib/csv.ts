import type { StatementItem } from '../types'

/**
 * Spreadsheet apps execute cells that start with = + - @ (CSV/formula injection). Text that came from other people,
 * such as a payment note, is neutralised with a leading apostrophe.
 */
function text(value: string | undefined): string {
  const raw = value ?? ''
  const safe = /^[=+\-@\t\r]/.test(raw) ? `'${raw}` : raw
  return /[",\r\n]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe
}

/** The loaded statement as CSV: signed amounts, exact decimal strings, one row per ledger entry. */
export function statementCsv(items: StatementItem[]): string {
  const header = 'created_at,type,direction,amount_inr,balance_after_inr,counterparty,note,transaction_id'
  const rows = items.map((i) =>
    [
      i.createdAt,
      i.kind,
      i.direction,
      `${i.direction === 'DEBIT' ? '-' : ''}${i.amount}`,
      i.balanceAfter,
      text(i.counterpartyName),
      text(i.memo),
      i.transactionId,
    ].join(','),
  )
  return [header, ...rows].join('\r\n')
}

export function downloadCsv(filename: string, content: string) {
  const url = URL.createObjectURL(new Blob([content], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}
