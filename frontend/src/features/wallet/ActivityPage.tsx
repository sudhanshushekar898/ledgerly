import { Download, ReceiptText, Search } from 'lucide-react'
import { useState } from 'react'
import { Alert, Button, Card, EmptyState, PageHeader } from '../../components/ui'
import { downloadCsv, statementCsv } from '../../lib/csv'
import { describeError } from '../../lib/errors'
import type { StatementItem } from '../../types'
import { useStatement } from './api'
import { StatementGroups, StatementSkeleton } from './StatementList'

type Filter = 'all' | 'in' | 'out'

const FILTERS: { value: Filter; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'in', label: 'Money in' },
  { value: 'out', label: 'Money out' },
]

function matches(item: StatementItem, filter: Filter, search: string): boolean {
  if (filter === 'in' && item.direction !== 'CREDIT') return false
  if (filter === 'out' && item.direction !== 'DEBIT') return false
  const q = search.trim().toLowerCase()
  return !q || `${item.counterpartyName ?? ''} ${item.memo ?? ''}`.toLowerCase().includes(q)
}

export function ActivityPage() {
  const statement = useStatement(20)
  const [filter, setFilter] = useState<Filter>('all')
  const [search, setSearch] = useState('')

  const loaded = statement.data?.pages.flatMap((page) => page.items) ?? []
  const visible = loaded.filter((item) => matches(item, filter, search))

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Wallet · Activity"
        title="Activity"
        subtitle="Every ledger entry on your wallet, newest first. Open one to see how it was recorded."
        actions={
          <Button
            variant="secondary"
            icon={Download}
            disabled={loaded.length === 0}
            onClick={() => downloadCsv(`ledgerly-statement-${new Date().toISOString().slice(0, 10)}.csv`, statementCsv(loaded))}
          >
            Export CSV
          </Button>
        }
      />

      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div role="radiogroup" aria-label="Filter activity" className="inline-flex rounded-lg bg-white/[0.04] p-1 ring-1 ring-white/[0.06]">
          {FILTERS.map(({ value, label }) => (
            <button
              key={value}
              type="button"
              role="radio"
              aria-checked={filter === value}
              onClick={() => setFilter(value)}
              className={`rounded-md px-3 py-1.5 text-sm font-medium transition ${filter === value ? 'bg-white/10 text-white' : 'text-slate-400 hover:text-slate-200'}`}
            >
              {label}
            </button>
          ))}
        </div>
        <label className="relative block sm:w-72">
          <span className="sr-only">Search by name or note</span>
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-500" aria-hidden />
          <input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search name or note"
            className="w-full rounded-lg border-0 bg-white/[0.04] py-2 pl-9 pr-3 text-sm text-slate-100 ring-1 ring-inset ring-white/10 placeholder:text-slate-500 focus:ring-2 focus:ring-teal-400"
          />
        </label>
      </div>

      <Card padded={false}>
        {statement.isPending ? (
          <StatementSkeleton rows={6} />
        ) : statement.isError ? (
          <div className="p-5">
            <Alert>{describeError(statement.error)}</Alert>
          </div>
        ) : visible.length === 0 ? (
          <EmptyState icon={ReceiptText} title={loaded.length === 0 ? 'No activity yet' : 'Nothing matches'}>
            {loaded.length === 0 ? 'Add money to your wallet to get started.' : 'Try another filter, or load older activity.'}
          </EmptyState>
        ) : (
          <StatementGroups items={visible} />
        )}
        {statement.hasNextPage && (
          <div className="border-t border-white/[0.06] p-4 text-center">
            <Button variant="secondary" loading={statement.isFetchingNextPage} onClick={() => void statement.fetchNextPage()}>
              Load older activity
            </Button>
          </div>
        )}
      </Card>
      <p className="text-center font-mono text-[11px] text-slate-600">
        Filters apply to the {loaded.length} entries loaded so far · keyset pagination on the per-account sequence
      </p>
    </div>
  )
}
