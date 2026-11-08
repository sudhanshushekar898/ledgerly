import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSyncExternalStore } from 'react'
import { api } from '../../lib/apiClient'
import { formatInr } from '../../lib/money'
import type { AppNotification, NotificationInbox } from '../../types'

export const notificationKeys = { inbox: ['notifications'] as const }

export function useNotifications() {
  return useQuery({
    queryKey: notificationKeys.inbox,
    queryFn: ({ signal }) => api<NotificationInbox>('/api/v1/notifications', { signal }),
  })
}

export function useMarkAllRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api('/api/v1/notifications/read-all', { method: 'POST' }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: notificationKeys.inbox }),
  })
}

/** What a person reads for a notification. */
export function describeNotification(n: AppNotification): { title: string; body: string } {
  const amount = formatInr(n.amount)
  const note = n.memo ? ` · “${n.memo}”` : ''
  switch (n.kind) {
    case 'MONEY_RECEIVED':
      return { title: `${amount} received`, body: `from ${n.counterpartyName ?? 'another customer'}${note}` }
    case 'MONEY_ADDED':
      return { title: `${amount} added`, body: 'to your wallet' }
    case 'PAYMENT_RETURNED':
      return { title: `${amount} returned to you`, body: `your payment to ${n.counterpartyName ?? 'another customer'} was reversed` }
    case 'PAYMENT_REVERSED':
      return {
        title: `${amount} reversed`,
        body: n.counterpartyName ? `the payment from ${n.counterpartyName} was reversed` : 'a top-up was reversed',
      }
  }
}

/* Toasts: a tiny store so the live stream (outside React rendering) can show them. */

export interface Toast {
  id: string
  notification: AppNotification
}

let toasts: readonly Toast[] = []
const listeners = new Set<() => void>()
const emit = () => listeners.forEach((listener) => listener())

export const toastStore = {
  subscribe(listener: () => void) {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },
  getSnapshot: () => toasts,
  show(notification: AppNotification, ttlMs = 7000) {
    const toast = { id: notification.id, notification }
    toasts = [toast, ...toasts.filter((t) => t.id !== toast.id)].slice(0, 4)
    emit()
    setTimeout(() => toastStore.dismiss(toast.id), ttlMs)
  },
  dismiss(id: string) {
    toasts = toasts.filter((t) => t.id !== id)
    emit()
  },
  clear() {
    toasts = []
    emit()
  },
}

export function useToasts() {
  return useSyncExternalStore(toastStore.subscribe, toastStore.getSnapshot)
}
