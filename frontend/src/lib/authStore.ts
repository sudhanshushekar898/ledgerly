import type { UserSummary } from '../types'

export type AuthState =
  | { status: 'loading'; accessToken: null; user: null }
  /** signedOut: the user clicked Sign out (as opposed to a session that expired or never existed). */
  | { status: 'anonymous'; accessToken: null; user: null; signedOut: boolean }
  | { status: 'authenticated'; accessToken: string; user: UserSummary }

const LOADING: AuthState = { status: 'loading', accessToken: null, user: null }
const ANONYMOUS: AuthState = { status: 'anonymous', accessToken: null, user: null, signedOut: false }
const SIGNED_OUT: AuthState = { status: 'anonymous', accessToken: null, user: null, signedOut: true }

let state: AuthState = LOADING
const listeners = new Set<() => void>()

function publish(next: AuthState) {
  state = next
  listeners.forEach((listener) => listener())
}

/**
 * The access token lives only in this module's memory: never in localStorage or cookies, so an XSS payload cannot
 * read it from storage. A page reload restores the session through the HttpOnly refresh cookie.
 */
export const authStore = {
  subscribe(listener: () => void) {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },
  getSnapshot: () => state,
  setSession(accessToken: string, user: UserSummary) {
    publish({ status: 'authenticated', accessToken, user })
  },
  clear() {
    publish(ANONYMOUS)
  },
  /** A deliberate sign-out: the next person should not be sent back to this user's last page. */
  signOut() {
    publish(SIGNED_OUT)
  },
  reset() {
    publish(LOADING)
  },
}
