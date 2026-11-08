import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { authStore } from '../../lib/authStore'
import { customer, loginResponse, problem, renderAt } from '../../test/fixtures'
import { server } from '../../test/server'
import { LoginPage } from './LoginPage'
import { RegisterPage } from './RegisterPage'

function renderAuth(path: '/login' | '/register') {
  return renderAt(
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/" element={<p>Home page</p>} />
    </Routes>,
    path,
  )
}

describe('LoginPage', () => {
  it('validates required fields before calling the API', async () => {
    renderAuth('/login')

    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Enter your email')).toBeInTheDocument()
    expect(screen.getByText('Enter your password')).toBeInTheDocument()
  })

  it('signs in and goes to the wallet', async () => {
    server.use(http.post('*/api/v1/auth/login', () => HttpResponse.json(loginResponse(customer()))))
    renderAuth('/login')

    await userEvent.type(screen.getByLabelText('Email'), 'asha@example.com')
    await userEvent.type(screen.getByLabelText('Password'), 'Correct-Horse-Battery-9')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Home page')).toBeInTheDocument()
    expect(authStore.getSnapshot().status).toBe('authenticated')
  })

  it('shows one generic message for wrong credentials and explains a lockout', async () => {
    server.use(http.post('*/api/v1/auth/login', () => problem(401, 'UNAUTHENTICATED', 'Invalid email or password.')))
    renderAuth('/login')

    await userEvent.type(screen.getByLabelText('Email'), 'asha@example.com')
    await userEvent.type(screen.getByLabelText('Password'), 'wrong')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password.')

    server.use(http.post('*/api/v1/auth/login', () => problem(423, 'ACCOUNT_LOCKED', 'Too many failed attempts.')))
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText(/wait a few minutes/i)).toBeInTheDocument()
  })
})

describe('RegisterPage', () => {
  async function fill(password = 'Correct-Horse-Battery-9') {
    await userEvent.type(screen.getByLabelText('Full name'), 'Asha Rao')
    await userEvent.type(screen.getByLabelText('Email'), 'asha@example.com')
    await userEvent.type(screen.getByLabelText('Password'), password)
    await userEvent.click(screen.getByRole('button', { name: 'Create wallet' }))
  }

  it('checks the password length before calling the API', async () => {
    renderAuth('/register')

    await fill('short')

    expect(await screen.findByText('Use at least 12 characters')).toBeInTheDocument()
  })

  it('creates the wallet, signs in and goes home', async () => {
    let body: unknown
    server.use(
      http.post('*/api/v1/auth/register', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(loginResponse(customer()), { status: 201 })
      }),
    )
    renderAuth('/register')

    await fill()

    expect(await screen.findByText('Home page')).toBeInTheDocument()
    expect(body).toEqual({ email: 'asha@example.com', password: 'Correct-Horse-Battery-9', fullName: 'Asha Rao' })
    expect(authStore.getSnapshot().status).toBe('authenticated')
  })

  it('puts "email taken" under the email field', async () => {
    server.use(http.post('*/api/v1/auth/register', () => problem(409, 'EMAIL_TAKEN', 'An account with this email already exists.')))
    renderAuth('/register')

    await fill()

    expect(await screen.findByText(/already exists\. Sign in instead/)).toBeInTheDocument()
  })

  it('shows the server password policy message under the password field', async () => {
    server.use(http.post('*/api/v1/auth/register', () => problem(400, 'VALIDATION_FAILED', 'Password is too common or too simple.')))
    renderAuth('/register')

    await fill('password1234')

    expect(await screen.findByText('Password is too common or too simple.')).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toHaveAttribute('aria-invalid', 'true')
  })
})
