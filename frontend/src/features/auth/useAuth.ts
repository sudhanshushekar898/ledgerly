import { useQueryClient } from '@tanstack/react-query'
import { useSyncExternalStore } from 'react'
import { api, refreshSession } from '../../lib/apiClient'
import { authStore } from '../../lib/authStore'
import type { LoginResponse } from '../../types'

export function useAuth() {
  const state = useSyncExternalStore(authStore.subscribe, authStore.getSnapshot)
  const queryClient = useQueryClient()

  return {
    status: state.status,
    user: state.user,
    signedOut: state.status === 'anonymous' && state.signedOut,
    async login(email: string, password: string) {
      const data = await api<LoginResponse>('/api/v1/auth/login', { method: 'POST', body: { email, password }, auth: false })
      authStore.setSession(data.accessToken, data.user)
      return data.user
    },
    /** Sign-up also signs in: the API answers with a session, and the wallet already exists. */
    async register(email: string, password: string, fullName: string) {
      const data = await api<LoginResponse>('/api/v1/auth/register', {
        method: 'POST',
        body: { email, password, fullName },
        auth: false,
      })
      authStore.setSession(data.accessToken, data.user)
      return data.user
    },
    async logout() {
      try {
        await api('/api/v1/auth/logout', { method: 'POST', auth: false, headers: { 'X-Requested-With': 'XMLHttpRequest' } })
      } catch {
        // The local session is cleared regardless; a failed call only leaves a server-side token to expire.
      } finally {
        authStore.signOut()
        queryClient.clear() // the next person must never see this user's cached balance
      }
    },
  }
}

/** Restores a session from the refresh cookie once at startup; anonymous if there is none. */
export async function bootstrapSession(): Promise<void> {
  if (!(await refreshSession())) authStore.clear()
}
