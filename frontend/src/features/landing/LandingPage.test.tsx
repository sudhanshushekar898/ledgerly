import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderAt } from '../../test/fixtures'
import { LandingPage } from './LandingPage'

describe('LandingPage', () => {
  it('leads with the claim, a live API status and the ways in', async () => {
    renderAt(<LandingPage />, '/welcome')

    expect(screen.getByRole('heading', { level: 1, name: /proves its own balance/ })).toBeInTheDocument()
    expect(await screen.findByText(/API online · \d+ ms/)).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: /Create (a )?wallet/ })[0]).toHaveAttribute('href', '/register')
    expect(screen.getAllByRole('link', { name: 'Sign in' })[0]).toHaveAttribute('href', '/login')
    expect(screen.getByRole('link', { name: /Open the Resilience Lab/ })).toHaveAttribute('href', '/lab')
  })

  it('states only tested guarantees and shows a balanced ledger', () => {
    renderAt(<LandingPage />, '/welcome')

    const guarantees = screen.getByRole('region', { name: 'Guarantees' })
    expect(guarantees).toHaveTextContent('1 charge')
    expect(guarantees).toHaveTextContent('negative balances across 200 parallel transfers')
    const totals = within(screen.getByRole('table')).getAllByRole('row').at(-1)!
    expect(within(totals).getAllByText('₹250.50')).toHaveLength(2)
    expect(screen.getByText(/Balanced: debits = credits/)).toBeInTheDocument()
  })

  it('marks only what is not built yet as planned', () => {
    renderAt(<LandingPage />, '/welcome')

    expect(screen.queryByText('next milestone')).not.toBeInTheDocument()
    expect(screen.getByText('Apache Kafka')).toBeInTheDocument()
    expect(screen.getByText(/Next/)).toHaveTextContent('planned')
  })
})
