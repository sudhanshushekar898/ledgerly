import { authStore } from './authStore'

export interface SseMessage {
  event: string
  data: string
}

/**
 * Incremental text/event-stream parser: feed it chunks as they arrive, it emits each complete message.
 * Handles multi-line data, comments (":keep-alive") and chunks that split a message anywhere.
 */
export function createSseParser(onMessage: (message: SseMessage) => void) {
  let buffer = ''
  return (chunk: string) => {
    buffer += chunk.replace(/\r\n/g, '\n')
    let end = buffer.indexOf('\n\n')
    while (end >= 0) {
      const block = buffer.slice(0, end)
      buffer = buffer.slice(end + 2)
      let event = 'message'
      const data: string[] = []
      for (const line of block.split('\n')) {
        if (!line || line.startsWith(':')) continue
        const colon = line.indexOf(':')
        const field = colon < 0 ? line : line.slice(0, colon)
        let value = colon < 0 ? '' : line.slice(colon + 1)
        if (value.startsWith(' ')) value = value.slice(1)
        if (field === 'event') event = value
        else if (field === 'data') data.push(value)
      }
      if (data.length > 0) onMessage({ event, data: data.join('\n') })
      end = buffer.indexOf('\n\n')
    }
  }
}

export class StreamUnauthorizedError extends Error {}

/**
 * Opens the live stream with fetch, not EventSource: EventSource cannot send the Authorization header, and putting
 * the token in the URL would leak it into access logs. Resolves when the server closes the stream.
 */
export async function openLiveStream(onMessage: (message: SseMessage) => void, signal: AbortSignal): Promise<void> {
  const token = authStore.getSnapshot().accessToken
  const response = await fetch(new URL('/api/v1/notifications/stream', window.location.origin), {
    headers: { Accept: 'text/event-stream', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    credentials: 'same-origin',
    signal,
  })
  if (response.status === 401) throw new StreamUnauthorizedError()
  if (!response.ok || !response.body) throw new Error(`Live stream failed with ${response.status}`)

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  const feed = createSseParser(onMessage)
  for (;;) {
    const { value, done } = await reader.read()
    if (done) return
    feed(decoder.decode(value, { stream: true }))
  }
}
