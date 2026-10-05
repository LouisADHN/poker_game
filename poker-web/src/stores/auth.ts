import { defineStore } from 'pinia'
import { ref } from 'vue'
import { disconnect } from '@/api/stomp'
import type { LoginResponse, User } from '@/api/types'
import { api, ApiError } from '@/api/http'

const TOKEN_KEY = 'poker.token'
const EXPIRES_KEY = 'poker.expiresAt'
const USER_KEY = 'poker.user'

/**
 * État de l'utilisateur connecté, partagé dans toute l'application.
 * Le jeton est conservé dans le localStorage pour rester connecté après un rechargement.
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem(TOKEN_KEY))
  const expiresAt = ref<string | null>(localStorage.getItem(EXPIRES_KEY))
  const user = ref<User | null>(readStoredUser())

  /**
   * Vrai si un jeton existe et n'a pas expiré.
   * C'est une fonction et non un computed : l'heure qui passe n'est pas "réactive",
   * un computed garderait en cache un résultat devenu faux.
   */
  function isAuthenticated(): boolean {
    return (
      token.value !== null && expiresAt.value !== null && new Date(expiresAt.value) > new Date()
    )
  }

  async function login(username: string, password: string): Promise<void> {
    const response = await api<LoginResponse>('/api/auth/login', {
      method: 'POST',
      body: { username, password },
    })
    saveSession(response)
  }

  /** Inscription, puis connexion automatique. */
  async function register(username: string, password: string): Promise<void> {
    await api<User>('/api/auth/register', {
      method: 'POST',
      body: { username, password },
    })
    await login(username, password)
  }

  function logout(): void {
    disconnect()
    token.value = null
    expiresAt.value = null
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(EXPIRES_KEY)
    localStorage.removeItem(USER_KEY)
  }

  function saveSession(response: LoginResponse): void {
    token.value = response.token
    expiresAt.value = response.expiresAt
    user.value = response.user
    localStorage.setItem(TOKEN_KEY, response.token)
    localStorage.setItem(EXPIRES_KEY, response.expiresAt)
    localStorage.setItem(USER_KEY, JSON.stringify(response.user))
  }

  /** Vérifie auprès du serveur que le compte existe toujours ; sinon, déconnecte. */
  async function verifySession(): Promise<void> {
    if (!isAuthenticated()) {
      return
    }
    try {
      user.value = await api<User>('/api/users/me')
      localStorage.setItem(USER_KEY, JSON.stringify(user.value))
    } catch (e) {
      // 404 : le compte a disparu (base réinitialisée…). Le 401 est déjà géré par http.ts.
      if (e instanceof ApiError && e.status === 404) {
        logout()
      }
    }
  }

  return { token, user, isAuthenticated, login, register, logout, verifySession }
})

function readStoredUser(): User | null {
  const stored = localStorage.getItem(USER_KEY)
  if (!stored) {
    return null
  }
  try {
    return JSON.parse(stored) as User
  } catch {
    return null // contenu corrompu : on repart de zéro
  }
}
