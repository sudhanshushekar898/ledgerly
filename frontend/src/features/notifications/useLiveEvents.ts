import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { refreshSession } from '../../lib/apiClient'
import { openLiveStream, StreamUnauthorizedError } from '../../lib/liveEvents'
import type { AppNotification } from '../../types'
import { walletKeys } from '../wallet/api'
import { notificationKeys, toastStore } from './notifications'

export type LiveStatus = 'connecting' | 'live' | 'offline'

function sleep(ms: number, signal: AbortSignal) {
  return new Promise<void>((resolve) => {
    const timer = setTimeout(resolve, ms)
    signal.addEventListener('abort', () => {
      clearTimeout(timer)
      resolve()
    })
  })
}

/**
 * Keeps one live connection open while signed in. On each notification: a toast, and fresh balance, activity and
 * inbox. Reconnects with exponential backoff (1 s, 2 s, 4 s ... capped at 30 s); an expired token is refreshed once
 * and the stream reopened.
 */
export function useLiveEvents(enabled: boolean): LiveStatus {
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<LiveStatus>('connecting')

  useEffect(() => {
    if (!enabled) return
    const controller = new AbortController()
    const { signal } = controller

    const run = async () => {
      let failures = 0
      while (!signal.aborted) {
        try {
          await openLiveStream((message) => {
            if (message.event === 'ready') {
              failures = 0
              setStatus('live')
            } else if (message.event === 'notification') {
              toastStore.show(JSON.parse(message.data) as AppNotification)
              void queryClient.invalidateQueries({ queryKey: walletKeys.all })
              void queryClient.invalidateQueries({ queryKey: notificationKeys.inbox })
            }
          }, signal)
        } catch (error) {
          if (signal.aborted) return
          if (error instanceof StreamUnauthorizedError && !(await refreshSession())) return
        }
        if (signal.aborted) return
        setStatus('offline')
        failures += 1
        await sleep(Math.min(30_000, 1000 * 2 ** (failures - 1)), signal)
      }
    }
    void run()
    return () => controller.abort()
  }, [enabled, queryClient])

  return enabled ? status : 'offline'
}
