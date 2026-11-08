import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { customer, loginResponse, problem, signIn } from '../test/fixtures'
import { server } from '../test/server'
import { api, apiCall } from './apiClient'
import { authStore } from './authStore'
import { ApiError } from './errors'
import { inspector } from './inspector'

const user = customer()

describe('apiClient', () => {
  it('sends the bearer token and parses JSON', async () => {
    signIn(user, 'token-abc')
    let authorization: string | null = null
    server.use(
      http.get('*/api/v1/wallet', ({ request }) => {
        authorization = request.headers.get('Authorization')
        return HttpResponse.json({ balance: '1.00' })
      }),
    )

    await expect(api<{ balance: string }>('/api/v1/wallet')).resolves.toEqual({ balance: '1.00' })
    expect(authorization).toBe('Bearer token-abc')
  })

  it('serialises query parameters and skips empty ones', async () => {
    signIn(user)
    let search = ''
    server.use(
      http.get('*/api/v1/wallet/statement', ({ request }) => {
        search = new URL(request.url).search
        return HttpResponse.json({ items: [] })
      }),
    )

    await api('/api/v1/wallet/statement', { query: { limit: 20, before: undefined, q: '' } })

    expect(search).toBe('?limit=20')
  })

  it('refreshes once on 401 and replays the payment with the SAME Idempotency-Key', async () => {
    signIn(user, 'expired')
    const seen: { auth: string | null; key: string | null }[] = []
    server.use(
      http.post('*/api/v1/transfers', ({ request }) => {
        seen.push({ auth: request.headers.get('Authorization'), key: request.headers.get('Idempotency-Key') })
        return request.headers.get('Authorization') === 'Bearer fresh'
          ? HttpResponse.json({ transactionId: 't-1' }, { status: 201 })
          : problem(401, 'UNAUTHENTICATED', 'Authentication required.')
      }),
      http.post('*/api/v1/auth/refresh', ({ request }) => {
        expect(request.headers.get('X-Requested-With')).toBe('XMLHttpRequest')
        return HttpResponse.json(loginResponse(user, 'fresh'))
      }),
    )

    await expect(
      api('/api/v1/transfers', { method: 'POST', body: { amount: '1.00' }, headers: { 'Idempotency-Key': 'key-0001' } }),
    ).resolves.toEqual({ transactionId: 't-1' })
    expect(seen).toEqual([
      { auth: 'Bearer expired', key: 'key-0001' },
      { auth: 'Bearer fresh', key: 'key-0001' },
    ])
    expect(authStore.getSnapshot().accessToken).toBe('fresh')
  })

  it('shares one refresh between concurrent requests (the server rotates the cookie on every use)', async () => {
    signIn(user, 'expired')
    let refreshCalls = 0
    server.use(
      http.get('*/api/v1/wallet', ({ request }) =>
        request.headers.get('Authorization') === 'Bearer fresh'
          ? HttpResponse.json({})
          : problem(401, 'UNAUTHENTICATED', 'Authentication required.'),
      ),
      http.post('*/api/v1/auth/refresh', () => {
        refreshCalls++
        return HttpResponse.json(loginResponse(user, 'fresh'))
      }),
    )

    await Promise.all([api('/api/v1/wallet'), api('/api/v1/wallet'), api('/api/v1/wallet')])

    expect(refreshCalls).toBe(1)
  })

  it('signs the user out when the refresh is rejected', async () => {
    signIn(user, 'expired')
    server.use(
      http.get('*/api/v1/wallet', () => problem(401, 'UNAUTHENTICATED', 'Authentication required.')),
      http.post('*/api/v1/auth/refresh', () => problem(401, 'UNAUTHENTICATED', 'Authentication required.')),
    )

    await expect(api('/api/v1/wallet')).rejects.toMatchObject({ status: 401 })
    expect(authStore.getSnapshot().status).toBe('anonymous')
  })

  it('does not try to refresh for unauthenticated endpoints such as login', async () => {
    let refreshCalls = 0
    server.use(
      http.post('*/api/v1/auth/login', () => problem(401, 'UNAUTHENTICATED', 'Invalid email or password.')),
      http.post('*/api/v1/auth/refresh', () => {
        refreshCalls++
        return problem(401, 'UNAUTHENTICATED', 'nope')
      }),
    )

    await expect(api('/api/v1/auth/login', { method: 'POST', body: {}, auth: false })).rejects.toBeInstanceOf(ApiError)
    expect(refreshCalls).toBe(0)
  })

  it('turns problem+json into an ApiError with code, field errors and request id', async () => {
    signIn(user)
    server.use(
      http.post('*/api/v1/transfers', () =>
        problem(400, 'VALIDATION_FAILED', 'One or more fields are invalid.', {
          errors: [{ field: 'recipientEmail', message: 'must be a well-formed email address' }],
          requestId: 'req-1',
        }),
      ),
    )

    const error = (await api('/api/v1/transfers', { method: 'POST', body: {} }).catch((e: unknown) => e)) as ApiError

    expect(error).toMatchObject({ status: 400, code: 'VALIDATION_FAILED', requestId: 'req-1' })
    expect(error.fieldErrors).toEqual([{ field: 'recipientEmail', message: 'must be a well-formed email address' }])
  })

  it('maps a non-JSON error page and an unreachable server to typed errors', async () => {
    signIn(user)
    server.use(http.get('*/api/v1/wallet', () => new HttpResponse('<html>Bad gateway</html>', { status: 502 })))
    await expect(api('/api/v1/wallet')).rejects.toMatchObject({ status: 502, code: 'UNKNOWN_ERROR' })

    server.use(http.get('*/api/v1/wallet', () => HttpResponse.error()))
    await expect(api('/api/v1/wallet')).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR' })
  })

  it('records each call for the inspector, with request id, key and replay flag but never the body', async () => {
    signIn(user)
    server.use(
      http.post('*/api/v1/transfers', () =>
        HttpResponse.json({ transactionId: 't-1' }, { status: 201, headers: { 'X-Request-Id': 'req-42', 'Idempotent-Replayed': 'true' } }),
      ),
    )

    const result = await apiCall('/api/v1/transfers', { method: 'POST', body: { amount: '1.00' }, headers: { 'Idempotency-Key': 'key-0001' } })

    expect(result).toMatchObject({ ok: true, status: 201, requestId: 'req-42', replayed: true })
    const [entry] = inspector.getSnapshot()
    expect(entry).toMatchObject({ method: 'POST', path: '/api/v1/transfers', status: 201, requestId: 'req-42', idempotencyKey: 'key-0001', replayed: true })
    expect(JSON.stringify(entry)).not.toContain('1.00')
  })

  it('masks email addresses in recorded URLs', async () => {
    signIn(user)
    server.use(http.get('*/api/v1/recipients', () => HttpResponse.json({ name: 'Ravi', email: 'ravi@example.com' })))

    await api('/api/v1/recipients', { query: { email: 'ravi@example.com' } })

    expect(inspector.getSnapshot()[0]?.path).toBe('/api/v1/recipients?email=•••')
  })

  it('returns undefined for an empty 204 response', async () => {
    server.use(http.post('*/api/v1/auth/logout', () => new HttpResponse(null, { status: 204 })))

    await expect(api('/api/v1/auth/logout', { method: 'POST', auth: false })).resolves.toBeUndefined()
  })
})
