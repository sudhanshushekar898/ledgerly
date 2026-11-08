import { useSyncExternalStore } from 'react'

const KEY = 'ledgerly.hideBalance'

/** Storage can be unavailable (private mode, blocked site data): fall back to "visible", never crash. */
function read(): boolean {
  try {
    return localStorage.getItem(KEY) === '1'
  } catch {
    return false
  }
}

let hidden = read()
const listeners = new Set<() => void>()

/** "Hide balance" for screen sharing or a café: a per-device convenience, remembered in this browser only. */
export const balancePrivacy = {
  subscribe(listener: () => void) {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },
  getSnapshot: () => hidden,
  set(value: boolean) {
    hidden = value
    try {
      localStorage.setItem(KEY, value ? '1' : '0')
    } catch {
      // not persisted; still applies for this session
    }
    listeners.forEach((listener) => listener())
  },
  toggle() {
    balancePrivacy.set(!hidden)
  },
}

export function useBalanceHidden(): boolean {
  return useSyncExternalStore(balancePrivacy.subscribe, balancePrivacy.getSnapshot)
}
