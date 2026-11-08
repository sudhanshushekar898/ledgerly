import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { callStats, inspector, percentile } from '../../lib/inspector'
import { renderAt, signIn } from '../../test/fixtures'
import { InspectorPage } from './InspectorPage'

function seed() {
  inspector.record({ method: 'GET', path: '/api/v1/wallet', status: 200, durationMs: 12, requestId: 'req-aaaa-1111', replayed: false })
  inspector.record({
    method: 'POST',
    path: '/api/v1/transfers',
    status: 201,
    durationMs: 40,
    requestId: 'req-bbbb-2222',
    idempotencyKey: 'key-12345678',
    replayed: true,
  })
  inspector.record({ method: 'POST', path: '/api/v1/transfers', status: 422, durationMs: 30, replayed: false, code: 'INSUFFICIENT_FUNDS' })
  inspector.record({ method: 'GET', path: '/api/v1/wallet', status: 0, durationMs: 5, replayed: false, code: 'NETWORK_ERROR' })
}

describe('inspector stats', () => {
  it('uses nearest-rank percentiles', () => {
    expect(percentile([], 50)).toBe(0)
    expect(percentile([10, 20, 30, 40], 50)).toBe(20)
    expect(percentile([10, 20, 30, 40], 95)).toBe(40)
  })

  it('separates rule rejections (4xx) from failures (5xx or no response)', () => {
    seed()
    expect(callStats(inspector.getSnapshot())).toEqual({ count: 4, p50: 12, p95: 40, rejected: 1, failed: 1, replays: 1 })
  })
})

describe('InspectorPage', () => {
  it('lists every call newest first, with status, replay flag and codes', async () => {
    signIn()
    seed()
    renderAt(<InspectorPage />)

    const rows = within(screen.getByRole('table')).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(4)
    expect(rows[0]).toHaveTextContent('ERR')
    expect(rows[0]).toHaveTextContent('NETWORK_ERROR')
    expect(rows[2]).toHaveTextContent('replayed')
    expect(rows[2]).toHaveTextContent('key-1234…')
    expect(screen.getByRole('img', { name: /Latency of the last 4 calls/ })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Clear' }))
    expect(screen.getByText('No requests yet')).toBeInTheDocument()
  })
})
