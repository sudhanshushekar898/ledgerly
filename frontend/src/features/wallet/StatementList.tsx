import { ChevronRight, ReceiptText } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Alert, Avatar, EmptyState, Skeleton } from '../../components/ui'
import { formatTime, groupByDay } from '../../lib/dates'
import { describeError } from '../../lib/errors'
import { formatInr } from '../../lib/money'
import type { StatementItem } from '../../types'
import { useStatement } from './api'
import { describeItem, signedAmount } from './statement'

function StatementRow({ item }: { item: StatementItem }) {
  const { title, icon: Icon } = describeItem(item)
  const credit = item.direction === 'CREDIT'
  const person = item.kind === 'TRANSFER' && item.counterpartyName
  const details = [item.memo, formatTime(item.createdAt)].filter(Boolean).join(' · ')
  return (
    <li>
      <Link
        to={`/transactions/${item.transactionId}`}
        className="group flex items-center gap-4 px-5 py-3.5 transition hover:bg-white/[0.03] focus-visible:bg-white/[0.04] focus-visible:outline-none"
      >
        {person ? (
          <Avatar name={item.counterpartyName} />
        ) : (
          <span
            className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-full ring-1 ${credit ? 'bg-emerald-400/10 text-emerald-300 ring-emerald-400/25' : 'bg-white/[0.05] text-slate-300 ring-white/10'}`}
          >
            <Icon className="h-[18px] w-[18px]" aria-hidden />
          </span>
        )}
        <div className="min-w-0 flex-1">
          {/* Two lines before clipping, so "Sent to <long name>" stays readable on a phone. */}
          <p className="line-clamp-2 break-words text-sm font-medium text-slate-100">{title}</p>
          <p className="truncate text-xs text-slate-500">{details}</p>
        </div>
        <div className="text-right">
          <p className={`whitespace-nowrap font-mono text-sm font-semibold tabular-nums ${credit ? 'text-emerald-300' : 'text-slate-100'}`}>
            {signedAmount(item)}
          </p>
          <p className="whitespace-nowrap font-mono text-[11px] text-slate-500 tabular-nums">bal {formatInr(item.balanceAfter)}</p>
        </div>
        <ChevronRight className="hidden h-4 w-4 text-slate-600 transition group-hover:text-slate-400 sm:block" aria-hidden />
      </Link>
    </li>
  )
}

/** Entries grouped under sticky day headers ("Today", "Yesterday", "Sat, 7 Nov 2026"). */
export function StatementGroups({ items }: { items: StatementItem[] }) {
  return (
    <ul aria-label="Wallet activity">
      {groupByDay(items).map((group) => (
        <li key={group.key}>
          <p className="sticky top-0 z-[1] border-y border-white/[0.04] bg-ink-900/95 px-5 py-1.5 font-mono text-[10px] uppercase tracking-[0.14em] text-slate-500 backdrop-blur">
            {group.label}
          </p>
          <ul className="divide-y divide-white/[0.04]">
            {group.items.map((item) => (
              <StatementRow key={item.seq} item={item} />
            ))}
          </ul>
        </li>
      ))}
    </ul>
  )
}

export function StatementSkeleton({ rows = 4 }: { rows?: number }) {
  return (
    <div className="space-y-4 p-5" aria-label="Loading activity" role="status">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="flex items-center gap-4">
          <Skeleton className="h-10 w-10 rounded-full" />
          <div className="flex-1 space-y-2">
            <Skeleton className="h-3 w-1/2" />
            <Skeleton className="h-2.5 w-1/3" />
          </div>
          <Skeleton className="h-4 w-20" />
        </div>
      ))}
    </div>
  )
}

/** First page of the statement, for the overview. */
export function StatementList({ pageSize }: { pageSize: number }) {
  const statement = useStatement(pageSize)

  if (statement.isPending) return <StatementSkeleton />
  if (statement.isError) {
    return (
      <div className="p-5">
        <Alert>{describeError(statement.error)}</Alert>
      </div>
    )
  }
  const items = statement.data.pages[0]?.items ?? []
  if (items.length === 0) {
    return (
      <EmptyState icon={ReceiptText} title="No activity yet">
        Add money to your wallet to get started.
      </EmptyState>
    )
  }
  return <StatementGroups items={items} />
}
