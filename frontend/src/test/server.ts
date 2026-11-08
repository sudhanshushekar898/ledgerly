import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'

/** A live stream that announces itself and then stays open, like the real one. */
function openStream() {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new TextEncoder().encode('event:ready\ndata:{}\n\n'))
    },
  })
  return new HttpResponse(stream, { headers: { 'Content-Type': 'text/event-stream' } })
}

/**
 * Network is mocked at the HTTP layer; tests add handlers per case and unhandled requests fail the test.
 * Always answered (resetHandlers keeps these): the readiness probe, the live stream and an empty inbox.
 */
export const server = setupServer(
  http.get('*/actuator/health/readiness', () => HttpResponse.json({ status: 'UP' })),
  http.get('*/api/v1/notifications/stream', () => openStream()),
  http.get('*/api/v1/notifications', () => HttpResponse.json({ unread: 0, items: [] })),
)
