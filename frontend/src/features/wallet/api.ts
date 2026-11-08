import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../lib/apiClient'
import type { Contact, Insights, Receipt, StatementResponse, TopUpReceipt, TransferReceipt, Wallet } from '../../types'

/** Mirrors the backend defaults (WalletProperties). The server enforces them; the UI only explains them early. */
export const LIMITS = { maxTopUp: '100000.00', maxTransfer: '25000.00', dailyTransfer: '50000.00' } as const

export const walletKeys = {
  all: ['wallet'] as const,
  summary: ['wallet', 'summary'] as const,
  statement: (pageSize: number) => ['wallet', 'statement', pageSize] as const,
  insights: (days: number) => ['wallet', 'insights', days] as const,
  contacts: ['wallet', 'contacts'] as const,
  receipt: (id: string) => ['wallet', 'receipt', id] as const,
}

export function useWallet() {
  return useQuery({ queryKey: walletKeys.summary, queryFn: ({ signal }) => api<Wallet>('/api/v1/wallet', { signal }) })
}

/** Newest first, in pages; the cursor is the per-account entry sequence the backend returns as nextBefore. */
export function useStatement(pageSize: number) {
  return useInfiniteQuery({
    queryKey: walletKeys.statement(pageSize),
    queryFn: ({ pageParam, signal }) =>
      api<StatementResponse>('/api/v1/wallet/statement', { query: { limit: pageSize, before: pageParam }, signal }),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (last) => last.nextBefore,
  })
}

export function useInsights(days = 30) {
  return useQuery({
    queryKey: walletKeys.insights(days),
    queryFn: ({ signal }) => api<Insights>('/api/v1/wallet/insights', { query: { days }, signal }),
  })
}

export function useContacts() {
  return useQuery({ queryKey: walletKeys.contacts, queryFn: ({ signal }) => api<Contact[]>('/api/v1/wallet/contacts', { signal }) })
}

export function useReceipt(transactionId: string) {
  return useQuery({
    queryKey: walletKeys.receipt(transactionId),
    queryFn: ({ signal }) => api<Receipt>(`/api/v1/wallet/transactions/${encodeURIComponent(transactionId)}`, { signal }),
  })
}

/** "Verify before you pay": who owns this email's wallet. */
export function lookupRecipient(email: string) {
  return api<Contact>('/api/v1/recipients', { query: { email } })
}

/** After every attempt, success or not, refresh what depends on money: a rejection can reveal a change (a freeze). */
export function useRefreshWallet() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: walletKeys.all })
}

export interface TopUpInput {
  amount: string
  idempotencyKey: string
}

export function useTopUp() {
  const refresh = useRefreshWallet()
  return useMutation({
    mutationFn: ({ amount, idempotencyKey }: TopUpInput) =>
      api<TopUpReceipt>('/api/v1/wallet/topups', { method: 'POST', body: { amount }, headers: { 'Idempotency-Key': idempotencyKey } }),
    onSettled: refresh,
  })
}

export interface TransferInput {
  recipientEmail: string
  amount: string
  memo?: string
  idempotencyKey: string
}

export function useTransfer() {
  const refresh = useRefreshWallet()
  return useMutation({
    mutationFn: ({ idempotencyKey, ...body }: TransferInput) =>
      api<TransferReceipt>('/api/v1/transfers', { method: 'POST', body, headers: { 'Idempotency-Key': idempotencyKey } }),
    onSettled: refresh,
  })
}
