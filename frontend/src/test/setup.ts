import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { palette } from '../components/CommandPalette'
import { toastStore } from '../features/notifications/notifications'
import { authStore } from '../lib/authStore'
import { inspector } from '../lib/inspector'
import { balancePrivacy } from '../lib/privacy'
import { server } from './server'

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  cleanup()
  server.resetHandlers()
  authStore.reset()
  inspector.clear()
  balancePrivacy.set(false)
  palette.close()
  toastStore.clear()
})
afterAll(() => server.close())
