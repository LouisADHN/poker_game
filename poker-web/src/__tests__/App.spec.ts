import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { TableView } from '@/api/types'
import { useGameStore } from '@/stores/game'
import App from '../App.vue'

// Pas de WebSocket dans ce test : game.start() se connecterait, puisqu'un jeton est présent
vi.mock('@/api/stomp', () => ({
  connect: vi.fn<(token: string) => void>(),
  disconnect: vi.fn<() => Promise<void>>(async () => {}),
  subscribe: vi.fn<() => () => void>(() => () => {}),
  publish: vi.fn<(destination: string, body: unknown) => void>(),
  onEachConnection: vi.fn<() => () => void>(() => () => {}),
}))

const ME = { id: 1, username: 'Louis' }

const localTable: TableView = {
  id: 7,
  name: 'Soirée poker',
  settings: { smallBlind: 10, bigBlind: 20, startingChips: 1000, maxPlayers: 6 },
  ownerId: ME.id,
  status: 'PLAYING',
  local: true,
  players: [
    { userId: -1, username: 'Mamie' },
    { userId: 2, username: 'Alice' },
  ],
}

/** Monte l'application entière sur la page de la table 7 */
async function mountApp() {
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async () => Response.json(ME)), // GET /api/users/me (verifySession)
  )
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'lobby', component: { template: '<p>Lobby</p>' } },
      { path: '/local', name: 'local-host', component: { template: '<p>Télé</p>' } },
      { path: '/leaderboard', name: 'leaderboard', component: { template: '<p>Classement</p>' } },
      { path: '/tables/:id', name: 'game', component: { template: '<p>Table</p>' } },
    ],
  })
  await router.push('/tables/7')
  await router.isReady()

  const wrapper = mount(App, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, router, game: useGameStore() }
}

/** Le serveur annonce : « tu regardes cette partie sans y jouer » */
function becomeSpectator() {
  const game = useGameStore()
  game.start(localTable)
  game.handle({
    type: 'GAME_STATE',
    data: {
      seq: 0,
      spectator: true,
      table: { handNumber: 0, dealer: null, street: null, board: [], pot: 0, players: [] },
      presences: {},
      holeCards: [],
      yourTurn: null,
    },
  })
}

describe('App : la barre de navigation', () => {
  beforeEach(() => {
    localStorage.setItem('poker.token', 'jeton')
    localStorage.setItem('poker.expiresAt', new Date(Date.now() + 3_600_000).toISOString())
    localStorage.setItem('poker.user', JSON.stringify(ME))
  })

  afterEach(() => {
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('est affichée sur la table quand on y joue', async () => {
    const { wrapper } = await mountApp()

    expect(wrapper.find('.app-bar').exists()).toBe(true)
  })

  it('disparaît sur la télé pendant une partie locale', async () => {
    const { wrapper } = await mountApp()

    becomeSpectator()
    await flushPromises()

    expect(wrapper.find('.app-bar').exists()).toBe(false)
  })

  it('revient dès que la télé quitte la table', async () => {
    const { wrapper, router } = await mountApp()
    becomeSpectator()

    await router.push('/local')
    await flushPromises()

    expect(wrapper.find('.app-bar').exists()).toBe(true)
  })
})
