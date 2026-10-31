import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { TableView } from '@/api/types'
import LocalHostView from '../LocalHostView.vue'

/** Le faux WebSocket, comme dans localHost.spec.ts */
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

const ME = { id: 1, username: 'Louis' }

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

/** Monte la page de la télé, avec un faux serveur qui répond selon la méthode et l'adresse */
async function mountHost(routes: Record<string, () => Response>) {
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const route = routes[`${init?.method ?? 'GET'} ${String(input)}`]
      if (!route) {
        throw new Error(`Requête inattendue : ${init?.method ?? 'GET'} ${String(input)}`)
      }
      return route()
    }),
  )
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/local', name: 'local-host', component: LocalHostView },
      { path: '/tables/:id', name: 'game', component: { template: '<p>Table</p>' } },
    ],
  })
  await router.push('/local')
  await router.isReady()

  const wrapper = mount(LocalHostView, { global: { plugins: [pinia, router] } })
  await flushPromises() // laisse se terminer resume()
  return { wrapper, router }
}

describe('LocalHostView', () => {
  beforeEach(() => {
    localStorage.setItem('poker.token', 'jeton')
    localStorage.setItem('poker.expiresAt', new Date(Date.now() + 3_600_000).toISOString())
    localStorage.setItem('poker.user', JSON.stringify(ME))
    stomp.handlers.clear()
  })

  afterEach(() => {
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('sans partie en attente, affiche le formulaire de création', async () => {
    const { wrapper } = await mountHost({})

    expect(wrapper.find('#table-name').exists()).toBe(true)
    expect(wrapper.find('.join-code').exists()).toBe(false)
  })

  it('en attente : le QR code, le code épelé pour les lecteurs d’écran, et les joueurs', async () => {
    localStorage.setItem('poker.localTable', JSON.stringify({ tableId: 7, joinCode: 'K7QM2X' }))
    const { wrapper } = await mountHost({
      'GET /api/tables/7': () => Response.json(localTable(['Mamie'])),
    })

    expect(wrapper.find('svg[role="img"]').exists()).toBe(true)
    const code = wrapper.find('.join-code')
    expect(code.find('[aria-hidden="true"]').text()).toBe('K7QM2X')
    expect(code.find('.visually-hidden').text()).toBe('Code : K 7 Q M 2 X')
    expect(wrapper.findAll('.player-list li').map((li) => li.text())).toEqual(['Mamie'])

    // Un seul joueur : pas encore de bouton pour lancer
    expect(wrapper.find('.start').exists()).toBe(false)
    expect(wrapper.text()).toContain('Il faut au moins 2 joueurs')
  })

  it('un 2e joueur arrive, on lance, et la télé passe sur la table', async () => {
    localStorage.setItem('poker.localTable', JSON.stringify({ tableId: 7, joinCode: 'K7QM2X' }))
    const { wrapper, router } = await mountHost({
      'GET /api/tables/7': () => Response.json(localTable(['Mamie'])),
      'POST /api/tables/7/start': () => Response.json(localTable(['Mamie', 'Alice'], 'PLAYING')),
    })

    // Alice s'assoit depuis son téléphone : le serveur diffuse la table à jour
    stomp.handlers.get('/topic/tables/7')!(localTable(['Mamie', 'Alice']))
    await flushPromises()

    await wrapper.find('.start').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('game')
    expect(router.currentRoute.value.params.id).toBe('7')
  })
})
