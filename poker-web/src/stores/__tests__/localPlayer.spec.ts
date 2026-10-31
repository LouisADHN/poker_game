import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { GuestJoinResponse, TableView } from '@/api/types'
import { useAuthStore } from '../auth'
import { useLocalPlayerStore } from '../localPlayer'

/** Le faux WebSocket : on retient ce que chaque abonnement fait d'un message */
const stomp = vi.hoisted(() => ({
  handlers: new Map<string, (body: unknown) => void>(),
}))

vi.mock('@/api/stomp', () => ({
  connect: vi.fn<(token: string) => void>(),
  disconnect: vi.fn<() => Promise<void>>(async () => {}),
  subscribe: vi.fn<(destination: string, handler: (body: unknown) => void) => () => void>(
    (destination, handler) => {
      stomp.handlers.set(destination, handler)
      return () => {
        stomp.handlers.delete(destination)
      }
    },
  ),
}))

const MAMIE = { id: -42, username: 'Mamie' }
const LATER = new Date(Date.now() + 3_600_000).toISOString()

/** La table locale n°7, avec les joueurs voulus (identifiant, pseudo) */
function localTable(players: { id: number; username: string }[]): TableView {
  return {
    id: 7,
    name: 'Soirée poker',
    settings: { smallBlind: 10, bigBlind: 20, startingChips: 1000, maxPlayers: 6 },
    ownerId: 1,
    status: 'WAITING',
    local: true,
    players: players.map((p) => ({ userId: p.id, username: p.username })),
  }
}

/** Un faux serveur : à chaque « MÉTHODE adresse », la réponse. Une requête imprévue fait échouer le test. */
function fakeServer(routes: Record<string, () => Response>) {
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const key = `${init?.method ?? 'GET'} ${String(input)}`
    const route = routes[key]
    if (!route) {
      throw new Error(`Requête inattendue : ${key}`)
    }
    return route()
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('useLocalPlayerStore', () => {
  beforeEach(() => {
    stomp.handlers.clear()
    setActivePinia(createPinia())
  })

  afterEach(() => {
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('rejoint en invité : SANS ancien jeton, puis devient cet invité et suit la table', async () => {
    // Un vieux jeton traîne dans le téléphone : il ne doit pas partir avec la requête
    localStorage.setItem('poker.token', 'vieux-jeton')
    localStorage.setItem('poker.expiresAt', LATER)
    setActivePinia(createPinia())
    const response: GuestJoinResponse = {
      token: 'jeton-invite',
      expiresAt: LATER,
      user: MAMIE,
      table: localTable([MAMIE]),
    }
    const fetchMock = fakeServer({
      'POST /api/local/K7QM2X/guest': () => Response.json(response),
      'GET /api/tables/7': () => Response.json(localTable([MAMIE])),
    })
    const player = useLocalPlayerStore()

    await player.joinAsGuest('K7QM2X', 'Mamie')

    const guestRequest = fetchMock.mock.calls[0]![1]!
    expect((guestRequest.headers as Record<string, string>)['Authorization']).toBeUndefined()
    expect(guestRequest.body).toBe('{"username":"Mamie"}')

    const auth = useAuthStore()
    expect(auth.token).toBe('jeton-invite')
    expect(auth.isGuest).toBe(true)
    expect(player.isSeated).toBe(true)
    expect(localStorage.getItem('poker.localSeat')).toBe('7')
    expect(stomp.handlers.has('/topic/tables/7')).toBe(true)
  })

  it('le code est encodé dans l’adresse : impossible de viser une autre route', async () => {
    // Sans encodage, « ../autre » ferait appeler /api/local/../autre/guest,
    // que le navigateur simplifie en /api/autre/guest : une tout autre route !
    const fetchMock = fakeServer({
      'POST /api/local/..%2Fautre/guest': () =>
        Response.json(
          { status: 404, detail: 'Aucune partie en attente avec ce code' },
          { status: 404 },
        ),
    })
    const player = useLocalPlayerStore()

    await expect(player.joinAsGuest('../autre', 'Mamie')).rejects.toThrow(
      'Aucune partie en attente avec ce code',
    )
    expect(String(fetchMock.mock.calls[0]![0])).toBe('/api/local/..%2Fautre/guest')
  })

  it('retrouve sa table après un rechargement, et voit arriver les autres', async () => {
    useAuthStore().saveSession({ token: 'jeton-invite', expiresAt: LATER, user: MAMIE })
    localStorage.setItem('poker.localSeat', '7')
    fakeServer({ 'GET /api/tables/7': () => Response.json(localTable([MAMIE])) })
    const player = useLocalPlayerStore()

    await player.resume()
    stomp.handlers.get('/topic/tables/7')!(localTable([MAMIE, { id: 2, username: 'Alice' }]))

    expect(player.table?.players.map((p) => p.username)).toEqual(['Mamie', 'Alice'])
  })

  it('oublie une table où je ne suis plus assis', async () => {
    useAuthStore().saveSession({ token: 'jeton-invite', expiresAt: LATER, user: MAMIE })
    localStorage.setItem('poker.localSeat', '7')
    fakeServer({
      'GET /api/tables/7': () => Response.json(localTable([{ id: 2, username: 'Alice' }])),
    })
    const player = useLocalPlayerStore()

    await player.resume()

    expect(player.table).toBeNull()
    expect(localStorage.getItem('poker.localSeat')).toBeNull()
  })

  it('un invité qui quitte la table est aussi déconnecté', async () => {
    useAuthStore().saveSession({ token: 'jeton-invite', expiresAt: LATER, user: MAMIE })
    localStorage.setItem('poker.localSeat', '7')
    fakeServer({
      'GET /api/tables/7': () => Response.json(localTable([MAMIE])),
      'POST /api/tables/7/leave': () => new Response(null, { status: 204 }),
    })
    const player = useLocalPlayerStore()
    await player.resume()

    await player.leave()

    expect(player.table).toBeNull()
    expect(useAuthStore().token).toBeNull()
  })
})
