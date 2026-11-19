import { defineStore } from 'pinia'
import { login as loginApi } from '@/api'
import type { LoginResult } from '@/api/types'
import { TOKEN_KEY, USER_KEY } from '@/api/http'

function readUser(): LoginResult | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as LoginResult
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: readUser()
  }),
  getters: {
    isLoggedIn: (state) => !!state.token,
    displayName: (state) => state.user?.displayName || state.user?.username || '',
    roles: (state) => state.user?.roles ?? [],
    isAdmin: (state) => (state.user?.roles ?? []).includes('ADMIN'),
    isGuard: (state) =>
      (state.user?.roles ?? []).some((role) => role === 'GUARD' || role === 'ADMIN')
  },
  actions: {
    async login(username: string, password: string) {
      const result = await loginApi(username, password)
      this.token = result.token
      this.user = result
      localStorage.setItem(TOKEN_KEY, result.token)
      localStorage.setItem(USER_KEY, JSON.stringify(result))
      return result
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    }
  }
})
