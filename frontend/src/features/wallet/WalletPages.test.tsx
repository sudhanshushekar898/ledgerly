import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it } from 'vitest'
import { item, receipt, renderAt, serveWalletReads, signIn, statement, wallet } from '../../test/fixtures'
import { server } from '../../test/server'
import { ActivityPage } from './ActivityPage'
import { OverviewPage } from './OverviewPage'
import { TransactionPage } from './TransactionPage'

const RECENT = statement([
  item({ seq: 3, kind: 'TRANSFER', direction: 'DEBIT', amount: '250.00', counterpartyName: 'Ravi Kumar', memo: 'Dinner', transactionId: 't-3' }),
  item({ seq: 2, kind: 'TRANSFER', direction: 'CREDIT', amount: '1000.00', counterpartyName: 'Meera Shah', transactionId: 't-2' }),
  item({ seq: 1, kind: 'TOPUP', direction: 'CREDIT', amount: '500.00', transactionId: 't-1' }),
])

describe('OverviewPage', () => {
  beforeEach(() => signIn())

  it('shows the balance, today\'s allowance, the 30-day chart and quick-send contacts', async () => {
    serveWalletReads({ wallet: wallet({ balance: '123456.50' }), statement: RECENT })
    renderAt(<OverviewPage />)

    expect(await screen.findByTestId('balance')).toHaveTextContent('₹1,23,456.50')
    expect(screen.getByRole('heading', { name: /, Asha$/ })).toBeInTheDocument()
    expect(screen.getByText('•••• •••• A3F9')).toBeInTheDocument()
    expect(await screen.findByTestId('remaining-today')).toHaveTextContent('₹49,750.00')
    expect(screen.getByText('0.5%')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /Balance over the last 30 days: from ₹750.00 to ₹1,500.00/ })).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Send money to Ravi Kumar' })).toHaveAttribute('href', '/send?to=ravi%40example.com')
  })

  it('lists recent activity as links to each receipt', async () => {
    serveWalletReads({ statement: RECENT })
    renderAt(<OverviewPage />)

    const list = await screen.findByRole('list', { name: 'Wallet activity' })
    const sent = within(list).getByRole('link', { name: /Sent to Ravi Kumar/ })
    expect(sent).toHaveAttribute('href', '/transactions/t-3')
    expect(sent).toHaveTextContent('−₹250.00')
    expect(sent).toHaveTextContent('Dinner')
    expect(within(list).getByRole('link', { name: /Received from Meera Shah/ })).toHaveTextContent('+₹1,000.00')
    expect(within(list).getByRole('link', { name: /Added money/ })).toBeInTheDocument()
  })

  it('hides and shows the balance', async () => {
    serveWalletReads()
    renderAt(<OverviewPage />)
    await screen.findByText('₹1,500.00')

    await userEvent.click(screen.getByRole('button', { name: 'Hide balance' }))
    expect(screen.getByLabelText('Balance hidden')).toBeInTheDocument()
    expect(screen.queryByText('₹1,500.00')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Show balance' }))
    expect(screen.getByTestId('balance')).toHaveTextContent('₹1,500.00')
  })

  it('shows a frozen wallet clearly', async () => {
    serveWalletReads({ wallet: wallet({ status: 'FROZEN' }) })
    renderAt(<OverviewPage />)

    expect(await screen.findByText(/Frozen: payments are paused/)).toBeInTheDocument()
    expect(await screen.findByText('No activity yet')).toBeInTheDocument()
  })
})

