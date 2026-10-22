import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { connect, publish, subscribe } from '@/api/stomp'
import { ApiError } from '@/api/http'
import type { ActionPayload, GameState, Presence, TableView, YourTurn } from '@/api/types.ts'
import { describeAction, describePresence, useGameStore } from '../game'

// Remplace la connexion temps réel : aucune WebSocket, et on peut vérifier ce qui est envoyé.
// onEachConnection fait comme si la connexion était déjà établie : il appelle tout de suite.
vi.mock('@/api/stomp', () => ({
  connect: vi.fn<(token: string) => void>(),
  subscribe: vi.fn<() => () => void>(() => () => {}),
  publish: vi.fn<(destination: string, body: unknown) => void>(),
  onEachConnection: vi.fn<(listener: () => void) => () => void>((listener) => {
    listener()
    return () => {}
  }),
}))

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
  timeLeftMs: 30_000,
  turnTimeoutMs: 30_000,
}

describe('useGameStore', () => {
  beforeEach(() => {
    // Chaque test a son propre Pinia : les stores repartent de zéro
    setActivePinia(createPinia())
    vi.unstubAllGlobals()
    vi.clearAllMocks() // oublie les appels du test précédent (publish, subscribe…)
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

  // ------------------------------------------------------------------
  // Synchronisation : GAME_STATE et numéros de séquence

  /** Un GAME_STATE : main n°3 au flop, Bob absent, c'est à personne (on peut le modifier) */
  function gameState(overrides: Partial<GameState> = {}): GameState {
    return {
      seq: 10,
      table: {
        handNumber: 3,
        dealer: 'Bob',
        street: 'FLOP',
        board: ['Ah', '7d', '2c'],
        pot: 120,
        players: [
          {
            name: 'Alice',
            chips: 940,
            streetBet: 0,
            status: 'ACTIVE',
            lastAction: null,
            revealedCards: null,
            category: null,
          },
          {
            name: 'Bob',
            chips: 940,
            streetBet: 0,
            status: 'ACTIVE',
            lastAction: { type: 'RAISE', total: 60 },
            revealedCards: null,
            category: null,
          },
        ],
      },
      presences: { Alice: 'PRESENT', Bob: 'AWAY' },
      holeCards: ['Qs', 'Qh'],
      yourTurn: null,
      ...overrides,
    }
  }

  /** Un message public BLIND_POSTED de Bob, avec son numéro de séquence */
  function blindWithSeq(seq: number, amount: number): void {
    const game = useGameStore()
    game.receiveTableMessage(
      { type: 'BLIND_POSTED', data: { player: 'Bob', amount } },
      { seq: String(seq) },
    )
  }

  it("start s'abonne puis demande l'état complet de la partie", () => {
    localStorage.setItem('poker.token', 'jeton') // un utilisateur connecté
    startedStore()

    expect(connect).toHaveBeenCalledWith('jeton')
    expect(subscribe).toHaveBeenCalledTimes(2)
    expect(publish).toHaveBeenCalledWith('/app/tables/7/sync', {})
    localStorage.removeItem('poker.token')
  })

  it('GAME_STATE remplace tout l’état de la partie', () => {
    const game = startedStore()

    game.handle({ type: 'GAME_STATE', data: gameState() })

    expect(game.handNumber).toBe(3)
    expect(game.dealer).toBe('Bob')
    expect(game.board).toEqual(['Ah', '7d', '2c'])
    expect(game.pot).toBe(120)
    expect(game.myCards).toEqual(['Qs', 'Qh'])
    const bob = game.players.find((p) => p.name === 'Bob')!
    expect(bob.chips).toBe(940)
    expect(bob.lastAction).toBe('relance à 60')
    expect(bob.presence).toBe('AWAY')
    expect(game.turn).toBeNull()
  })

  it('GAME_STATE avec mon tour : compte à rebours à partir du temps qui reste vraiment', () => {
    vi.useFakeTimers()
    vi.setSystemTime(1_000_000) // Date.now() vaudra exactement 1 000 000
    const game = startedStore()

    game.handle({
      type: 'GAME_STATE',
      data: gameState({ yourTurn: { ...yourTurn, timeLeftMs: 12_000, turnTimeoutMs: 20_000 } }),
    })

    expect(game.turn).not.toBeNull()
    expect(game.turnDeadline).toBe(1_012_000)
    expect(game.turnTimeoutMs).toBe(20_000)
    vi.useRealTimers()
  })

  it('avant le premier GAME_STATE, les messages publics sont ignorés', () => {
    const game = startedStore()

    blindWithSeq(1, 20)

    expect(game.pot).toBe(0)
  })

  it('après GAME_STATE : un numéro déjà inclus est ignoré, le suivant est appliqué', () => {
    const game = startedStore()
    game.handle({ type: 'GAME_STATE', data: gameState({ seq: 10 }) })

    blindWithSeq(10, 20) // déjà dans l'état : ignoré
    expect(game.pot).toBe(120)

    blindWithSeq(11, 20) // le suivant : appliqué
    expect(game.pot).toBe(140)
  })

  it('un numéro qui saute fait redemander l’état, et rien n’est appliqué en attendant', () => {
    const game = startedStore()
    game.handle({ type: 'GAME_STATE', data: gameState({ seq: 10 }) })
    vi.mocked(publish).mockClear()

    blindWithSeq(12, 20) // le n°11 a été perdu
    expect(publish).toHaveBeenCalledWith('/app/tables/7/sync', {})
    expect(game.pot).toBe(120)

    blindWithSeq(13, 20) // en attente du GAME_STATE : ignoré
    expect(game.pot).toBe(120)

    // Le nouvel état arrive : on repart de lui
    game.handle({
      type: 'GAME_STATE',
      data: gameState({ seq: 13, table: { ...gameState().table, pot: 200 } }),
    })
    blindWithSeq(14, 20)
    expect(game.pot).toBe(220)
  })

  it('YOUR_TURN : compte à rebours à partir du temps envoyé par le serveur', () => {
    vi.useFakeTimers()
    vi.setSystemTime(1_000_000)
    const game = startedStore()

    game.handle({
      type: 'YOUR_TURN',
      data: { ...yourTurn, timeLeftMs: 25_000, turnTimeoutMs: 25_000 },
    })

    expect(game.turnDeadline).toBe(1_025_000)
    expect(game.turnTimeoutMs).toBe(25_000)
    vi.useRealTimers()
  })
})
