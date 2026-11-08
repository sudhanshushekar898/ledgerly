import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { authStore } from '../lib/authStore'
import { customer, renderAt, serveWalletReads, signIn } from '../test/fixtures'
import { server } from '../test/server'
import { AppRoutes } from './AppRoutes'

describe('AppRoutes', () => {
  it('shows visitors the landing page', async () => {
    authStore.clear()
    renderAt(<AppRoutes />, '/')

    expect(await screen.findByRole('heading', { level: 1, name: /proves its own balance/ })).toBeInTheDocument()
  })

  it('sends a signed-out deep link to sign in, not the landing page', async () => {
    authStore.clear()
    renderAt(<AppRoutes />, '/lab')

    expect(await screen.findByRole('heading', { name: 'Sign in to Ledgerly' })).toBeInTheDocument()
  })

  it('sends signed-in users from the landing page to their wallet', async () => {
    signIn()
    serveWalletReads()
    renderAt(<AppRoutes />, '/welcome')

    expect(await screen.findByTestId('balance')).toBeInTheDocument()
  })

  it('shows a customer the wallet, the engineering sections and a live API status', async () => {
    signIn()
    serveWalletReads()
    renderAt(<AppRoutes />, '/')

    expect(await screen.findByTestId('balance')).toHaveTextContent('₹1,500.00')
    const nav = screen.getByRole('navigation', { name: 'Primary' })
    expect(nav).toHaveTextContent('Send')
    expect(nav).toHaveTextContent('API inspector')
    expect(nav).toHaveTextContent('Resilience lab')
    expect(await screen.findByText(/API online · \d+ ms/)).toBeInTheDocument()
  })

  it('tells an operator that wallet screens are for customers, but leaves the inspector', async () => {
    signIn(customer({ role: 'ADMIN', name: 'Ledgerly Operator' }))
    renderAt(<AppRoutes />, '/')

    expect(await screen.findByText('Operator account')).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Primary' })
    expect(nav).not.toHaveTextContent('Send')
    expect(nav).toHaveTextContent('API inspector')
  })

  it('signed-in users visiting /login go to their wallet', async () => {
    signIn()
    serveWalletReads()
    renderAt(<AppRoutes />, '/login')

    expect(await screen.findByTestId('balance')).toBeInTheDocument()
  })

  it('signing out revokes the session and returns to the landing page', async () => {
    signIn()
    serveWalletReads()
    let logoutHeader: string | null = null
    server.use(
      http.post('*/api/v1/auth/logout', ({ request }) => {
        logoutHeader = request.headers.get('X-Requested-With')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderAt(<AppRoutes />, '/activity')
    await screen.findByRole('heading', { name: 'Activity' })

    await userEvent.click(screen.getAllByRole('button', { name: 'Sign out' })[0]!)

    expect(await screen.findByRole('heading', { level: 1, name: /proves its own balance/ })).toBeInTheDocument()
    expect(logoutHeader).toBe('XMLHttpRequest')
    expect(authStore.getSnapshot()).toMatchObject({ status: 'anonymous', signedOut: true })
  })

  it('shows not found for unknown pages', async () => {
    signIn()
    renderAt(<AppRoutes />, '/nope')

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
  })
})

describe('Command palette', () => {
  it('opens with Ctrl+K, filters, and runs a command with the keyboard', async () => {
    signIn()
    serveWalletReads()
    renderAt(<AppRoutes />, '/')
    await screen.findByTestId('balance')

    await userEvent.keyboard('{Control>}k{/Control}')
    const dialog = await screen.findByRole('dialog', { name: 'Command palette' })
    expect(dialog).toBeInTheDocument()

    await userEvent.type(screen.getByRole('combobox', { name: 'Search commands' }), 'histor')
    expect(screen.getAllByRole('option')).toHaveLength(1)
    await userEvent.keyboard('{Enter}')

    expect(await screen.findByRole('heading', { name: 'Activity' })).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('closes with Escape and can hide the balance', async () => {
    signIn()
    serveWalletReads()
    renderAt(<AppRoutes />, '/')
    await screen.findByText('₹1,500.00')

    await userEvent.keyboard('{Control>}k{/Control}')
    await userEvent.type(await screen.findByRole('combobox', { name: 'Search commands' }), 'hide')
    await userEvent.keyboard('{Enter}')
    expect(screen.getByLabelText('Balance hidden')).toBeInTheDocument()

    await userEvent.keyboard('{Control>}k{/Control}')
    await screen.findByRole('dialog')
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
