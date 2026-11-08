/*
 * API contract, written by hand for this small surface. Money is always a decimal STRING such as "1500.00": the
 * backend formats BigDecimal that way so no JavaScript number ever rounds a rupee.
 */

export type Role = 'USER' | 'ADMIN'

export interface UserSummary {
  id: string
  email: string
  name: string
  role: Role
}

export interface LoginResponse {
  accessToken: string
  tokenType: 'Bearer'
  expiresInSeconds: number
  user: UserSummary
}

export type WalletStatus = 'ACTIVE' | 'FROZEN'

export interface Wallet {
  accountId: string
  currency: 'INR'
  balance: string
  status: WalletStatus
}

export interface TopUpReceipt {
  transactionId: string
  amount: string
  balanceAfter: string
  createdAt: string
}

export interface TransferReceipt {
  transactionId: string
  amount: string
  recipientName: string
  memo?: string
  balanceAfter: string
  createdAt: string
}

export type TransactionKind = 'TOPUP' | 'TRANSFER' | 'REVERSAL'
export type Direction = 'DEBIT' | 'CREDIT'

export interface StatementItem {
  seq: number
  transactionId: string
  kind: TransactionKind
  direction: Direction
  amount: string
  balanceAfter: string
  memo?: string
  counterpartyName?: string
  createdAt: string
}

export interface StatementResponse {
  items: StatementItem[]
  /** Cursor for the next (older) page; absent on the last page. */
  nextBefore?: number
}

export type LedgerAccount = 'YOUR_WALLET' | 'CUSTOMER_WALLET' | 'TOPUP_SOURCE'

/** One double-entry line. balanceAfter only ever appears on the viewer's own wallet. */
export interface ReceiptLine {
  account: LedgerAccount
  label: string
  direction: Direction
  amount: string
  balanceAfter?: string
}

export interface Receipt {
  transactionId: string
  kind: TransactionKind
  direction: Direction
  amount: string
  memo?: string
  counterpartyName?: string
  createdAt: string
  reverses?: string
  reversedBy?: string
  lines: ReceiptLine[]
  balanced: boolean
}

export interface DayFlow {
  /** Calendar date in the wallet's zone (Asia/Kolkata), YYYY-MM-DD. */
  date: string
  moneyIn: string
  moneyOut: string
  closingBalance: string
}

export interface Limits {
  perTransfer: string
  daily: string
  sentToday: string
  remainingToday: string
}

export interface Insights {
  series: DayFlow[]
  totalIn: string
  totalOut: string
  limits: Limits
}

export type NotificationKind = 'MONEY_RECEIVED' | 'MONEY_ADDED' | 'PAYMENT_RETURNED' | 'PAYMENT_REVERSED'

export interface AppNotification {
  id: string
  kind: NotificationKind
  amount: string
  counterpartyName?: string
  memo?: string
  transactionId: string
  createdAt: string
  read: boolean
}

export interface NotificationInbox {
  unread: number
  items: AppNotification[]
}

export interface Contact {
  name: string
  email: string
}
