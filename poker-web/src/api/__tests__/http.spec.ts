import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'
import { api } from '../http'

// Le vrai routeur n'est pas utile ici : on vérifie seulement vers quelle page on serait envoyé
vi.mock('@/router', () => ({ default: { push: vi.fn<(to: unknown) => Promise<void>>() } }))

// Pas de WebSocket : logout() ferme la connexion temps réel
vi.mock('@/api/stomp', () => ({ disconnect: vi.fn<() => Promise<void>>(async () => {}) }))

/** Une session enregistrée dans le localStorage, comme après une connexion */
function storeSession(user: { id: number; username: string }) {
  localStorage.setItem('poker.token', 'mon-jeton')
  localStorage.setItem('poker.expiresAt', new Date(Date.now() + 3_600_000).toISOString())
  localStorage.setItem('poker.user', JSON.stringify(user))
}

/** Les en-têtes envoyés lors du dernier appel à fetch */
function sentHeaders(fetchMock: ReturnType<typeof vi.fn<typeof fetch>>): Record<string, string> {
  const calls = fetchMock.mock.calls
  return calls[calls.length - 1]![1]!.headers as Record<string, string>
}

describe('api', () => {
  beforeEach(() => {
    vi.mocked(router.push).mockClear()
  })

  afterEach(() => {
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('envoie le jeton… sauf pour un appel anonyme', async () => {
    storeSession({ id: 1, username: 'Louis' })
    setActivePinia(createPinia())
    const fetchMock = vi.fn<typeof fetch>(async () => Response.json({}))
    vi.stubGlobal('fetch', fetchMock)

    await api('/api/tables/7')
    expect(sentHeaders(fetchMock)['Authorization']).toBe('Bearer mon-jeton')

    await api('/api/local/K7QM2X/guest', { method: 'POST', body: {}, anonymous: true })
    expect(sentHeaders(fetchMock)['Authorization']).toBeUndefined()
  })

  it('jeton refusé (401) : un compte retourne à la connexion', async () => {
    storeSession({ id: 1, username: 'Louis' })
    setActivePinia(createPinia())
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(async () => new Response(null, { status: 401 })),
    )

    await expect(api('/api/tables')).rejects.toThrow('Erreur 401')

    expect(useAuthStore().token).toBeNull()
    expect(router.push).toHaveBeenCalledWith({ name: 'login' })
  })

  it('jeton refusé (401) : un invité retourne à la page pour rejoindre', async () => {
    storeSession({ id: -42, username: 'Mamie' })
    setActivePinia(createPinia())
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(async () => new Response(null, { status: 401 })),
    )

    await expect(api('/api/tables/7')).rejects.toThrow('Erreur 401')

    expect(router.push).toHaveBeenCalledWith({ name: 'join' })
  })
})
