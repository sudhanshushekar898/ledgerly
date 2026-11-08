import type { LoginResponse } from '../types'
import { authStore } from './authStore'
import { ApiError, networkError, toApiError } from './errors'
import { inspector } from './inspector'

export interface RequestOptions {
  method?: 'GET' | 'POST'
  body?: unknown
  query?: Record<string, string | number | undefined | null>
  /** false for endpoints that must not carry a token or trigger a refresh (login, register). */
  auth?: boolean
  /** Extra headers, for example Idempotency-Key, or X-Requested-With on cookie-authenticated endpoints. */
  headers?: Record<string, string>
  signal?: AbortSignal
}

/** Outcome of one logical API call, with the transport facts the inspector and the Resilience Lab show. */
export interface CallResult<T> {
  ok: boolean
  status: number
  data?: T
  error?: ApiError
  requestId?: string
  /** The server answered from its idempotency store instead of executing the request again. */
  replayed: boolean
  durationMs: number
}

const IDEMPOTENCY_HEADER = 'Idempotency-Key'

// Absolute URLs keep fetch working outside a browser page (tests) and are same-origin in the browser.
function buildUrl(path: string, query?: RequestOptions['query']): string {
  const url = new URL(path, window.location.origin)
  Object.entries(query ?? {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') url.searchParams.set(key, String(value))
  })
  return url.toString()
}

/** The path as the inspector shows it: values of parameters that could identify a person are masked. */
function displayPath(path: string, query?: RequestOptions['query']): string {
  const params = Object.entries(query ?? {})
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => `${key}=${/email/i.test(key) ? '•••' : String(value)}`)
  return params.length ? `${path}?${params.join('&')}` : path
}

function record(method: string, path: string, startedAt: number, response: Response | null, extra: { key?: string; code?: string }) {
  inspector.record({
    method,
    path,
    status: response?.status ?? 0,
    durationMs: Math.round(performance.now() - startedAt),
    requestId: response?.headers.get('X-Request-Id') ?? undefined,
    idempotencyKey: extra.key,
    replayed: response?.headers.get('Idempotent-Replayed') === 'true',
    code: extra.code,
  })
}

let refreshInFlight: Promise<boolean> | null = null

/**
 * Exchanges the HttpOnly refresh cookie for a new access token. Concurrent callers share one request: the server
 * rotates the cookie on every use and treats a reused token as theft, so two parallel refreshes would sign the
 * user out.
 */
export function refreshSession(): Promise<boolean> {
  refreshInFlight ??= doRefresh().finally(() => {
    refreshInFlight = null
  })
  return refreshInFlight
}

async function doRefresh(): Promise<boolean> {
  const path = '/api/v1/auth/refresh'
  const startedAt = performance.now()
  try {
    const response = await fetch(buildUrl(path), {
      method: 'POST',
      headers: { 'X-Requested-With': 'XMLHttpRequest' },
      credentials: 'same-origin',
    })
    record('POST', path, startedAt, response, { code: response.ok ? undefined : 'UNAUTHENTICATED' })
    if (!response.ok) {
      authStore.clear()
      return false
    }
    const data = (await response.json()) as LoginResponse
    authStore.setSession(data.accessToken, data.user)
    return true
  } catch {
    // Server unreachable: keep the current state; the caller sees the failed request.
    record('POST', path, startedAt, null, { code: 'NETWORK_ERROR' })
    return false
  }
}

type Attempt = { response: Response; startedAt: number } | { response: null; startedAt: number }

async function attempt(path: string, options: RequestOptions, token: string | null): Promise<Attempt> {
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`
  const startedAt = performance.now()
  try {
    const response = await fetch(buildUrl(path, options.query), {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      credentials: 'same-origin',
      signal: options.signal,
    })
    return { response, startedAt }
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    return { response: null, startedAt }
  }
}

/**
 * Every API call goes through here, and never throws for HTTP or network failures (only for an abort). On a 401
 * it refreshes once and replays the request with the SAME options, so a payment's Idempotency-Key is reused and
 * the replay can never move money twice.
 */
export async function apiCall<T>(path: string, options: RequestOptions = {}): Promise<CallResult<T>> {
  const useAuth = options.auth !== false
  const method = options.method ?? 'GET'
  const shown = displayPath(path, options.query)
  const key = options.headers?.[IDEMPOTENCY_HEADER]

  let current = await attempt(path, options, useAuth ? authStore.getSnapshot().accessToken : null)
  if (current.response?.status === 401 && useAuth && (await refreshSession())) {
    // Access tokens live 15 minutes: refresh once, then replay the request once.
    record(method, shown, current.startedAt, current.response, { key, code: 'UNAUTHENTICATED' })
    current = await attempt(path, options, authStore.getSnapshot().accessToken)
  }

  const durationMs = Math.round(performance.now() - current.startedAt)
  if (!current.response) {
    record(method, shown, current.startedAt, null, { key, code: 'NETWORK_ERROR' })
    return { ok: false, status: 0, error: networkError(), replayed: false, durationMs }
  }

  const { response } = current
  const requestId = response.headers.get('X-Request-Id') ?? undefined
  const replayed = response.headers.get('Idempotent-Replayed') === 'true'
  if (!response.ok) {
    const error = await toApiError(response)
    record(method, shown, current.startedAt, response, { key, code: error.code })
    return { ok: false, status: response.status, error, requestId, replayed, durationMs }
  }
  record(method, shown, current.startedAt, response, { key })
  // 204 (logout) has nothing to parse.
  const text = await response.text()
  return { ok: true, status: response.status, data: (text ? JSON.parse(text) : undefined) as T, requestId, replayed, durationMs }
}

/** The usual way to call the API: resolves with the body, rejects with an ApiError. */
export async function api<T = void>(path: string, options: RequestOptions = {}): Promise<T> {
  const result = await apiCall<T>(path, options)
  if (!result.ok) throw result.error
  return result.data as T
}

export function isApiError(error: unknown, code?: string): error is ApiError {
  return error instanceof ApiError && (code === undefined || error.code === code)
}
