import { describe, expect, it } from 'vitest'
import type { SseMessage } from './liveEvents'
import { createSseParser } from './liveEvents'

describe('createSseParser', () => {
  it('emits complete events, even when chunks split them anywhere', () => {
    const seen: SseMessage[] = []
    const feed = createSseParser((m) => seen.push(m))

    feed('event:ready\nda')
    feed('ta:{}\n\nevent: notification\ndata: {"amount":')
    expect(seen).toEqual([{ event: 'ready', data: '{}' }])

    feed('"250.50"}\n\n')
    expect(seen[1]).toEqual({ event: 'notification', data: '{"amount":"250.50"}' })
  })

  it('ignores keep-alive comments, joins multi-line data and defaults the event name', () => {
    const seen: SseMessage[] = []
    const feed = createSseParser((m) => seen.push(m))

    feed(':keep-alive\n\ndata:line 1\r\ndata:line 2\r\n\r\n')

    expect(seen).toEqual([{ event: 'message', data: 'line 1\nline 2' }])
  })
})
