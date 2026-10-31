import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import type { GameState, TableView } from '@/api/types'
import { useGameStore } from '@/stores/game'
import GameTableView from '../GameTableView.vue'

/** La table renvoyée par GET /api/tables/7 */
const table: TableView = {
  id: 7,
  name: 'Table de test',
  settings: { smallBlind: 10, bigBlind: 20, startingChips: 1000, maxPlayers: 2 },
  ownerId: 1,
  status: 'PLAYING',
  local: false,
  players: [
    { userId: 1, username: 'Alice' },
    { userId: 2, username: 'Bob' },
  ],
}

/** La table que renvoie le faux serveur : celle du lobby, sauf dans les tests du grand écran */
let servedTable: TableView = table

/** Faux fetch : la table pour le GET, 204 pour « Quitter la partie » */
const fetchMock = vi.fn<typeof fetch>(async (input) =>
  String(input).endsWith('/leave-game')
    ? new Response(null, { status: 204 })
    : Response.json(servedTable),
)

/**
 * Un routeur minimal, en mémoire (pas de vraie barre d'adresse dans jsdom).
 * Le lobby est remplacé par un composant vide : seul compte le fait d'y arriver.
 */
function testRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'lobby', component: { template: '<p>Lobby</p>' } },
      { path: '/tables/:id', name: 'game', component: GameTableView },
      { path: '/local', name: 'local-host', component: { template: '<p>Télé</p>' } },
      { path: '/join/:code?', name: 'join', component: { template: '<p>Rejoindre</p>' } },
    ],
  })
}

/** Ouvre la page de la table 7 et attend que la table soit chargée. */
async function mountTable() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = testRouter()
  await router.push('/tables/7')
  await router.isReady()

  const wrapper = mount(GameTableView, { global: { plugins: [pinia, router] } })
  await flushPromises() // laisse se terminer le fetch de onMounted
  return { wrapper, router, game: useGameStore() }
}

