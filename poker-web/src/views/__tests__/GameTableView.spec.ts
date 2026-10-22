import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import type { TableView } from '@/api/types'
import { useGameStore } from '@/stores/game'
import GameTableView from '../GameTableView.vue'

/** La table renvoyée par GET /api/tables/7 */
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

/** Faux fetch : la table pour le GET, 204 pour « Quitter la partie » */
const fetchMock = vi.fn<typeof fetch>(async (input) =>
  String(input).endsWith('/leave-game')
    ? new Response(null, { status: 204 })
    : Response.json(table),
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
    fetchMock.mockClear()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
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
