import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { Router } from 'vue-router'

const LATER = new Date(Date.now() + 3_600_000).toISOString()

/** Se connecte en tant que cet utilisateur (identifiant négatif = invité) */
function signIn(user: { id: number; username: string }) {
  localStorage.setItem('poker.token', 'jeton')
  localStorage.setItem('poker.expiresAt', LATER)
  localStorage.setItem('poker.user', JSON.stringify(user))
}

describe('les gardes du routeur', () => {
  let router: Router

  beforeEach(async () => {
    setActivePinia(createPinia())
    // Import dynamique : le routeur est un module partagé, on le recharge pour chaque test
    vi.resetModules()
    router = (await import('@/router')).default
  })

  afterEach(() => {
    localStorage.clear()
  })

  it.each(['/', '/local', '/leaderboard'])(
    'un invité qui ouvre %s est ramené à /join',
    async (path) => {
      signIn({ id: -42, username: 'Mamie' })
      setActivePinia(createPinia())

      await router.push(path)

      expect(router.currentRoute.value.name).toBe('join')
    },
  )

  it('un invité peut ouvrir sa table et la page de connexion', async () => {
    signIn({ id: -42, username: 'Mamie' })
    setActivePinia(createPinia())

    await router.push('/tables/7')
    expect(router.currentRoute.value.name).toBe('game')

    await router.push('/login')
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('un compte garde l’accès au lobby, et n’a plus besoin de la page de connexion', async () => {
    signIn({ id: 1, username: 'Louis' })
    setActivePinia(createPinia())

    await router.push('/')
    expect(router.currentRoute.value.name).toBe('lobby')

    await router.push('/login')
    expect(router.currentRoute.value.name).toBe('lobby')
  })

  it('/join est ouvert à tous, même sans être connecté', async () => {
    await router.push('/join/K7QM2X')

    expect(router.currentRoute.value.name).toBe('join')
    expect(router.currentRoute.value.params.code).toBe('K7QM2X')
  })
})
