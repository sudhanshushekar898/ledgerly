import type { LucideIcon } from 'lucide-react'
import { BadgeCheck, CircleCheck, CircleX, Copy, FlaskConical, Play, Unplug, Zap } from 'lucide-react'
import { useState } from 'react'
import { Alert, Avatar, Badge, Button, Card, PageHeader, TextField } from '../../components/ui'
import { isApiError } from '../../lib/apiClient'
import { describeError } from '../../lib/errors'
import { formatInr } from '../../lib/money'
import type { Contact } from '../../types'
import { lookupRecipient, useContacts, useRefreshWallet } from '../wallet/api'
import { paymentError } from '../wallet/paymentErrors'
import type { Outcome, Probe, Report } from './experiments'
import { duplicateStorm, lostResponse, overdrawRace } from './experiments'

const OUTCOME: Record<Outcome, { bar: string; tone: 'teal' | 'violet' | 'amber' | 'red' }> = {
  executed: { bar: 'bg-teal-400', tone: 'teal' },
  replayed: { bar: 'bg-violet-400', tone: 'violet' },
  rejected: { bar: 'bg-amber-400', tone: 'amber' },
  failed: { bar: 'bg-rose-500', tone: 'red' },
}

/** Each request as a bar on a shared time axis: you can see them overlap, which is the point. */
function Timeline({ probes }: { probes: Probe[] }) {
  const end = Math.max(1, ...probes.map((p) => p.startMs + p.durationMs))
  return (
    <ul className="space-y-1.5" aria-label="Requests">
      {probes.map((p) => (
        <li key={p.index} className="grid grid-cols-[92px_1fr_auto] items-center gap-3 text-xs">
          <span className="truncate font-mono text-slate-400">{p.label}</span>
          <span className="relative h-2.5 rounded-full bg-white/[0.04]">
            <span
              className={`absolute inset-y-0 rounded-full ${OUTCOME[p.outcome].bar}`}
              style={{ left: `${(p.startMs / end) * 100}%`, width: `${Math.max(2, (p.durationMs / end) * 100)}%` }}
            />
          </span>
          <span className="flex items-center gap-1.5">
            <Badge mono tone={OUTCOME[p.outcome].tone}>
              {p.status || 'ERR'} {p.code ?? p.outcome}
            </Badge>
            <span className="w-12 text-right font-mono text-slate-500">{p.durationMs} ms</span>
          </span>
        </li>
      ))}
    </ul>
  )
}

