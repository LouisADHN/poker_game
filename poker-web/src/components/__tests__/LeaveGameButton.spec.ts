import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { TableView } from '@/api/types'
import { useGameStore } from '@/stores/game'
import LeaveGameButton from '../LeaveGameButton.vue'

// Pas de vraie WebSocket : game.start s'abonnerait sinon
vi.mock('@/api/stomp', () => ({
  connect: vi.fn<(token: string) => void>(),
  subscribe: vi.fn<() => () => void>(() => () => {}),
  publish: vi.fn<(destination: string, body: unknown) => void>(),
  onEachConnection: vi.fn<(listener: () => void) => () => void>(() => () => {}),
}))

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

/** Monte le bouton ; started = faux pour simuler une table pas encore chargée. */
async function mountButton(started = true) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'lobby', component: { template: '<p>Lobby</p>' } },
      { path: '/tables/:id', name: 'game', component: { template: '<p>Table</p>' } },
      { path: '/join/:code?', name: 'join', component: { template: '<p>Rejoindre</p>' } },
    ],
  })
  await router.push('/tables/7')
  await router.isReady()

  const game = useGameStore()
  if (started) {
    game.start(table)
  }
  const wrapper = mount(LeaveGameButton, { global: { plugins: [pinia, router] } })
  return { wrapper, router, game }
}

describe('LeaveGameButton', () => {
  beforeEach(() => {
    vi.stubGlobal('confirm', () => true) // le joueur clique sur « OK »
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('ne s’affiche pas tant que la table n’est pas chargée', async () => {
    const { wrapper } = await mountButton(false)

    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('refus du serveur : le message est affiché et on reste sur la table', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(async () =>
        Response.json({ detail: 'La partie est terminée' }, { status: 409 }),
      ),
    )
    const { wrapper, router, game } = await mountButton()

    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(game.error).toBe('La partie est terminée')
    expect(router.currentRoute.value.name).toBe('game')
    // Le bouton redevient cliquable : le joueur peut réessayer
    expect(wrapper.find('button').attributes('disabled')).toBeUndefined()
  })

  it('pendant l’envoi, le bouton est désactivé : pas de double demande', async () => {
    // Une réponse qui n'arrive que quand le test le décide
    let answer: (response: Response) => void = () => {}
    const fetchMock = vi.fn<typeof fetch>(
      () =>
        new Promise((resolve) => {
          answer = resolve
        }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const { wrapper, router } = await mountButton()

    await wrapper.find('button').trigger('click')

    expect(wrapper.find('button').text()).toBe('Départ…')
    expect(wrapper.find('button').attributes('disabled')).toBeDefined()

    answer(new Response(null, { status: 204 }))
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.name).toBe('lobby')
  })
})
