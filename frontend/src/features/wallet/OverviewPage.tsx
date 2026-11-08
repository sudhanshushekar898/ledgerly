import { Link } from 'react-router-dom'
import { Alert, Card, PageHeader, Skeleton, Stat } from '../../components/ui'
import { describeError } from '../../lib/errors'
import { formatInr, fromPaise, parsePaise } from '../../lib/money'
import { useAuth } from '../auth/useAuth'
import { useInsights } from './api'
import { BalanceCard } from './BalanceCard'
import { BalanceChart } from './BalanceChart'
import { LimitCard } from './LimitCard'
import { QuickSend } from './QuickSend'
import { StatementList } from './StatementList'

function greeting(hour: number): string {
  if (hour < 12) return 'Good morning'
  if (hour < 17) return 'Good afternoon'
  return 'Good evening'
}

export function OverviewPage() {
  const { user } = useAuth()
  const insights = useInsights(30)
  const firstName = user?.name.split(' ')[0] ?? ''

  const net = insights.data ? parsePaise(insights.data.totalIn) - parsePaise(insights.data.totalOut) : 0n

  return (
    <div className="space-y-6">
      <PageHeader eyebrow="Wallet · Overview" title={`${greeting(new Date().getHours())}, ${firstName}`} subtitle="Your balance, today's allowance and the last 30 days." />

      <div className="grid gap-6 lg:grid-cols-12">
        <div className="lg:col-span-7">
          <BalanceCard />
        </div>
        <div className="lg:col-span-5">
          {insights.isPending ? (
            <Skeleton className="h-full min-h-[244px] rounded-2xl" />
          ) : insights.isError ? (
            <Alert>{describeError(insights.error)}</Alert>
          ) : (
            <LimitCard limits={insights.data.limits} />
          )}
        </div>
      </div>

      <Card eyebrow="Last 30 days" title="Balance and cash flow">
        {insights.isPending ? (
          <Skeleton className="h-52" />
        ) : insights.isError ? (
          <Alert>{describeError(insights.error)}</Alert>
        ) : (
          <div className="grid gap-6 lg:grid-cols-[1fr_200px]">
            <BalanceChart series={insights.data.series} />
            <div className="grid grid-cols-3 gap-3 lg:grid-cols-1">
              <Stat label="Money in" value={formatInr(insights.data.totalIn)} tone="green" />
              <Stat label="Money out" value={formatInr(insights.data.totalOut)} tone="red" />
              <Stat label="Net" value={formatInr(fromPaise(net), { signed: net > 0n })} />
            </div>
          </div>
        )}
      </Card>

      <div className="grid gap-6 lg:grid-cols-12">
        <div className="lg:col-span-5">
          <QuickSend />
        </div>
        <div className="lg:col-span-7">
          <Card
            eyebrow="Ledger"
            title="Recent activity"
            padded={false}
            action={
              <Link to="/activity" className="text-sm font-medium text-teal-300 hover:underline">
                View all
              </Link>
            }
          >
            <StatementList pageSize={6} />
          </Card>
        </div>
      </div>
    </div>
  )
}
