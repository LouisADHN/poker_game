import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { disconnect } from '@/api/stomp'
import { useAuthStore } from '../auth'

vi.mock('@/api/stomp', () => ({ disconnect: vi.fn<() => Promise<void>>(async () => {}) }))

const LATER = new Date(Date.now() + 3_600_000).toISOString()

describe('useAuthStore', () => {
  beforeEach(() => {
    vi.mocked(disconnect).mockClear()
    setActivePinia(createPinia())
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('un identifiant négatif est celui d’un invité', () => {
    const auth = useAuthStore()
    expect(auth.isGuest).toBe(false) // personne de connecté

    auth.saveSession({ token: 'a', expiresAt: LATER, user: { id: 1, username: 'Louis' } })
    expect(auth.isGuest).toBe(false)

    auth.saveSession({ token: 'b', expiresAt: LATER, user: { id: -42, username: 'Mamie' } })
    expect(auth.isGuest).toBe(true)
  })

  it('remplacer une session ferme la connexion temps réel ouverte avec l’ancien jeton', () => {
    const auth = useAuthStore()

    auth.saveSession({ token: 'invite', expiresAt: LATER, user: { id: -42, username: 'Mamie' } })
    expect(disconnect).not.toHaveBeenCalled() // première session : rien à fermer

    auth.saveSession({ token: 'compte', expiresAt: LATER, user: { id: 3, username: 'Mamie59' } })
    expect(disconnect).toHaveBeenCalledOnce()
    expect(auth.token).toBe('compte')
  })
})