describe('GameTableView', () => {
  beforeEach(() => {
    servedTable = table
    fetchMock.mockClear()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('affiche « Absent » sur la place d’un joueur absent', async () => {
    const { wrapper, game } = await mountTable()

    game.handle({ type: 'PLAYER_PRESENCE', data: { player: 'Bob', presence: 'AWAY' } })
    await flushPromises() // laisse Vue mettre la page à jour

    const bobSeat = wrapper.findAll('.seat').find((seat) => seat.text().includes('Bob'))!
    expect(bobSeat.classes()).toContain('seat--away')
    expect(bobSeat.find('.seat-presence').text()).toBe('Absent')

    const aliceSeat = wrapper.findAll('.seat').find((seat) => seat.text().includes('Alice'))!
    expect(aliceSeat.find('.seat-presence').exists()).toBe(false)
  })

  it('quitte la partie après confirmation, puis revient au lobby', async () => {
    vi.stubGlobal('confirm', () => true) // le joueur clique sur « OK »
    const { wrapper, router } = await mountTable()

    await wrapper.find('.leave-button').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/tables/7/leave-game',
      expect.objectContaining({ method: 'POST' }),
    )
    expect(router.currentRoute.value.name).toBe('lobby')
  })

  it('un invité qui quitte la partie est déconnecté et renvoyé vers /join', async () => {
    localStorage.setItem('poker.token', 'jeton-invite')
    localStorage.setItem('poker.expiresAt', new Date(Date.now() + 3_600_000).toISOString())
    localStorage.setItem('poker.user', JSON.stringify({ id: -42, username: 'Mamie' }))
    localStorage.setItem('poker.localSeat', '7')
    vi.stubGlobal('confirm', () => true)
    const { wrapper, router } = await mountTable()

    await wrapper.find('.leave-button').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('join')
    expect(localStorage.getItem('poker.token')).toBeNull()
    expect(localStorage.getItem('poker.localSeat')).toBeNull()
  })

  it('ne fait rien si le joueur annule', async () => {
    vi.stubGlobal('confirm', () => false) // le joueur clique sur « Annuler »
    const { wrapper, router } = await mountTable()

    await wrapper.find('.leave-button').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(1) // seulement le chargement de la table
    expect(router.currentRoute.value.name).toBe('game')
  })

  it('annonce une partie abandonnée et cache le bouton « Quitter »', async () => {
    const { wrapper, game } = await mountTable()

    game.handle({
      type: 'GAME_OVER',
      data: { reason: 'ABANDONED', standings: [{ player: 'Alice', chips: 1000 }] },
    })
    await flushPromises()

    expect(wrapper.find('#game-over-title').text()).toBe('Partie abandonnée')
    expect(wrapper.text()).toContain('ne compte pas au classement')
    expect(wrapper.find('.leave-button').exists()).toBe(false)
  })

  it('garde le titre « Partie terminée » pour une partie allée à son terme', async () => {
    const { wrapper, game } = await mountTable()

    game.handle({
      type: 'GAME_OVER',
      data: { reason: 'COMPLETED', standings: [{ player: 'Alice', chips: 2000 }] },
    })
    await flushPromises()

    expect(wrapper.find('#game-over-title').text()).toBe('Partie terminée')
  })

  it('en spectateur : badge, retour au lobby, ni actions ni bouton « Quitter »', async () => {
    const { wrapper, game } = await mountTable()

    game.handle({
      type: 'GAME_STATE',
      data: {
        seq: 3,
        spectator: true,
        table: {
          handNumber: 1,
          dealer: 'Alice',
          street: 'PREFLOP',
          board: [],
          pot: 30,
          players: [
            {
              name: 'Alice',
              chips: 990,
              streetBet: 10,
              status: 'ACTIVE',
              lastAction: null,
              revealedCards: null,
              category: null,
            },
            {
              name: 'Bob',
              chips: 980,
              streetBet: 20,
              status: 'ACTIVE',
              lastAction: null,
              revealedCards: null,
              category: null,
            },
          ],
        },
        presences: { Alice: 'PRESENT', Bob: 'PRESENT' },
        holeCards: [],
        yourTurn: null,
      },
    })
    await flushPromises()

    expect(wrapper.find('.spectator-badge').text()).toBe('Mode spectateur')
    expect(wrapper.find('.back-link').attributes('href')).toBe('/')
    expect(wrapper.find('.action-bar').exists()).toBe(false)
    expect(wrapper.find('.leave-button').exists()).toBe(false)
    // Les deux joueurs sont en main : on ne voit que le dos de leurs cartes
    for (const seat of wrapper.findAll('.seat')) {
      expect(seat.findAll('.playing-card.back')).toHaveLength(2)
    }
  })
})

// ------------------------------------------------------------------
// Le grand écran : la télé du mode local

/** Le GAME_STATE qu'un spectateur reçoit en pleine main */
const spectatorState: GameState = {
  seq: 3,
  spectator: true,
  table: {
    handNumber: 1,
    dealer: 'Alice',
    street: 'PREFLOP',
    board: [],
    pot: 30,
    players: [
      {
        name: 'Alice',
        chips: 990,
        streetBet: 10,
        status: 'ACTIVE',
        lastAction: null,
        revealedCards: null,
        category: null,
      },
      {
        name: 'Bob',
        chips: 980,
        streetBet: 20,
        status: 'ACTIVE',
        lastAction: null,
        revealedCards: null,
        category: null,
      },
    ],
  },
  presences: { Alice: 'PRESENT', Bob: 'PRESENT' },
  holeCards: [],
  yourTurn: null,
}

describe('GameTableView en grand écran', () => {
  beforeEach(() => {
    servedTable = { ...table, local: true } // une table du mode local
    fetchMock.mockClear()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('la télé (spectatrice d’une table locale) passe en grand écran, sans badge ni lien', async () => {
    const { wrapper, game } = await mountTable()

    game.handle({ type: 'GAME_STATE', data: spectatorState })
    await flushPromises()

    expect(wrapper.find('main').classes()).toContain('game--big')
    expect(wrapper.find('.spectator-badge').exists()).toBe(false)
    expect(wrapper.find('.back-link').exists()).toBe(false)
    expect(wrapper.find('#log-title').text()).toBe('Dernières actions')
  })

  it('n’affiche que les 6 dernières lignes d’historique, la plus récente en premier', async () => {
    const { wrapper, game } = await mountTable()
    game.handle({ type: 'GAME_STATE', data: spectatorState })

    for (let amount = 1; amount <= 10; amount++) {
      game.handle({ type: 'POT_WON', data: { player: 'Alice', amount } })
    }
    await flushPromises()

    const lines = wrapper.findAll('.log li').map((li) => li.text())
    expect(lines).toHaveLength(6)
    expect(lines[0]).toBe('Alice remporte 10')
  })

  it('un JOUEUR d’une table locale a la manette : ni table dessinée, ni grand écran', async () => {
    const { wrapper, game } = await mountTable()

    game.handle({ type: 'GAME_STATE', data: { ...spectatorState, spectator: false } })
    await flushPromises()

    expect(wrapper.find('.controller').exists()).toBe(true)
    expect(wrapper.find('.poker-table').exists()).toBe(false)
    expect(wrapper.find('main').classes()).not.toContain('game--big')
    expect(wrapper.find('main').classes()).toContain('game--controller')
    expect(wrapper.find('.action-bar').exists()).toBe(true)
  })

  it('avant le premier GAME_STATE, une table locale affiche la table, pas la manette', async () => {
    const { wrapper } = await mountTable()

    expect(wrapper.find('.controller').exists()).toBe(false)
    expect(wrapper.find('.poker-table').exists()).toBe(true)
  })

  it('un joueur d’une table du LOBBY garde la table complète', async () => {
    servedTable = table // table du lobby
    const { wrapper, game } = await mountTable()

    game.handle({ type: 'GAME_STATE', data: { ...spectatorState, spectator: false } })
    await flushPromises()

    expect(wrapper.find('.controller').exists()).toBe(false)
    expect(wrapper.find('.poker-table').exists()).toBe(true)
    expect(wrapper.find('main').classes()).not.toContain('game--controller')
  })

  it('la fin de partie s’affiche aussi par-dessus la manette', async () => {
    const { wrapper, game } = await mountTable()
    game.handle({ type: 'GAME_STATE', data: { ...spectatorState, spectator: false } })
    game.handle({
      type: 'GAME_OVER',
      data: { reason: 'COMPLETED', standings: [{ player: 'Alice', chips: 2000 }] },
    })
    await flushPromises()

    expect(wrapper.find('#game-over-title').text()).toBe('Partie terminée')
    expect(wrapper.text()).toContain('Partie locale : elle ne compte pas au classement.')
  })

  it('un spectateur d’une table du LOBBY garde l’affichage normal', async () => {
    servedTable = table // table du lobby
    const { wrapper, game } = await mountTable()

    game.handle({ type: 'GAME_STATE', data: spectatorState })
    await flushPromises()

    expect(wrapper.find('main').classes()).not.toContain('game--big')
    expect(wrapper.find('.spectator-badge').exists()).toBe(true)
  })

  it('en fin de partie, la télé propose une nouvelle partie locale', async () => {
    const { wrapper, router, game } = await mountTable()
    game.handle({ type: 'GAME_STATE', data: spectatorState })
    game.handle({
      type: 'GAME_OVER',
      data: { reason: 'COMPLETED', standings: [{ player: 'Alice', chips: 2000 }] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Partie locale : elle ne compte pas au classement.')
    await wrapper.find('.game-over-panel button').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('local-host')
  })

  /** La partie locale se termine (je suis joueur, pas la télé) */
  async function endLocalGameAsPlayer() {
    const mounted = await mountTable()
    mounted.game.handle({ type: 'GAME_STATE', data: { ...spectatorState, spectator: false } })
    mounted.game.handle({
      type: 'GAME_OVER',
      data: { reason: 'COMPLETED', standings: [{ player: 'Alice', chips: 2000 }] },
    })
    await flushPromises()
    return mounted
  }

  it('fin de partie locale pour un invité : « Terminer », déconnecté, renvoyé vers /join', async () => {
    localStorage.setItem('poker.token', 'jeton-invite')
    localStorage.setItem('poker.expiresAt', new Date(Date.now() + 3_600_000).toISOString())
    localStorage.setItem('poker.user', JSON.stringify({ id: -42, username: 'Mamie' }))
    localStorage.setItem('poker.localSeat', '7')
    const { wrapper, router } = await endLocalGameAsPlayer()

    const button = wrapper.find('.game-over-panel button')
    expect(button.text()).toBe('Terminer')
    await button.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('join')
    expect(localStorage.getItem('poker.token')).toBeNull()
    expect(localStorage.getItem('poker.localSeat')).toBeNull()
  })

  it('fin de partie locale pour un compte : retour au lobby, toujours connecté', async () => {
    localStorage.setItem('poker.token', 'jeton-alice')
    localStorage.setItem('poker.expiresAt', new Date(Date.now() + 3_600_000).toISOString())
    localStorage.setItem('poker.user', JSON.stringify({ id: 1, username: 'Alice' }))
    localStorage.setItem('poker.localSeat', '7')
    const { wrapper, router } = await endLocalGameAsPlayer()

    const button = wrapper.find('.game-over-panel button')
    expect(button.text()).toBe('Retour au lobby')
    await button.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('lobby')
    expect(localStorage.getItem('poker.token')).toBe('jeton-alice')
    expect(localStorage.getItem('poker.localSeat')).toBeNull()
  })
})
