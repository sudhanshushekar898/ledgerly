import { zodResolver } from '@hookform/resolvers/zod'
import { Building2, Plus } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router-dom'
import { z } from 'zod'
import { Alert, Button, Card, MonoValue, PageHeader, TextField } from '../../components/ui'
import { useIdempotencyKey } from '../../lib/idempotency'
import { amountSchema, formatInr, fromPaise, toPaise } from '../../lib/money'
import type { TopUpReceipt } from '../../types'
import { LIMITS, useTopUp, useWallet } from './api'
import { paymentError } from './paymentErrors'

const QUICK_AMOUNTS = ['500', '1000', '5000', '10000']

const schema = z.object({ amount: amountSchema(LIMITS.maxTopUp, 'add') })
type Values = z.infer<typeof schema>

export function AddMoneyPage() {
  const wallet = useWallet()
  const topUp = useTopUp()
  const idempotency = useIdempotencyKey()
  const [receipt, setReceipt] = useState<{ result: TopUpReceipt; key: string } | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    setValue,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { amount: '' } })

  const onSubmit = handleSubmit(async ({ amount }) => {
    setFormError(null)
    setReceipt(null)
    const normalised = fromPaise(toPaise(amount) ?? 0n)
    // Same amount as the failed attempt => same key, so a retry after a timeout is a replay, not a second top-up.
    const key = idempotency.forPayment(normalised)
    try {
      const result = await topUp.mutateAsync({ amount: normalised, idempotencyKey: key })
      idempotency.reset()
      reset()
      setReceipt({ result, key })
    } catch (error) {
      const { field, message } = paymentError(error)
      if (field === 'amount') setError('amount', { message })
      else setFormError(message)
    }
  })

  return (
    <div className="mx-auto max-w-xl space-y-6">
      <PageHeader
        eyebrow="Wallet · Add money"
        title="Add money"
        subtitle={wallet.data ? `Current balance ${formatInr(wallet.data.balance)}` : 'Top up your wallet.'}
      />
      {receipt && (
        <Alert tone="success">
          Added <strong>{formatInr(receipt.result.amount)}</strong>. Your balance is now <strong>{formatInr(receipt.result.balanceAfter)}</strong>.
          <span className="mt-2 flex flex-wrap items-center gap-2 text-xs">
            <Link to={`/transactions/${receipt.result.transactionId}`} className="font-medium underline">
              View receipt
            </Link>
            <span className="opacity-70">Idempotency-Key</span>
            <MonoValue value={receipt.key} display={`${receipt.key.slice(0, 8)}…`} label="idempotency key" />
          </span>
        </Alert>
      )}
      <Card>
        <form onSubmit={onSubmit} noValidate className="space-y-5">
          {formError && <Alert>{formError}</Alert>}
          <div className="flex items-center gap-3 rounded-xl bg-white/[0.03] p-3 ring-1 ring-white/[0.06]">
            <span className="flex h-10 w-10 items-center justify-center rounded-lg bg-violet-400/10 text-violet-300 ring-1 ring-violet-400/25">
              <Building2 className="h-5 w-5" aria-hidden />
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium text-slate-100">Demo Bank</p>
              <p className="font-mono text-xs text-slate-500">savings •••• 4242 · simulated</p>
            </div>
          </div>
          <TextField
            label="Amount"
            inputMode="decimal"
            autoComplete="off"
            prefix="₹"
            placeholder="0.00"
            hint={`Up to ${formatInr(LIMITS.maxTopUp)} at a time.`}
            error={errors.amount?.message}
            {...register('amount')}
          />
          <div className="flex flex-wrap gap-2" aria-label="Quick amounts">
            {QUICK_AMOUNTS.map((amount) => (
              <button
                key={amount}
                type="button"
                onClick={() => setValue('amount', amount, { shouldValidate: true })}
                className="rounded-full bg-white/[0.04] px-3 py-1.5 font-mono text-sm text-slate-200 ring-1 ring-white/10 transition hover:ring-teal-400/50"
              >
                {formatInr(amount).replace('.00', '')}
              </button>
            ))}
          </div>
          <Button type="submit" icon={Plus} loading={isSubmitting} loadingText="Adding…" className="w-full">
            Add money
          </Button>
          <p className="text-center text-xs text-slate-500">Demo wallet: money comes from a simulated bank, no real payment is taken.</p>
        </form>
      </Card>
    </div>
  )
}
