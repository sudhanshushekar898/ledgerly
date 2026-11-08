import type { LucideIcon } from 'lucide-react'
import { ArrowDownLeft, ArrowUpRight, CirclePlus, Undo2 } from 'lucide-react'
import { formatInr } from '../../lib/money'
import type { StatementItem } from '../../types'

type Describable = Pick<StatementItem, 'kind' | 'direction' | 'counterpartyName'>

/** The line a person reads for one ledger entry of THEIR wallet. */
export function describeItem(item: Describable): { title: string; icon: LucideIcon } {
  const name = item.counterpartyName ?? 'another customer'
  const credit = item.direction === 'CREDIT'
  switch (item.kind) {
    case 'TOPUP':
      return { title: 'Added money', icon: CirclePlus }
    case 'TRANSFER':
      return credit ? { title: `Received from ${name}`, icon: ArrowDownLeft } : { title: `Sent to ${name}`, icon: ArrowUpRight }
    case 'REVERSAL':
      // Sender gets money back; recipient gives it back; a reversed top-up has the system account on the other side.
      if (credit) return { title: item.counterpartyName ? `Payment to ${name} reversed` : 'Reversal', icon: Undo2 }
      return { title: item.counterpartyName ? `Payment from ${name} reversed` : 'Top-up reversed', icon: Undo2 }
  }
}

/** "+₹250.00" for money in, "−₹250.00" for money out. */
export function signedAmount(item: Pick<StatementItem, 'direction' | 'amount'>): string {
  return item.direction === 'CREDIT' ? formatInr(item.amount, { signed: true }) : `−${formatInr(item.amount)}`
}

export const KIND_LABEL: Record<StatementItem['kind'], string> = { TOPUP: 'Top-up', TRANSFER: 'Transfer', REVERSAL: 'Reversal' }
