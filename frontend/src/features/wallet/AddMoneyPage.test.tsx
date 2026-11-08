import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { problem, renderAt, serveWalletReads, signIn } from '../../test/fixtures'
import { server } from '../../test/server'
import { AddMoneyPage } from './AddMoneyPage'

const TOP_UP = { transactionId: '0190eeee-0000-7000-8000-000000000001', amount: '1000.00', balanceAfter: '2500.00', createdAt: '2026-11-08T10:00:00Z' }

describe('AddMoneyPage', () => {
  beforeEach(() => {
    signIn()
    serveWalletReads()
  })

  it('adds a quick amount with an Idempotency-Key and links the receipt', async () => {
    let sent: { body: unknown; key: string | null } | undefined
    server.use(
      http.post('*/api/v1/wallet/topups', async ({ request }) => {
        sent = { body: await request.json(), key: request.headers.get('Idempotency-Key') }
        return HttpResponse.json(TOP_UP, { status: 201 })
      }),
    )
    renderAt(<AddMoneyPage />)

    await userEvent.click(screen.getByRole('button', { name: '₹1,000' }))
    await userEvent.click(screen.getByRole('button', { name: 'Add money' }))

    expect(await screen.findByText(/Your balance is now/)).toHaveTextContent('Added ₹1,000.00. Your balance is now ₹2,500.00.')
    expect(sent?.body).toEqual({ amount: '1000.00' })
    expect(sent?.key).toMatch(/^[A-Za-z0-9_-]{8,64}$/)
    expect(screen.getByRole('link', { name: 'View receipt' })).toHaveAttribute('href', `/transactions/${TOP_UP.transactionId}`)
  })

  it('retrying the same amount after a network failure reuses the key', async () => {
    const keys: (string | null)[] = []
    server.use(
      http.post('*/api/v1/wallet/topups', ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        return keys.length === 1 ? HttpResponse.error() : HttpResponse.json(TOP_UP, { status: 201 })
      }),
    )
    renderAt(<AddMoneyPage />)

    await userEvent.type(screen.getByLabelText('Amount'), '1000')
    await userEvent.click(screen.getByRole('button', { name: 'Add money' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/can never be charged twice/)
    await userEvent.click(screen.getByRole('button', { name: 'Add money' }))

    expect(await screen.findByText(/Your balance is now/)).toBeInTheDocument()
    expect(keys[1]).toBe(keys[0])
  })

  it('rejects an amount above the top-up limit before calling the API', async () => {
    renderAt(<AddMoneyPage />)

    await userEvent.type(screen.getByLabelText('Amount'), '100000.01')
    await userEvent.click(screen.getByRole('button', { name: 'Add money' }))

    expect(await screen.findByText('You can add at most ₹1,00,000.00 at a time')).toBeInTheDocument()
  })

  it('explains a frozen wallet', async () => {
    server.use(http.post('*/api/v1/wallet/topups', () => problem(423, 'WALLET_FROZEN', 'frozen')))
    renderAt(<AddMoneyPage />)

    await userEvent.type(screen.getByLabelText('Amount'), '100')
    await userEvent.click(screen.getByRole('button', { name: 'Add money' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/a wallet involved is frozen/)
  })
})
