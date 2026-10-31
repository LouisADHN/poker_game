import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { GameState, PlayerSnapshot, TableView, YourTurn } from '@/api/types'
import { useGameStore } from '@/stores/game'
import LocalController from '../LocalController.vue'

// Pas de vraie WebSocket
vi.mock('@/api/stomp', () => ({
  connect: vi.fn<(token: string) => void>(),
  subscribe: vi.fn<() => () => void>(() => () => {}),
  publish: vi.fn<(destination: string, body: unknown) => void>(),
  onEachConnection: vi.fn<(listener: () => void) => () => void>(() => () => {}),
}))

/** Une table locale, trois joueurs ; je suis Alice */
const table: TableView = {
  id: 7,
  name: 'Soirée poker',
  settings: { smallBlind: 10, bigBlind: 20, startingChips: 1000, maxPlayers: 3 },
  ownerId: 9,
  status: 'PLAYING',
  local: true,
  players: [
    { userId: 1, username: 'Alice' },
    { userId: -5, username: 'Bob' },
    { userId: -6, username: 'Chloé' },
  ],
}

/** Un joueur tel que le serveur le décrit, avec des valeurs par défaut */
function player(name: string, changes: Partial<PlayerSnapshot> = {}): PlayerSnapshot {
  return {
    name,
    chips: 1000,
    streetBet: 0,
    status: 'ACTIVE',
    lastAction: null,
    revealedCards: null,
    category: null,
    ...changes,
  }
}

/**
 * L'état reçu en pleine main : Chloé a relancé à 60, j'ai (Alice) mis 20,
 * il me reste 480 jetons. Ce n'est pas mon tour.
 */
function midHand(alice: Partial<PlayerSnapshot> = {}): GameState {
  return {
    seq: 4,
    spectator: false,
    table: {
      handNumber: 3,
      dealer: 'Bob',
      street: 'PREFLOP',
      board: [],
      pot: 90,
      players: [
        player('Alice', { chips: 480, streetBet: 20, ...alice }),
        player('Bob', { streetBet: 10 }),
        player('Chloé', { chips: 940, streetBet: 60 }),
      ],
    },
    presences: {},
    holeCards: ['As', 'Kd'],
    yourTurn: null,
  }
}

async function mountController() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<p></p>' } }],
  })
  const game = useGameStore()
  game.start(table)
  const wrapper = mount(LocalController, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, game }
}

/** La valeur affichée sous un intitulé (« Mon tapis », « Pot »…), ou null s'il est absent */
function number(wrapper: Awaited<ReturnType<typeof mountController>>['wrapper'], label: string) {
  const item = wrapper.findAll('.numbers div').find((div) => div.find('dt').text() === label)
  return item ? item.find('dd').text() : null
}

describe('LocalController', () => {
  beforeEach(() => {
    // Je suis Alice : le store d'authentification lit l'utilisateur dans le localStorage
    localStorage.setItem('poker.user', JSON.stringify({ id: 1, username: 'Alice' }))
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('affiche le nom de la table en titre principal (la page n’en a pas d’autre)', async () => {
    const { wrapper } = await mountController()

    expect(wrapper.find('h1').text()).toBe('Soirée poker')
  })

  it('avant la première main : deux emplacements vides, et rien à suivre', async () => {
    const { wrapper } = await mountController()

    expect(wrapper.findAll('.card-slot')).toHaveLength(2)
    expect(wrapper.text()).toContain('Pas de cartes pour l’instant')
    expect(number(wrapper, 'À suivre')).toBeNull()
  })

  it('en pleine main : mes cartes, mon tapis, le pot, et ce qu’il me faut pour suivre', async () => {
    const { wrapper, game } = await mountController()

    game.handle({ type: 'GAME_STATE', data: midHand() })
    await flushPromises()

    const cards = wrapper.findAll('.my-cards .playing-card').map((c) => c.attributes('aria-label'))
    expect(cards).toEqual(['As de pique', 'Roi de carreau'])
    expect(number(wrapper, 'Mon tapis')).toBe('480')
    expect(number(wrapper, 'Pot')).toBe('90')
    expect(number(wrapper, 'À suivre')).toBe('40') // 60 de Chloé, moins mes 20
  })

  it('à suivre ne dépasse jamais mon tapis : au-delà, suivre veut dire faire tapis', async () => {
    const { wrapper, game } = await mountController()

    game.handle({ type: 'GAME_STATE', data: midHand({ chips: 25 }) })
    await flushPromises()

    expect(number(wrapper, 'À suivre')).toBe('25')
  })

  it('pendant mon tour, à suivre vient du serveur', async () => {
    const { wrapper, game } = await mountController()
    const yourTurn: YourTurn = {
      street: 'PREFLOP',
      holeCards: ['As', 'Kd'],
      board: [],
      chips: 480,
      pot: 90,
      currentBet: 60,
      myStreetBet: 20,
      opponents: [],
      legalActions: {
        canCheck: false,
        toCall: 33,
        canRaise: true,
        minRaiseTo: 100,
        maxRaiseTo: 500,
      },
      timeLeftMs: 30_000,
      turnTimeoutMs: 30_000,
    }

    game.handle({ type: 'GAME_STATE', data: { ...midHand(), yourTurn } })
    await flushPromises()

    // 33 et pas 40 : pendant mon tour, le chiffre du serveur fait foi
    expect(number(wrapper, 'À suivre')).toBe('33')
  })

  it('couché : mes cartes en retrait, « Couché » écrit, et plus rien à suivre', async () => {
    const { wrapper, game } = await mountController()

    game.handle({ type: 'GAME_STATE', data: midHand({ status: 'FOLDED' }) })
    await flushPromises()

    expect(wrapper.find('.my-cards').classes()).toContain('my-cards--folded')
    expect(wrapper.find('.my-status').text()).toBe('Couché')
    expect(number(wrapper, 'À suivre')).toBeNull()
  })

  it('à tapis : « Tapis » écrit, et rien à suivre', async () => {
    const { wrapper, game } = await mountController()

    game.handle({ type: 'GAME_STATE', data: midHand({ chips: 0, status: 'ALL_IN' }) })
    await flushPromises()

    expect(wrapper.find('.my-status').text()).toBe('Tapis')
    expect(number(wrapper, 'À suivre')).toBeNull()
  })

  it('propose les actions, en grande version, et le bouton « Quitter la partie »', async () => {
    const { wrapper, game } = await mountController()

    game.handle({ type: 'GAME_STATE', data: midHand() })
    await flushPromises()

    expect(wrapper.find('.action-bar').classes()).toContain('action-bar--big')
    expect(wrapper.find('.leave-button').exists()).toBe(true)
  })
})
