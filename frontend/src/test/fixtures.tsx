import { QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import type { ReactElement } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { createQueryClient } from '../app/queryClient'
import { authStore } from '../lib/authStore'
import type { Contact, DayFlow, Insights, LoginResponse, Receipt, StatementItem, StatementResponse, UserSummary, Wallet } from '../types'
import { server } from './server'

export function customer(overrides: Partial<UserSummary> = {}): UserSummary {
  return { id: '0190aaaa-0000-7000-8000-000000000001', email: 'asha@example.com', name: 'Asha Rao', role: 'USER', ...overrides }
}

export function loginResponse(user: UserSummary, accessToken = 'access-1'): LoginResponse {
  return { accessToken, tokenType: 'Bearer', expiresInSeconds: 900, user }
}

export function signIn(user: UserSummary = customer(), accessToken = 'access-1') {
  authStore.setSession(accessToken, user)
}

export function problem(status: number, code: string, detail: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json({ status, code, detail, ...extra }, { status, headers: { 'Content-Type': 'application/problem+json' } })
}

export function wallet(overrides: Partial<Wallet> = {}): Wallet {
  return { accountId: '0190dddd-0000-7000-8000-00000000a3f9', currency: 'INR', balance: '1500.00', status: 'ACTIVE', ...overrides }
}

export function item(overrides: Partial<StatementItem> = {}): StatementItem {
  return {
    seq: 1,
    transactionId: '0190bbbb-0000-7000-8000-000000000001',
    kind: 'TOPUP',
    direction: 'CREDIT',
    amount: '500.00',
    balanceAfter: '500.00',
    createdAt: '2026-11-08T10:00:00Z',
    ...overrides,
  }
}

export function statement(items: StatementItem[], nextBefore?: number): StatementResponse {
  return nextBefore === undefined ? { items } : { items, nextBefore }
}

export function insights(overrides: Partial<Insights> = {}): Insights {
  const series: DayFlow[] = Array.from({ length: 30 }, (_, i) => ({
    date: new Date(Date.UTC(2026, 9, 10 + i)).toISOString().slice(0, 10),
    moneyIn: i === 29 ? '1000.00' : '0.00',
    moneyOut: i === 29 ? '250.00' : '0.00',
    closingBalance: i === 29 ? '1500.00' : '750.00',
  }))
  return {
    series,
    totalIn: '1000.00',
    totalOut: '250.00',
    limits: { perTransfer: '25000.00', daily: '50000.00', sentToday: '250.00', remainingToday: '49750.00' },
    ...overrides,
  }
}

export const RAVI: Contact = { name: 'Ravi Kumar', email: 'ravi@example.com' }

export function receipt(overrides: Partial<Receipt> = {}): Receipt {
  return {
    transactionId: '0190cccc-0000-7000-8000-000000000001',
    kind: 'TRANSFER',
    direction: 'DEBIT',
    amount: '250.50',
    memo: 'Dinner',
    counterpartyName: 'Ravi Kumar',
    createdAt: '2026-11-08T10:00:00Z',
    balanced: true,
    lines: [
      { account: 'YOUR_WALLET', label: 'Your wallet', direction: 'DEBIT', amount: '250.50', balanceAfter: '1249.50' },
      { account: 'CUSTOMER_WALLET', label: "Ravi Kumar's wallet", direction: 'CREDIT', amount: '250.50' },
    ],
    ...overrides,
  }
}

/** Every read endpoint the signed-in wallet screens call, with sensible defaults. */
export function serveWalletReads(overrides: { wallet?: Wallet; statement?: StatementResponse; insights?: Insights; contacts?: Contact[] } = {}) {
  server.use(
    http.get('*/api/v1/wallet', () => HttpResponse.json(overrides.wallet ?? wallet())),
    http.get('*/api/v1/wallet/statement', () => HttpResponse.json(overrides.statement ?? statement([]))),
    http.get('*/api/v1/wallet/insights', () => HttpResponse.json(overrides.insights ?? insights())),
    http.get('*/api/v1/wallet/contacts', () => HttpResponse.json(overrides.contacts ?? [RAVI])),
  )
}

export function renderAt(ui: ReactElement, path = '/') {
  return render(
    <QueryClientProvider client={createQueryClient({ retry: false })}>
      <MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}