function ReportView({ report }: { report: Report }) {
  return (
    <div className="mt-4 space-y-4 animate-fade-up">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Badge tone={report.passed ? 'green' : 'red'}>
          {report.passed ? <CircleCheck className="h-3 w-3" aria-hidden /> : <CircleX className="h-3 w-3" aria-hidden />}
          {report.passed ? 'PASSED' : 'FAILED'}
        </Badge>
        <span className="font-mono text-[11px] text-slate-500">
          {formatInr(report.balanceBefore)} → {formatInr(report.balanceAfter)} · {report.elapsedMs} ms
        </span>
      </div>
      <Timeline probes={report.probes} />
      <ul className="space-y-1.5" aria-label="Checks">
        {report.checks.map((check) => (
          <li key={check.label} className="flex items-start gap-2 text-sm">
            {check.passed ? (
              <CircleCheck className="mt-0.5 h-4 w-4 shrink-0 text-emerald-300" aria-label="passed" />
            ) : (
              <CircleX className="mt-0.5 h-4 w-4 shrink-0 text-rose-300" aria-label="failed" />
            )}
            <span className="flex-1 text-slate-300">{check.label}</span>
            <span className="font-mono text-[11px] text-slate-500">{check.detail}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

interface ExperimentSpec {
  id: string
  icon: LucideIcon
  title: string
  claim: string
  how: string
  run: (recipientEmail: string) => Promise<Report>
}

const EXPERIMENTS: ExperimentSpec[] = [
  {
    id: 'storm',
    icon: Copy,
    title: 'Double-click storm',
    claim: 'Five identical payments with one Idempotency-Key, all in flight at once, charge you exactly once.',
    how: 'Sends ₹1.00 five times in parallel with the same key.',
    run: (recipientEmail) => duplicateStorm({ recipientEmail }),
  },
  {
    id: 'race',
    icon: Zap,
    title: 'Overdraw race',
    claim: 'Parallel payments asking for more than your balance never take it below zero.',
    how: 'Fires six payments of 60% of your balance (capped at ₹25,000) at the same moment.',
    run: (recipientEmail) => overdrawRace({ recipientEmail }),
  },
  {
    id: 'lost',
    icon: Unplug,
    title: 'Lost response',
    claim: 'If the response is lost after the server commits, the retry returns the original payment.',
    how: 'Sends ₹1.00, throws the response away, then retries with the same key.',
    run: (recipientEmail) => lostResponse({ recipientEmail }),
  },
]

function ExperimentCard({ spec, recipient }: { spec: ExperimentSpec; recipient: Contact | null }) {
  const refresh = useRefreshWallet()
  const [running, setRunning] = useState(false)
  const [result, setResult] = useState<Report | null>(null)
  const [error, setError] = useState<string | null>(null)

  const run = async () => {
    if (!recipient) return
    setRunning(true)
    setError(null)
    try {
      setResult(await spec.run(recipient.email))
    } catch (failure) {
      setResult(null)
      setError(failure instanceof Error ? failure.message : describeError(failure))
    } finally {
      setRunning(false)
      void refresh()
    }
  }

  return (
    <Card>
      <div className="flex items-start gap-3">
        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-teal-400/10 text-teal-300 ring-1 ring-teal-400/25">
          <spec.icon className="h-5 w-5" aria-hidden />
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-semibold text-white">{spec.title}</h2>
          <p className="mt-1 text-sm text-slate-300">{spec.claim}</p>
          <p className="mt-1 font-mono text-[11px] text-slate-500">{spec.how}</p>
        </div>
      </div>
      <Button
        className="mt-4 w-full"
        variant="secondary"
        icon={Play}
        loading={running}
        loadingText="Running…"
        disabled={!recipient}
        onClick={() => void run()}
      >
        Run experiment
      </Button>
      {error && (
        <div className="mt-4">
          <Alert>{error}</Alert>
        </div>
      )}
      {result && <ReportView report={result} />}
    </Card>
  )
}

function RecipientPicker({ recipient, onChange }: { recipient: Contact | null; onChange: (contact: Contact | null) => void }) {
  const contacts = useContacts()
  const [email, setEmail] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [checking, setChecking] = useState(false)

  const verify = async (candidate: string) => {
    setError(null)
    setChecking(true)
    try {
      onChange(await lookupRecipient(candidate.trim()))
    } catch (failure) {
      onChange(null)
      setError(isApiError(failure) ? paymentError(failure).message : describeError(failure))
    } finally {
      setChecking(false)
    }
  }

  return (
    <Card eyebrow="Counterparty" title="Who receives the test payments?">
      {recipient ? (
        <div className="flex items-center gap-3">
          <Avatar name={recipient.name} />
          <div className="min-w-0 flex-1">
            <p className="flex items-center gap-1.5 text-sm font-medium text-white">
              {recipient.name}
              <BadgeCheck className="h-4 w-4 text-teal-300" aria-label="Verified recipient" />
            </p>
            <p className="truncate font-mono text-xs text-slate-500">{recipient.email}</p>
          </div>
          <Button variant="ghost" onClick={() => onChange(null)}>
            Change
          </Button>
        </div>
      ) : (
        <form
          className="space-y-3"
          onSubmit={(event) => {
            event.preventDefault()
            void verify(email)
          }}
        >
          {!!contacts.data?.length && (
            <div className="flex flex-wrap gap-1.5" aria-label="Recent contacts">
              {contacts.data.slice(0, 6).map((contact) => (
                <button
                  key={contact.email}
                  type="button"
                  onClick={() => onChange(contact)}
                  className="rounded-full bg-white/[0.04] px-2.5 py-1 text-xs text-slate-300 ring-1 ring-white/10 hover:ring-teal-400/50"
                >
                  {contact.name}
                </button>
              ))}
            </div>
          )}
          <div className="flex items-end gap-2">
            <TextField
              className="flex-1"
              label="Recipient email"
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              error={error ?? undefined}
              placeholder="friend@example.com"
            />
            <Button type="submit" variant="secondary" loading={checking} loadingText="Checking…" disabled={!email.trim()}>
              Verify
            </Button>
          </div>
        </form>
      )}
    </Card>
  )
}

export function LabPage() {
  const [recipient, setRecipient] = useState<Contact | null>(null)

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Engineering · Resilience lab"
        title="Resilience lab"
        subtitle="Real experiments against the real API with your own synthetic money. Each one states a guarantee, attacks it, and checks the invariants."
      />
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
        <div className="space-y-4">
          <RecipientPicker recipient={recipient} onChange={setRecipient} />
          <Card eyebrow="Legend" title="Reading the results">
            <ul className="space-y-2 text-sm text-slate-400">
              <li className="flex items-center gap-2"><span className="h-2 w-6 rounded-full bg-teal-400" /> executed by the server</li>
              <li className="flex items-center gap-2"><span className="h-2 w-6 rounded-full bg-violet-400" /> replayed from the idempotency store</li>
              <li className="flex items-center gap-2"><span className="h-2 w-6 rounded-full bg-amber-400" /> refused by a business rule</li>
              <li className="flex items-center gap-2"><span className="h-2 w-6 rounded-full bg-rose-500" /> server or network failure</li>
            </ul>
            <p className="mt-3 flex items-start gap-2 text-xs text-slate-500">
              <FlaskConical className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
              Every request also appears in the API inspector, with its request id and key.
            </p>
          </Card>
        </div>
        <div className="space-y-4">
          {!recipient && <Alert tone="info">Pick or verify a recipient to enable the experiments.</Alert>}
          {EXPERIMENTS.map((spec) => (
            <ExperimentCard key={spec.id} spec={spec} recipient={recipient} />
          ))}
        </div>
      </div>
    </div>
  )
}
