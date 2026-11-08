import { zodResolver } from '@hookform/resolvers/zod'
import { ArrowLeft, ArrowRight, ArrowUpRight, BadgeCheck, Check, KeyRound, ReceiptText } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useSearchParams } from 'react-router-dom'
import { z } from 'zod'
import { Alert, Avatar, Button, Card, Eyebrow, MonoValue, PageHeader, TextField, linkButton } from '../../components/ui'
import { isApiError } from '../../lib/apiClient'
import { applyApiError } from '../../lib/forms'
import { useIdempotencyKey } from '../../lib/idempotency'
import { amountSchema, formatInr, fromPaise, parsePaise, toPaise } from '../../lib/money'
import type { TransferReceipt } from '../../types'
import { LIMITS, lookupRecipient, useContacts, useInsights, useTransfer, useWallet } from './api'
import { paymentError } from './paymentErrors'

const schema = z.object({
  recipientEmail: z.string().min(1, 'Enter who to pay').email('Enter a valid email'),
  amount: amountSchema(LIMITS.maxTransfer, 'send'),
  memo: z.string().max(140, 'Use at most 140 characters'),
})
type Values = z.infer<typeof schema>

/** A payment the person has filled in and we have verified, plus the Idempotency-Key it will be sent with. */
interface Draft {
  recipientEmail: string
  recipientName: string
  amount: string
  memo: string
  key: string
}

type Step = { name: 'details'; initial?: Values } | { name: 'review'; draft: Draft } | { name: 'done'; receipt: TransferReceipt }

const STEPS = ['Details', 'Review', 'Done'] as const

function Stepper({ current }: { current: number }) {
  return (
    <ol className="flex items-center gap-2 font-mono text-[11px] uppercase tracking-wider" aria-label="Progress">
      {STEPS.map((label, index) => (
        <li key={label} className="flex items-center gap-2" aria-current={index === current ? 'step' : undefined}>
          <span
            className={`flex h-5 w-5 items-center justify-center rounded-full text-[10px] ring-1 ${
              index < current
                ? 'bg-teal-400 text-ink-950 ring-teal-400'
                : index === current
                  ? 'text-teal-300 ring-teal-400'
                  : 'text-slate-600 ring-white/10'
            }`}
          >
            {index < current ? <Check className="h-3 w-3" aria-hidden /> : index + 1}
          </span>
          <span className={index === current ? 'text-slate-200' : 'text-slate-600'}>{label}</span>
          {index < STEPS.length - 1 && <span className="h-px w-6 bg-white/10" aria-hidden />}
        </li>
      ))}
    </ol>
  )
}

function DetailsStep({ initial, onNext }: { initial?: Values; onNext: (draft: Omit<Draft, 'key'>) => void }) {
  const [params] = useSearchParams()
  const wallet = useWallet()
  const contacts = useContacts()
  useInsights(30) // warm the cache: the review step shows today's remaining allowance immediately
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    setValue,
    formState: { errors, isSubmitting },
  } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: initial ?? { recipientEmail: params.get('to') ?? '', amount: '', memo: '' },
  })

  const onSubmit = handleSubmit(async ({ recipientEmail, amount, memo }) => {
    setFormError(null)
    const balance = wallet.data ? parsePaise(wallet.data.balance) : null
    const paise = toPaise(amount)
    if (balance !== null && paise !== null && paise > balance) {
      setError('amount', { message: `Your balance is ${formatInr(wallet.data!.balance)}` })
      return
    }
    try {
      // Verify before paying: resolve the email to the account holder's name, like UPI does.
      const recipient = await lookupRecipient(recipientEmail.trim())
      onNext({ recipientEmail: recipient.email, recipientName: recipient.name, amount: fromPaise(paise ?? 0n), memo: memo.trim() })
    } catch (error) {
      if (isApiError(error) && error.fieldErrors.length > 0) {
        setFormError(applyApiError(error, setError))
        return
      }
      const { field, message } = paymentError(error)
      if (field) setError(field, { message })
      else setFormError(message)
    }
  })

  const frozen = wallet.data?.status === 'FROZEN'

  return (
    <Card>
      <form onSubmit={onSubmit} noValidate className="space-y-5">
        {formError && <Alert>{formError}</Alert>}
        {frozen && <Alert tone="info">Your wallet is frozen, so you cannot send money right now. Contact support.</Alert>}
        <div>
          <TextField
            label="Recipient email"
            type="email"
            autoComplete="off"
            placeholder="friend@example.com"
            error={errors.recipientEmail?.message}
            {...register('recipientEmail')}
          />
          {!!contacts.data?.length && (
            <div className="mt-2 flex flex-wrap gap-1.5" aria-label="Recent contacts">
              {contacts.data.slice(0, 5).map((contact) => (
                <button
                  key={contact.email}
                  type="button"
                  onClick={() => setValue('recipientEmail', contact.email, { shouldValidate: true })}
                  className="rounded-full bg-white/[0.04] px-2.5 py-1 text-xs text-slate-300 ring-1 ring-white/10 transition hover:text-white hover:ring-teal-400/50"
                >
                  {contact.name}
                </button>
              ))}
            </div>
          )}
        </div>
        <TextField
          label="Amount"
          inputMode="decimal"
          autoComplete="off"
          prefix="₹"
          placeholder="0.00"
          hint={`Up to ${formatInr(LIMITS.maxTransfer)} per payment, ${formatInr(LIMITS.dailyTransfer)} per day.`}
          error={errors.amount?.message}
          {...register('amount')}
        />
        <TextField label="Note (optional)" placeholder="Dinner, rent, ..." maxLength={140} error={errors.memo?.message} {...register('memo')} />
        <Button type="submit" icon={ArrowRight} loading={isSubmitting} loadingText="Verifying recipient…" disabled={frozen} className="w-full">
          Continue
        </Button>
      </form>
    </Card>
  )
}

