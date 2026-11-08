import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { AppRoutes } from '../../app/AppRoutes'
import { renderAt, serveWalletReads, signIn } from '../../test/fixtures'
import { server } from '../../test/server'
import type { AppNotification } from '../../types'
import { describeNotification } from './notifications'

const RECEIVED: AppNotification = {
  id: 'n-1',
  kind: 'MONEY_RECEIVED',
  amount: '250.50',
  counterpartyName: 'Asha Rao',
  memo: 'Dinner',
  transactionId: 't-1',
  createdAt: '2026-11-08T10:00:00Z',
  read: false,
}

/** A stream that says "ready", then delivers one notification. */
function streamWith(notification: AppNotification) {
  const encoder = new TextEncoder()
  return new HttpResponse(
    new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('event:ready\ndata:{}\n\n'))
        setTimeout(() => controller.enqueue(encoder.encode(`event:notification\ndata:${JSON.stringify(notification)}\n\n`)), 50)
      },
    }),
    { headers: { 'Content-Type': 'text/event-stream' } },
  )
}

describe('live events', () => {
  it('connects with the bearer token, shows "live" and toasts a payment as it arrives', async () => {
    signIn()
    serveWalletReads()
    let authorization: string | null = null
    server.use(
      http.get('*/api/v1/notifications/stream', ({ request }) => {
        authorization = request.headers.get('Authorization')
        return streamWith(RECEIVED)
      }),
    )
    renderAt(<AppRoutes />, '/')

    expect(await screen.findByText('Events · live')).toBeInTheDocument()
    expect(await screen.findByText('₹250.50 received')).toBeInTheDocument()
    expect(screen.getByText('from Asha Rao · “Dinner”')).toBeInTheDocument()
    expect(authorization).toBe('Bearer access-1')
  })

  it('the bell shows the unread count and marks everything read', async () => {
    signIn()
    serveWalletReads()
    let unread = 1
    server.use(
      http.get('*/api/v1/notifications', () => HttpResponse.json({ unread, items: [{ ...RECEIVED, read: unread === 0 }] })),
      http.post('*/api/v1/notifications/read-all', () => {
        unread = 0
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderAt(<AppRoutes />, '/')

    // Desktop sidebar and phone header each have a bell; jsdom applies no CSS, so both exist.
    await userEvent.click((await screen.findAllByRole('button', { name: 'Notifications, 1 unread' }))[0]!)
    expect(screen.getByRole('dialog', { name: 'Notifications' })).toHaveTextContent('₹250.50 received')
    await userEvent.click(screen.getByRole('button', { name: 'Mark all read' }))

    expect((await screen.findAllByRole('button', { name: 'Notifications' })).length).toBeGreaterThan(0)
  })

  it('describes every kind of notification', () => {
    expect(describeNotification({ ...RECEIVED, kind: 'MONEY_ADDED' }).title).toBe('₹250.50 added')
    expect(describeNotification({ ...RECEIVED, kind: 'PAYMENT_RETURNED' }).body).toBe('your payment to Asha Rao was reversed')
    expect(describeNotification({ ...RECEIVED, kind: 'PAYMENT_REVERSED', counterpartyName: undefined }).body).toBe('a top-up was reversed')
  })
})
