import { ArrowDownLeft, Bell, CirclePlus, Undo2, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { formatTime } from '../../lib/dates'
import type { AppNotification } from '../../types'
import type { LiveStatus } from './useLiveEvents'
import { describeNotification, toastStore, useMarkAllRead, useNotifications, useToasts } from './notifications'

function NotificationIcon({ notification }: { notification: AppNotification }) {
  const Icon = notification.kind === 'MONEY_RECEIVED' ? ArrowDownLeft : notification.kind === 'MONEY_ADDED' ? CirclePlus : Undo2
  return (
    <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-emerald-400/10 text-emerald-300 ring-1 ring-emerald-400/25">
      <Icon className="h-4 w-4" aria-hidden />
    </span>
  )
}

/** Toasts pushed by the live stream, top-right. */
export function Toaster() {
  const toasts = useToasts()
  return (
    <div aria-live="polite" className="pointer-events-none fixed right-4 top-4 z-[60] flex w-80 max-w-[calc(100vw-2rem)] flex-col gap-2">
      {toasts.map(({ id, notification }) => {
        const { title, body } = describeNotification(notification)
        return (
          <div key={id} role="status" className="pointer-events-auto flex animate-fade-up items-start gap-3 rounded-xl bg-ink-800/95 p-3 ring-1 ring-emerald-400/30 shadow-2xl shadow-black/50 backdrop-blur">
            <NotificationIcon notification={notification} />
            <div className="min-w-0 flex-1">
              <p className="font-mono text-sm font-semibold text-white">{title}</p>
              <p className="truncate text-xs text-slate-400">{body}</p>
              <Link to={`/transactions/${notification.transactionId}`} onClick={() => toastStore.dismiss(id)} className="mt-1 inline-block text-xs font-medium text-teal-300 hover:underline">
                View receipt
              </Link>
            </div>
            <button type="button" aria-label="Dismiss" onClick={() => toastStore.dismiss(id)} className="rounded p-1 text-slate-500 hover:text-slate-200">
              <X className="h-3.5 w-3.5" aria-hidden />
            </button>
          </div>
        )
      })}
    </div>
  )
}

/** Bell with the unread count; opens the latest notifications. */
export function NotificationBell({ align = 'left' }: { align?: 'left' | 'right' }) {
  const inbox = useNotifications()
  const markAllRead = useMarkAllRead()
  const [open, setOpen] = useState(false)
  const panel = useRef<HTMLDivElement>(null)
  const unread = inbox.data?.unread ?? 0

  useEffect(() => {
    if (!open) return
    const onDown = (event: MouseEvent) => {
      if (panel.current && !panel.current.contains(event.target as Node)) setOpen(false)
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <div ref={panel} className="relative">
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}
        className="relative rounded-lg p-2 text-slate-400 transition hover:bg-white/5 hover:text-slate-100"
      >
        <Bell className="h-[18px] w-[18px]" aria-hidden />
        {unread > 0 && (
          <span className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-teal-400 px-1 font-mono text-[10px] font-bold text-ink-950">
            {unread > 9 ? '9+' : unread}
          </span>
        )}
      </button>
      {open && (
        <div
          role="dialog"
          aria-label="Notifications"
          className={`absolute top-11 z-50 w-80 animate-fade-up overflow-hidden rounded-xl bg-ink-850 ring-1 ring-white/10 shadow-2xl shadow-black/60 ${align === 'right' ? 'right-0' : 'left-0'}`}
        >
          <div className="flex items-center justify-between border-b border-white/[0.06] px-4 py-3">
            <p className="text-sm font-semibold text-white">Notifications</p>
            <button
              type="button"
              disabled={unread === 0 || markAllRead.isPending}
              onClick={() => markAllRead.mutate()}
              className="text-xs font-medium text-teal-300 hover:underline disabled:text-slate-600 disabled:no-underline"
            >
              Mark all read
            </button>
          </div>
          <ul className="max-h-96 divide-y divide-white/[0.04] overflow-y-auto">
            {!inbox.data?.items.length && <li className="px-4 py-8 text-center text-sm text-slate-500">Nothing yet</li>}
            {inbox.data?.items.map((n) => {
              const { title, body } = describeNotification(n)
              return (
                <li key={n.id}>
                  <Link to={`/transactions/${n.transactionId}`} onClick={() => setOpen(false)} className="flex gap-3 px-4 py-3 transition hover:bg-white/[0.03]">
                    <NotificationIcon notification={n} />
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center gap-2 font-mono text-sm text-slate-100">
                        {title}
                        {!n.read && <span className="h-1.5 w-1.5 rounded-full bg-teal-300" aria-label="unread" />}
                      </span>
                      <span className="block truncate text-xs text-slate-500">
                        {body} · {formatTime(n.createdAt)}
                      </span>
                    </span>
                  </Link>
                </li>
              )
            })}
          </ul>
        </div>
      )}
    </div>
  )
}

/** "Events · live": whether the server-sent-event stream is connected. */
export function LiveIndicator({ status }: { status: LiveStatus }) {
  const dot = { live: 'bg-teal-300', connecting: 'bg-amber-300 animate-pulse', offline: 'bg-rose-400' }[status]
  const label = { live: 'Events · live', connecting: 'Events · connecting…', offline: 'Events · reconnecting' }[status]
  return (
    <div role="status" aria-label="Live events" className="flex items-center gap-2 rounded-lg bg-white/[0.03] px-3 py-2 ring-1 ring-white/[0.06]">
      <span className={`h-2 w-2 rounded-full ${dot}`} aria-hidden />
      <span className="font-mono text-[11px] text-slate-400">{label}</span>
    </div>
  )
}
