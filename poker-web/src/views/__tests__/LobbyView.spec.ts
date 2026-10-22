import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { TableView } from '@/api/types'
import LobbyView from '../LobbyView.vue'

/** Une table du lobby, avec le statut et les joueurs voulus */
function table(
  id: number,
  name: string,
  status: TableView['status'],
  playerIds: number[],
): TableView {
  return {
    id,
    name,
    settings: { smallBlind: 10, bigBlind: 20, startingChips: 1000, maxPlayers: 6 },
    ownerId: playerIds[0]!,
    status,
    players: playerIds.map((userId) => ({ userId, username: `Joueur ${userId}` })),
  }
}

/** Je suis l'utilisateur n°1 */
const ME = { id: 1, username: 'Louis' }

async function mountLobby(tables: TableView[]) {
  // GET /api/tables renvoie la liste demandée
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async () => Response.json(tables)),
  )
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'lobby', component: LobbyView },
      { path: '/tables/:id', name: 'game', component: { template: '<p>Table</p>' } },
    ],
  })
  await router.push('/')
  await router.isReady()

  const wrapper = mount(LobbyView, { global: { plugins: [pinia, router] } })
  await flushPromises() // laisse se terminer le chargement des tables
  return wrapper
}

describe('LobbyView : regarder une partie', () => {
  beforeEach(() => {
    localStorage.setItem('poker.user', JSON.stringify(ME))
  })

  afterEach(() => {
    localStorage.removeItem('poker.user')
    vi.unstubAllGlobals()
  })

  it('propose de regarder une partie en cours où je ne suis pas assis', async () => {
    const wrapper = await mountLobby([table(5, 'Table des amis', 'PLAYING', [2, 3])])

    const link = wrapper.find('.watch-link')
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toBe('/tables/5')
    expect(link.attributes('aria-label')).toBe('Regarder la partie de la table Table des amis')
  })

  it('ne propose pas de regarder une table en attente', async () => {
    const wrapper = await mountLobby([table(5, 'En attente', 'WAITING', [2])])

    expect(wrapper.find('.watch-link').exists()).toBe(false)
  })

  it('ne propose pas de regarder ma propre table', async () => {
    const wrapper = await mountLobby([table(5, 'Ma table', 'PLAYING', [1, 2])])

    expect(wrapper.find('.watch-link').exists()).toBe(false)
  })
})