function ReviewStep({ draft, onEdit, onDone }: { draft: Draft; onEdit: () => void; onDone: (receipt: TransferReceipt) => void }) {
  const wallet = useWallet()
  const insights = useInsights(30)
  const transfer = useTransfer()
  const [error, setError] = useState<string | null>(null)

  const balanceAfter = wallet.data ? fromPaise(parsePaise(wallet.data.balance) - parsePaise(draft.amount)) : null
  const remainingAfter = insights.data
    ? fromPaise(parsePaise(insights.data.limits.remainingToday) - parsePaise(draft.amount))
    : null

  const pay = async () => {
    setError(null)
    try {
      onDone(
        await transfer.mutateAsync({
          recipientEmail: draft.recipientEmail,
          amount: draft.amount,
          memo: draft.memo || undefined,
          idempotencyKey: draft.key,
        }),
      )
    } catch (failure) {
      // The draft, and therefore the key, is unchanged: paying again replays instead of charging twice.
      setError(paymentError(failure).message)
    }
  }

  return (
    <Card>
      <div className="space-y-5">
        {error && <Alert>{error}</Alert>}
        <div className="flex items-center gap-4 rounded-xl bg-white/[0.03] p-4 ring-1 ring-white/[0.06]">
          <Avatar name={draft.recipientName} size="lg" />
          <div className="min-w-0">
            <p className="flex items-center gap-1.5 text-base font-semibold text-white">
              {draft.recipientName}
              <BadgeCheck className="h-4 w-4 text-teal-300" aria-label="Verified recipient" />
            </p>
            <p className="truncate font-mono text-xs text-slate-400">{draft.recipientEmail}</p>
          </div>
        </div>

        <div className="text-center">
          <Eyebrow>You are sending</Eyebrow>
          <p className="mt-2 font-mono text-4xl font-semibold text-white tabular-nums" data-testid="review-amount">
            {formatInr(draft.amount)}
          </p>
          {draft.memo && <p className="mt-1 text-sm text-slate-400">“{draft.memo}”</p>}
        </div>

        <dl className="divide-y divide-white/[0.05] rounded-xl bg-white/[0.02] text-sm ring-1 ring-white/[0.06]">
          <div className="flex justify-between gap-3 px-4 py-2.5">
            <dt className="text-slate-500">Fee</dt>
            <dd className="font-mono text-slate-200">₹0.00</dd>
          </div>
          {balanceAfter && (
            <div className="flex justify-between gap-3 px-4 py-2.5">
              <dt className="text-slate-500">Your balance after</dt>
              <dd className="font-mono text-slate-200">{formatInr(balanceAfter)}</dd>
            </div>
          )}
          {remainingAfter && (
            <div className="flex justify-between gap-3 px-4 py-2.5">
              <dt className="text-slate-500">Daily allowance left after</dt>
              <dd className="font-mono text-slate-200">{formatInr(remainingAfter)}</dd>
            </div>
          )}
          <div className="flex items-center justify-between gap-3 px-4 py-2.5">
            <dt className="flex items-center gap-1.5 text-slate-500">
              <KeyRound className="h-3.5 w-3.5" aria-hidden />
              Idempotency-Key
            </dt>
            <dd>
              <MonoValue value={draft.key} display={`${draft.key.slice(0, 8)}…`} label="idempotency key" />
            </dd>
          </div>
        </dl>
        <p className="text-xs text-slate-500">
          The key travels with the payment. If the connection drops, paying again sends the same key and the server
          replays the first result, so you can never be charged twice.
        </p>

        <div className="flex gap-3">
          <Button variant="secondary" icon={ArrowLeft} onClick={onEdit} disabled={transfer.isPending}>
            Edit
          </Button>
          <Button icon={ArrowUpRight} loading={transfer.isPending} loadingText="Sending…" onClick={() => void pay()} className="flex-1">
            Pay {formatInr(draft.amount)}
          </Button>
        </div>
      </div>
    </Card>
  )
}

