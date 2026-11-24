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
    permissions: (state) => state.user?.permissions ?? [],
    isAdmin: (state) => (state.user?.roles ?? []).includes('ADMIN'),
    isGuard: (state) =>
      (state.user?.roles ?? []).some((role) => role === 'GUARD' || role === 'ADMIN'),
    /**
     * C-73 权限判定：ADMIN 全量；其余看 permissions（支持 module:* 通配）。
     * 用法：auth.hasPerm('appointment:read')
     */
    hasPerm: (state) => (perm: string) => {
      const roles = state.user?.roles ?? []
      if (roles.includes('ADMIN')) return true
      const perms = state.user?.permissions ?? []
      if (perms.includes('*') || perms.includes(perm)) return true
      const module = perm.includes(':') ? perm.slice(0, perm.indexOf(':')) : perm
      return perms.includes(`${module}:*`)
    }
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
