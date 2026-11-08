import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it } from 'vitest'
import { problem, RAVI, renderAt, serveWalletReads, signIn, wallet } from '../../test/fixtures'
import { server } from '../../test/server'
import type { TransferReceipt } from '../../types'
import { SendMoneyPage } from './SendMoneyPage'

const RECEIPT: TransferReceipt = {
  transactionId: '0190cccc-0000-7000-8000-000000000001',
  amount: '250.50',
  recipientName: 'Ravi Kumar',
  memo: 'Dinner',
  balanceAfter: '1249.50',
  createdAt: '2026-11-08T10:00:00Z',
}

function renderSend(path = '/send') {
  return renderAt(
    <Routes>
      <Route path="/send" element={<SendMoneyPage />} />
      <Route path="/transactions/:id" element={<p>Receipt page</p>} />
    </Routes>,
    path,
  )
}

function serveLookup() {
  server.use(http.get('*/api/v1/recipients', () => HttpResponse.json(RAVI)))
}

/** Captures the Idempotency-Key of every payment attempt; `fail` decides which attempts lose their response. */
function servePayments(fail: (attempt: number) => boolean = () => false) {
  const keys: (string | null)[] = []
  server.use(
    http.post('*/api/v1/transfers', ({ request }) => {
      keys.push(request.headers.get('Idempotency-Key'))
      return fail(keys.length) ? HttpResponse.error() : HttpResponse.json(RECEIPT, { status: 201 })
    }),
  )
  return keys
}

async function fillDetails({ email = 'ravi@example.com', amount = '250.50', memo = 'Dinner' } = {}) {
  const field = screen.getByLabelText('Recipient email')
  await userEvent.clear(field)
  await userEvent.type(field, email)
  const amountField = screen.getByLabelText('Amount')
  await userEvent.clear(amountField)
  await userEvent.type(amountField, amount)
  const note = screen.getByLabelText('Note (optional)')
  await userEvent.clear(note)
  if (memo) await userEvent.type(note, memo)
  await userEvent.click(screen.getByRole('button', { name: 'Continue' }))
}

async function ready() {
  await screen.findByText('Available balance ₹1,500.00')
}

describe('SendMoneyPage', () => {
  beforeEach(() => {
    signIn()
    serveWalletReads()
  })

  it('validates before verifying the recipient', async () => {
    let lookups = 0
    server.use(
      http.get('*/api/v1/recipients', () => {
        lookups++
        return HttpResponse.json(RAVI)
      }),
    )
    renderSend()

    await fillDetails({ email: 'not-an-email', amount: '12.345', memo: '' })

    expect(await screen.findByText('Enter a valid email')).toBeInTheDocument()
    expect(screen.getByText('Enter an amount like 250 or 250.50')).toBeInTheDocument()
    expect(lookups).toBe(0)
  })

  it('stops an amount above the balance before any request', async () => {
    renderSend()
    await ready()

    await fillDetails({ amount: '1500.01', memo: '' })

    expect(await screen.findByText('Your balance is ₹1,500.00')).toBeInTheDocument()
  })

  it('shows "unknown recipient" next to the email after verification fails', async () => {
    server.use(http.get('*/api/v1/recipients', () => problem(404, 'RECIPIENT_NOT_FOUND', 'No customer')))
    renderSend()
    await ready()

    await fillDetails()

    expect(await screen.findByText('No Ledgerly customer has this email.')).toBeInTheDocument()
    expect(screen.getByLabelText('Recipient email')).toHaveAttribute('aria-invalid', 'true')
  })

  it('verifies, reviews with the key, pays and shows the receipt', async () => {
    serveLookup()
    let sent: { body: unknown; key: string | null } | undefined
    server.use(
      http.post('*/api/v1/transfers', async ({ request }) => {
        sent = { body: await request.json(), key: request.headers.get('Idempotency-Key') }
        return HttpResponse.json(RECEIPT, { status: 201 })
      }),
    )
    renderSend('/send?to=ravi%40example.com')
    await ready()
    expect(screen.getByLabelText('Recipient email')).toHaveValue('ravi@example.com')

    await fillDetails()

    expect(await screen.findByLabelText('Verified recipient')).toBeInTheDocument()
    expect(screen.getByTestId('review-amount')).toHaveTextContent('₹250.50')
    expect(screen.getByText('₹1,249.50')).toBeInTheDocument() // your balance after
    expect(await screen.findByText('₹49,499.50')).toBeInTheDocument() // daily allowance left after (49,750.00 - 250.50)
    const shownKey = screen.getByTitle(/^[0-9a-f-]{36}$/).textContent ?? ''

    await userEvent.click(screen.getByRole('button', { name: 'Pay ₹250.50' }))

    expect(await screen.findByText('Sent to Ravi Kumar. Your balance is now ₹1,249.50.')).toBeInTheDocument()
    expect(sent?.body).toEqual({ recipientEmail: 'ravi@example.com', amount: '250.50', memo: 'Dinner' })
    expect(sent?.key?.startsWith(shownKey.replace('…', ''))).toBe(true)
    expect(screen.getByRole('link', { name: 'View receipt' })).toHaveAttribute('href', `/transactions/${RECEIPT.transactionId}`)
  })

  it('paying again after a lost response reuses the SAME key, so it cannot charge twice', async () => {
    serveLookup()
    const keys = servePayments((attempt) => attempt === 1)
    renderSend()
    await ready()
    await fillDetails()
    await screen.findByLabelText('Verified recipient')

    await userEvent.click(screen.getByRole('button', { name: 'Pay ₹250.50' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/can never be charged twice/)
    await userEvent.click(screen.getByRole('button', { name: 'Pay ₹250.50' }))

    expect(await screen.findByText(/Sent to Ravi Kumar/)).toBeInTheDocument()
    expect(keys).toHaveLength(2)
    expect(keys[1]).toBe(keys[0])
  })

  it('going back and paying the same thing keeps the key; changing the amount makes a new payment', async () => {
    serveLookup()
    const keys = servePayments(() => true)
    renderSend()
    await ready()
    await fillDetails()
    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹250.50' }))
    await screen.findByRole('alert')

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Continue' })) // unchanged
    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹250.50' }))
    await screen.findByRole('alert')

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    await fillDetails({ amount: '300' })
    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹300.00' }))
    await screen.findByRole('alert')

    expect(keys).toHaveLength(3)
    expect(keys[1]).toBe(keys[0])
    expect(keys[2]).not.toBe(keys[0])
  })

  it('explains a server-side rejection on the review step', async () => {
    serveLookup()
    server.use(
      http.post('*/api/v1/transfers', () =>
        problem(422, 'DAILY_LIMIT_EXCEEDED', 'Daily transfer limit of 50000.00 reached. You can still send 750.00 today.'),
      ),
    )
    renderSend()
    await ready()
    await fillDetails()

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹250.50' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('You can still send 750.00 today.')
  })

  it('explains a frozen wallet and blocks continuing', async () => {
    serveWalletReads({ wallet: wallet({ status: 'FROZEN' }) })
    renderSend()

    expect(await screen.findByText(/Your wallet is frozen/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()
  })
})