describe('ActivityPage', () => {
  beforeEach(() => signIn())

  it('filters by direction and searches names and notes', async () => {
    serveWalletReads({ statement: RECENT })
    renderAt(<ActivityPage />)
    const list = await screen.findByRole('list', { name: 'Wallet activity' })
    expect(within(list).getAllByRole('link')).toHaveLength(3)

    await userEvent.click(screen.getByRole('radio', { name: 'Money out' }))
    expect(within(screen.getByRole('list', { name: 'Wallet activity' })).getAllByRole('link')).toHaveLength(1)

    await userEvent.click(screen.getByRole('radio', { name: 'All' }))
    await userEvent.type(screen.getByPlaceholderText('Search name or note'), 'meera')
    const filtered = within(screen.getByRole('list', { name: 'Wallet activity' })).getAllByRole('link')
    expect(filtered).toHaveLength(1)
    expect(filtered[0]).toHaveTextContent('Received from Meera Shah')

    await userEvent.clear(screen.getByPlaceholderText('Search name or note'))
    await userEvent.type(screen.getByPlaceholderText('Search name or note'), 'zzz')
    expect(screen.getByText('Nothing matches')).toBeInTheDocument()
  })

  it('loads older activity with the keyset cursor until there is no more', async () => {
    const cursors: (string | null)[] = []
    server.use(
      http.get('*/api/v1/wallet/statement', ({ request }) => {
        const before = new URL(request.url).searchParams.get('before')
        cursors.push(before)
        return HttpResponse.json(
          before === null
            ? statement([item({ seq: 3 }), item({ seq: 2 })], 2)
            : statement([item({ seq: 1, kind: 'TRANSFER', direction: 'DEBIT', counterpartyName: 'Ravi Kumar' })]),
        )
      }),
    )
    renderAt(<ActivityPage />)
    await screen.findByRole('list', { name: 'Wallet activity' })

    await userEvent.click(screen.getByRole('button', { name: 'Load older activity' }))

    expect(await screen.findByText('Sent to Ravi Kumar')).toBeInTheDocument()
    expect(cursors).toEqual([null, '2'])
    expect(screen.queryByRole('button', { name: 'Load older activity' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Export CSV' })).toBeEnabled()
  })
})

describe('TransactionPage', () => {
  beforeEach(() => signIn())

  function renderReceipt(id = '0190cccc-0000-7000-8000-000000000001') {
    return renderAt(
      <Routes>
        <Route path="/transactions/:transactionId" element={<TransactionPage />} />
      </Routes>,
      `/transactions/${id}`,
    )
  }

  it('shows the double-entry lines, keeps the other balance private, and proves they balance', async () => {
    server.use(http.get('*/api/v1/wallet/transactions/:id', () => HttpResponse.json(receipt())))
    renderReceipt()

    expect(await screen.findByRole('heading', { name: 'Sent to Ravi Kumar' })).toBeInTheDocument()
    expect(screen.getByTestId('receipt-amount')).toHaveTextContent('−₹250.50')
    const rows = within(screen.getByRole('table')).getAllByRole('row')
    expect(rows[1]).toHaveTextContent('Your wallet')
    expect(rows[1]).toHaveTextContent('₹1,249.50')
    expect(rows[2]).toHaveTextContent("Ravi Kumar's wallet")
    expect(rows[2]).toHaveTextContent('private')
    expect(rows[3]).toHaveTextContent('Totals')
    expect(within(rows[3]!).getAllByText('₹250.50')).toHaveLength(2)
    expect(within(rows[3]!).getByText('Balanced')).toBeInTheDocument()
  })

  it('marks a reversed payment and links the reversal', async () => {
    server.use(http.get('*/api/v1/wallet/transactions/:id', () => HttpResponse.json(receipt({ reversedBy: '0190ffff-0000-7000-8000-000000000009' }))))
    renderReceipt()

    expect(await screen.findByText('Reversed')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /0190ffff/ })).toHaveAttribute('href', '/transactions/0190ffff-0000-7000-8000-000000000009')
  })

  it('says "not found" for a transaction that is not yours', async () => {
    server.use(
      http.get('*/api/v1/wallet/transactions/:id', () =>
        HttpResponse.json({ status: 404, code: 'TRANSACTION_NOT_FOUND', detail: 'No such transaction.' }, { status: 404 }),
      ),
    )
    renderReceipt()

    expect(await screen.findByText('Transaction not found')).toBeInTheDocument()
  })
})
