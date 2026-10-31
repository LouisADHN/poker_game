import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { subscribe } from '@/api/stomp'
import type { LocalTable, TableView } from '@/api/types'
import { useLocalHostStore } from '../localHost'

/**
 * Le faux WebSocket : on retient ce que chaque abonnement doit faire d'un message,
 * pour pouvoir « envoyer » des messages depuis les tests.
 * vi.hoisted : créé en même temps que vi.mock, qui est remonté en haut du fichier par Vitest.
 */
const stomp = vi.hoisted(() => ({
  handlers: new Map<string, (body: unknown) => void>(),
}))

vi.mock('@/api/stomp', () => ({
  connect: vi.fn<(token: string) => void>(),
  subscribe: vi.fn<(destination: string, handler: (body: unknown) => void) => () => void>(
    (destination, handler) => {
      stomp.handlers.set(destination, handler)
      return () => {
        stomp.handlers.delete(destination)
      }
    },
  ),
}))

/** Je suis l'hôte, l'utilisateur n°1 */
const ME = { id: 1, username: 'Louis' }

/** La table locale n°7, avec les joueurs voulus */
function localTable(players: string[], status: TableView['status'] = 'WAITING'): TableView {
  return {
    id: 7,
    name: 'Soirée poker',
    settings: { smallBlind: 10, bigBlind: 20, startingChips: 1000, maxPlayers: 6 },
    ownerId: ME.id,
    status,
    local: true,
    players: players.map((username, i) => ({ userId: -(i + 1), username })),
  }
}

/**
 * Un faux serveur : à chaque « MÉTHODE adresse », la réponse à renvoyer.
 * Une requête imprévue fait échouer le test : on sait exactement ce que le store appelle.
 */
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

const notFound = () => Response.json({ status: 404, title: 'Table introuvable' }, { status: 404 })

describe('useLocalHostStore', () => {
  beforeEach(() => {
    localStorage.setItem('poker.token', 'jeton')
    localStorage.setItem('poker.expiresAt', new Date(Date.now() + 3_600_000).toISOString())
    localStorage.setItem('poker.user', JSON.stringify(ME))
    stomp.handlers.clear()
    vi.mocked(subscribe).mockClear()
    setActivePinia(createPinia())
  })

  afterEach(() => {
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('crée la table locale, garde son code et suit ses joueurs en direct', async () => {
    const created: LocalTable = { table: localTable([]), joinCode: 'K7QM2X' }
    fakeServer({
      'POST /api/local': () => Response.json(created, { status: 201 }),
      'GET /api/tables/7': () => Response.json(localTable([])),
    })
    const local = useLocalHostStore()

    await local.create({
      name: 'Soirée poker',
      smallBlind: 10,
      bigBlind: 20,
      startingChips: 1000,
      maxPlayers: 6,
    })

    expect(local.joinCode).toBe('K7QM2X')
    expect(local.joinUrl).toBe(`${window.location.origin}/join/K7QM2X`)
    expect(JSON.parse(localStorage.getItem('poker.localTable')!)).toEqual({
      tableId: 7,
      joinCode: 'K7QM2X',
    })

    // Un joueur s'assoit : le serveur diffuse la table à jour
    stomp.handlers.get('/topic/tables/7')!(localTable(['Mamie']))
    expect(local.table?.players.map((p) => p.username)).toEqual(['Mamie'])
  })

  it("s'abonne AVANT de charger la table : aucun joueur ne peut arriver sans être vu", async () => {
    const fetchMock = fakeServer({
      'GET /api/tables/7': () => Response.json(localTable([])),
    })
    localStorage.setItem('poker.localTable', JSON.stringify({ tableId: 7, joinCode: 'K7QM2X' }))
    const local = useLocalHostStore()

    await local.resume()

    // invocationCallOrder : le rang de chaque appel parmi TOUS les appels de faux, dans l'ordre
    const subscribedAt = vi.mocked(subscribe).mock.invocationCallOrder[0]!
    const loadedAt = fetchMock.mock.invocationCallOrder[0]!
    expect(subscribedAt).toBeLessThan(loadedAt)
  })

  it('retrouve la table et son code après un rechargement de la page', async () => {
    fakeServer({ 'GET /api/tables/7': () => Response.json(localTable(['Mamie'])) })
    localStorage.setItem('poker.localTable', JSON.stringify({ tableId: 7, joinCode: 'K7QM2X' }))
    const local = useLocalHostStore()

    await local.resume()

    expect(local.joinCode).toBe('K7QM2X')
    expect(local.table?.players).toHaveLength(1)
    expect(stomp.handlers.has('/topic/tables/7')).toBe(true)
  })

  it("oublie une table qui n'existe plus (404)", async () => {
    fakeServer({ 'GET /api/tables/7': notFound })
    localStorage.setItem('poker.localTable', JSON.stringify({ tableId: 7, joinCode: 'K7QM2X' }))
    const local = useLocalHostStore()

    await local.resume()

    expect(local.table).toBeNull()
    expect(local.joinCode).toBeNull()
    expect(localStorage.getItem('poker.localTable')).toBeNull()
    expect(stomp.handlers.size).toBe(0) // plus d'abonnement inutile
  })

  it("oublie une table qui n'est pas la mienne", async () => {
    fakeServer({ 'GET /api/tables/7': () => Response.json({ ...localTable([]), ownerId: 99 }) })
    localStorage.setItem('poker.localTable', JSON.stringify({ tableId: 7, joinCode: 'K7QM2X' }))
    const local = useLocalHostStore()

    await local.resume()

    expect(local.table).toBeNull()
    expect(localStorage.getItem('poker.localTable')).toBeNull()
  })

  it('ne fait rien sans table gardée, même si le contenu gardé est corrompu', async () => {
    const fetchMock = fakeServer({})
    localStorage.setItem('poker.localTable', '{pas du json')
    const local = useLocalHostStore()

    await local.resume()

    expect(local.table).toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('on peut lancer à partir de 2 joueurs, et le lancement applique le statut PLAYING', async () => {
    fakeServer({
      'GET /api/tables/7': () => Response.json(localTable(['Mamie'])),
      'POST /api/tables/7/start': () => Response.json(localTable(['Mamie', 'Alice'], 'PLAYING')),
    })
    localStorage.setItem('poker.localTable', JSON.stringify({ tableId: 7, joinCode: 'K7QM2X' }))
    const local = useLocalHostStore()
    await local.resume()
    expect(local.canStart).toBe(false)

    stomp.handlers.get('/topic/tables/7')!(localTable(['Mamie', 'Alice']))
    expect(local.canStart).toBe(true)

    await local.start()
    expect(local.table?.status).toBe('PLAYING')
    expect(local.canStart).toBe(false)
  })
})
