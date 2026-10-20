import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/http'
import type { ActionPayload, Presence, TableView, YourTurn } from '@/api/types.ts'
import { describeAction, describePresence, useGameStore } from '../game'

describe('describeAction', () => {
  // it.each : le même test, exécuté pour chaque ligne du tableau
  it.each<[ActionPayload, string]>([
    [{ type: 'FOLD' }, 'se couche'],
    [{ type: 'CHECK' }, 'checke'],
    [{ type: 'CALL' }, 'suit'],
    [{ type: 'BET', total: 50 }, 'mise à 50'],
    [{ type: 'RAISE', total: 120 }, 'relance à 120'],
    [{ type: 'ALL_IN' }, 'fait tapis'],
  ])('%o -> "%s"', (action, expected) => {
    expect(describeAction(action)).toBe(expected)
  })
})

describe('describePresence', () => {
  it.each<[Presence, string | null]>([
    ['PRESENT', null],
    ['DISCONNECTED', 'Déconnecté…'],
    ['AWAY', 'Absent'],
    ['LEFT', 'A quitté la partie'],
  ])('%s -> %o', (presence, expected) => {
    expect(describePresence(presence)).toBe(expected)
  })
})

// ------------------------------------------------------------------
// Le store de la partie

/** Une table de 2 joueurs, telle que le serveur la renvoie. */
const table: TableView = {
  id: 7,
  name: 'Table de test',
  settings: { smallBlind: 10, bigBlind: 20, startingChips: 1000, maxPlayers: 2 },
  ownerId: 1,
  status: 'PLAYING',
  players: [
    { userId: 1, username: 'Alice' },
    { userId: 2, username: 'Bob' },
  ],
}

/** Un message YOUR_TURN : c'est à moi (Alice) de jouer, je peux checker. */
const yourTurn: YourTurn = {
  street: 'PREFLOP',
  holeCards: ['As', 'Kd'],
  board: [],
  chips: 980,
  pot: 40,
  currentBet: 20,
  myStreetBet: 20,
  opponents: [{ name: 'Bob', chips: 980, status: 'ACTIVE', streetBet: 20 }],
  legalActions: { canCheck: true, toCall: 0, canRaise: true, minRaiseTo: 40, maxRaiseTo: 1000 },
}

describe('useGameStore', () => {
  beforeEach(() => {
    // Chaque test a son propre Pinia : les stores repartent de zéro
    setActivePinia(createPinia())
    vi.unstubAllGlobals()
  })

  /** Le store, préparé pour la table de test (sans connexion : aucun jeton dans ce test). */
  function startedStore() {
    const game = useGameStore()
    game.start(table)
    return game
  }

  it('commence avec tous les joueurs présents', () => {
    const game = startedStore()
    expect(game.players.map((p) => p.presence)).toEqual(['PRESENT', 'PRESENT'])
  })

  it("PLAYER_PRESENCE met à jour la présence du bon joueur et l'historique", () => {
    const game = startedStore()

    game.handle({ type: 'PLAYER_PRESENCE', data: { player: 'Bob', presence: 'AWAY' } })

    expect(game.players.find((p) => p.name === 'Bob')?.presence).toBe('AWAY')
    expect(game.players.find((p) => p.name === 'Alice')?.presence).toBe('PRESENT')
    expect(game.log[0]).toBe('Bob est absent : il joue en automatique')
  })

  it('un retour remet le joueur présent', () => {
    const game = startedStore()
    game.handle({ type: 'PLAYER_PRESENCE', data: { player: 'Bob', presence: 'DISCONNECTED' } })

    game.handle({ type: 'PLAYER_PRESENCE', data: { player: 'Bob', presence: 'PRESENT' } })

    expect(game.players.find((p) => p.name === 'Bob')?.presence).toBe('PRESENT')
    expect(game.log[0]).toBe('Bob est de retour')
  })

  it('une action jouée pour moi par le serveur termine mon tour', () => {
    // Je suis Alice : le store d'authentification lit l'utilisateur dans le localStorage
    localStorage.setItem('poker.user', JSON.stringify({ id: 1, username: 'Alice' }))
    const game = startedStore()
    game.handle({ type: 'YOUR_TURN', data: yourTurn })
    expect(game.turn).not.toBeNull()

    // Le délai est écoulé : le serveur checke pour moi
    game.handle({
      type: 'PLAYER_ACTED',
      data: { player: 'Alice', action: { type: 'CHECK' }, streetBet: 20, chipsLeft: 980 },
    })

    expect(game.turn).toBeNull()
    expect(game.turnDeadline).toBeNull()
    localStorage.removeItem('poker.user')
  })

  it("l'action d'un adversaire ne termine pas mon tour", () => {
    localStorage.setItem('poker.user', JSON.stringify({ id: 1, username: 'Alice' }))
    const game = startedStore()
    game.handle({ type: 'YOUR_TURN', data: yourTurn })

    game.handle({
      type: 'PLAYER_ACTED',
      data: { player: 'Bob', action: { type: 'CALL' }, streetBet: 20, chipsLeft: 980 },
    })

    expect(game.turn).not.toBeNull()
    localStorage.removeItem('poker.user')
  })

  it('GAME_OVER retient le motif de fin', () => {
    const game = startedStore()

    game.handle({
      type: 'GAME_OVER',
      data: { reason: 'ABANDONED', standings: [{ player: 'Alice', chips: 1000 }] },
    })

    expect(game.endReason).toBe('ABANDONED')
    expect(game.standings).toEqual([{ player: 'Alice', chips: 1000 }])
    expect(game.log[0]).toBe('Partie abandonnée')
  })

  it('leaveGame appelle POST /api/tables/{id}/leave-game', async () => {
    // Faux fetch : le serveur répond 204, sans corps
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)
    const game = startedStore()

    await game.leaveGame()

    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, options] = fetchMock.mock.calls[0]!
    expect(url).toBe('/api/tables/7/leave-game')
    expect(options?.method).toBe('POST')
  })

  it('leaveGame transmet le refus du serveur', async () => {
    const problem = { status: 409, detail: 'Aucune partie en cours à cette table' }
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(Response.json(problem, { status: 409 })),
    )
    const game = startedStore()

    await expect(game.leaveGame()).rejects.toBeInstanceOf(ApiError)
  })
})