function DoneStep({ receipt, onAnother }: { receipt: TransferReceipt; onAnother: () => void }) {
  return (
    <Card>
      <div className="flex flex-col items-center py-4 text-center" role="status">
        <span className="flex h-16 w-16 animate-pop items-center justify-center rounded-full bg-emerald-400/15 text-emerald-300 ring-1 ring-emerald-400/40 shadow-[0_0_40px_-8px_rgb(52_211_153/0.6)]">
          <Check className="h-8 w-8" aria-hidden />
        </span>
        <p className="mt-5 font-mono text-3xl font-semibold text-white tabular-nums">{formatInr(receipt.amount)}</p>
        <p className="mt-1 text-sm text-slate-300">
          Sent to {receipt.recipientName}. Your balance is now {formatInr(receipt.balanceAfter)}.
        </p>
        <div className="mt-4">
          <MonoValue value={receipt.transactionId} display={`txn ${receipt.transactionId.slice(0, 8)}`} label="transaction ID" />
        </div>
        <div className="mt-6 flex w-full flex-col gap-3 sm:flex-row">
          <Link to={`/transactions/${receipt.transactionId}`} className={`${linkButton('secondary')} flex-1`}>
            <ReceiptText className="h-4 w-4" aria-hidden />
            View receipt
          </Link>
          <Button onClick={onAnother} className="flex-1">
            Send another
          </Button>
        </div>
      </div>
    </Card>
  )
}

/** Details, then a review with the verified recipient and the Idempotency-Key, then a receipt. */
export function SendMoneyPage() {
  const wallet = useWallet()
  const idempotency = useIdempotencyKey()
  const [step, setStep] = useState<Step>({ name: 'details' })

  const current = step.name === 'details' ? 0 : step.name === 'review' ? 1 : 2

  return (
    <div className="mx-auto max-w-xl space-y-6">
      <PageHeader
        eyebrow="Wallet · Send"
        title="Send money"
        subtitle={wallet.data ? `Available balance ${formatInr(wallet.data.balance)}` : 'Pay anyone on Ledgerly by email.'}
      />
      <Stepper current={current} />
      {step.name === 'details' && (
        <DetailsStep
          initial={step.initial}
          onNext={(draft) =>
            setStep({
              name: 'review',
              // Same content as last time => same key, so "Edit" then "Pay" unchanged cannot double-charge.
              draft: { ...draft, key: idempotency.forPayment(JSON.stringify([draft.recipientEmail, draft.amount, draft.memo])) },
            })
          }
        />
      )}
      {step.name === 'review' && (
        <ReviewStep
          draft={step.draft}
          onEdit={() =>
            setStep({
              name: 'details',
              initial: { recipientEmail: step.draft.recipientEmail, amount: step.draft.amount, memo: step.draft.memo },
            })
          }
          onDone={(receipt) => {
            idempotency.reset()
            setStep({ name: 'done', receipt })
          }}
        />
      )}
      {step.name === 'done' && <DoneStep receipt={step.receipt} onAnother={() => setStep({ name: 'details' })} />}
    </div>
  )
}
