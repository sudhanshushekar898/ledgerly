import { ArrowLeft, CircleCheck, Lock, SearchX } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Alert, Avatar, Badge, Card, EmptyState, Eyebrow, MonoValue, Skeleton } from '../../components/ui'
import { isApiError } from '../../lib/apiClient'
import { formatDateTime } from '../../lib/dates'
import { describeError } from '../../lib/errors'
import { formatInr, fromPaise, parsePaise } from '../../lib/money'
import type { Receipt } from '../../types'
import { useReceipt } from './api'
import { describeItem, KIND_LABEL, signedAmount } from './statement'

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-xs text-slate-500">{label}</dt>
      <dd className="mt-1 text-sm text-slate-200">{children}</dd>
    </div>
  )
}

function LedgerTable({ receipt }: { receipt: Receipt }) {
  const total = (direction: 'DEBIT' | 'CREDIT') =>
    fromPaise(receipt.lines.filter((l) => l.direction === direction).reduce((sum, l) => sum + parsePaise(l.amount), 0n))
  const cell = 'px-5 py-3 text-right font-mono tabular-nums'
  return (
    <div className="overflow-x-auto">
      <table className="min-w-full text-sm">
        <thead>
          <tr className="border-b border-white/[0.06] text-left font-mono text-[10px] uppercase tracking-[0.14em] text-slate-500">
            <th className="px-5 py-2.5 font-medium">Account</th>
            <th className="px-5 py-2.5 text-right font-medium">Debit</th>
            <th className="px-5 py-2.5 text-right font-medium">Credit</th>
            <th className="px-5 py-2.5 text-right font-medium">Balance after</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-white/[0.04]">
          {receipt.lines.map((line) => (
            <tr key={`${line.account}-${line.direction}-${line.label}`}>
              <td className="px-5 py-3 text-slate-200">
                <span className="flex items-center gap-2">
                  {line.label}
                  {line.account === 'YOUR_WALLET' && <Badge tone="teal">you</Badge>}
                  {line.account === 'TOPUP_SOURCE' && <Badge tone="violet">system</Badge>}
                </span>
              </td>
              <td className={`${cell} text-rose-300`}>{line.direction === 'DEBIT' ? formatInr(line.amount) : <span className="text-slate-600">—</span>}</td>
              <td className={`${cell} text-emerald-300`}>{line.direction === 'CREDIT' ? formatInr(line.amount) : <span className="text-slate-600">—</span>}</td>
              <td className={`${cell} text-slate-300`}>
                {line.balanceAfter ? (
                  formatInr(line.balanceAfter)
                ) : (
                  <span className="inline-flex items-center gap-1 text-xs text-slate-600" title="Other people's balances are private">
                    <Lock className="h-3 w-3" aria-hidden />
                    private
                  </span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr className="border-t border-white/10 bg-white/[0.02]">
            <td className="px-5 py-3 font-mono text-[11px] uppercase tracking-wider text-slate-500">Totals</td>
            <td className={`${cell} text-slate-100`}>{formatInr(total('DEBIT'))}</td>
            <td className={`${cell} text-slate-100`}>{formatInr(total('CREDIT'))}</td>
            <td className="px-5 py-3 text-right">
              {receipt.balanced ? (
                <Badge tone="green">
                  <CircleCheck className="h-3 w-3" aria-hidden />
                  Balanced
                </Badge>
              ) : (
                <Badge tone="red">Unbalanced</Badge>
              )}
            </td>
          </tr>
        </tfoot>
      </table>
    </div>
  )
}

export function TransactionPage() {
  const { transactionId = '' } = useParams()
  const receipt = useReceipt(transactionId)

  const back = (
    <Link to="/activity" className="inline-flex items-center gap-1.5 text-sm text-slate-400 hover:text-slate-100">
      <ArrowLeft className="h-4 w-4" aria-hidden />
      Activity
    </Link>
  )

  if (receipt.isPending) {
    return (
      <div className="mx-auto max-w-3xl space-y-6">
        {back}
        <Skeleton className="h-56 rounded-2xl" />
        <Skeleton className="h-48 rounded-2xl" />
      </div>
    )
  }
  if (receipt.isError) {
    return (
      <div className="mx-auto max-w-3xl space-y-6">
        {back}
        {isApiError(receipt.error, 'TRANSACTION_NOT_FOUND') ? (
          <EmptyState icon={SearchX} title="Transaction not found">
            It does not exist, or it does not involve your wallet.
          </EmptyState>
        ) : (
          <Alert>{describeError(receipt.error)}</Alert>
        )}
      </div>
    )
  }

  const r = receipt.data
  const { title, icon: Icon } = describeItem(r)
  const credit = r.direction === 'CREDIT'

  return (
    <div className="mx-auto max-w-3xl space-y-6 animate-fade-up">
      {back}
      <Card>
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="flex items-center gap-4">
            {r.kind === 'TRANSFER' && r.counterpartyName ? (
              <Avatar name={r.counterpartyName} size="lg" />
            ) : (
              <span className="flex h-14 w-14 items-center justify-center rounded-full bg-white/[0.05] text-slate-300 ring-1 ring-white/10">
                <Icon className="h-6 w-6" aria-hidden />
              </span>
            )}
            <div>
              <h1 className="text-lg font-semibold text-white">{title}</h1>
              <div className="mt-1 flex flex-wrap gap-1.5">
                <Badge mono>{KIND_LABEL[r.kind]}</Badge>
                {r.reversedBy ? <Badge tone="amber">Reversed</Badge> : <Badge tone="green">Completed</Badge>}
              </div>
            </div>
          </div>
          <p className={`font-mono text-3xl font-semibold tabular-nums ${credit ? 'text-emerald-300' : 'text-white'}`} data-testid="receipt-amount">
            {signedAmount(r)}
          </p>
        </div>

        <dl className="mt-6 grid gap-5 border-t border-white/[0.06] pt-5 sm:grid-cols-2">
          <Detail label="Date and time">{formatDateTime(r.createdAt)}</Detail>
          <Detail label="Note">{r.memo ?? <span className="text-slate-600">—</span>}</Detail>
          <Detail label="Transaction ID">
            <MonoValue value={r.transactionId} display={`${r.transactionId.slice(0, 8)}…${r.transactionId.slice(-4)}`} label="transaction ID" />
          </Detail>
          {r.reverses && (
            <Detail label="Reverses">
              <Link to={`/transactions/${r.reverses}`} className="font-mono text-xs text-teal-300 hover:underline">
                {r.reverses.slice(0, 8)}…
              </Link>
            </Detail>
          )}
          {r.reversedBy && (
            <Detail label="Reversed by">
              <Link to={`/transactions/${r.reversedBy}`} className="font-mono text-xs text-teal-300 hover:underline">
                {r.reversedBy.slice(0, 8)}…
              </Link>
            </Detail>
          )}
        </dl>
      </Card>

      <Card eyebrow="Double-entry ledger" title="How this was recorded" padded={false}>
        <LedgerTable receipt={r} />
        <div className="border-t border-white/[0.06] px-5 py-4">
          <Eyebrow>Why two lines?</Eyebrow>
          <p className="mt-1.5 text-sm text-slate-400">
            Every movement is written twice: once leaving an account and once entering another, so money is never created
            or lost. The database itself rejects any transaction whose debits and credits differ, and the history can only
            be appended to, never edited.
          </p>
        </div>
      </Card>
    </div>
  )
}
