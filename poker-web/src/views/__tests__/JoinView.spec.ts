import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { GuestJoinResponse, TableView } from '@/api/types'
import JoinView from '../JoinView.vue'

const stomp = vi.hoisted(() => ({
  handlers: new Map<string, (body: unknown) => void>(),
}))

vi.mock('@/api/stomp', () => ({
  connect: vi.fn<(token: string) => void>(),
  disconnect: vi.fn<() => Promise<void>>(async () => {}),
  subscribe: vi.fn<(destination: string, handler: (body: unknown) => void) => () => void>(
    (destination, handler) => {
      stomp.handlers.set(destination, handler)
      return () => {
        stomp.handlers.delete(destination)
      }
    },
  ),
}))

const MAMIE = { id: -42, username: 'Mamie' }
const LATER = new Date(Date.now() + 3_600_000).toISOString()

function localTable(
  players: { id: number; username: string }[],
  status: TableView['status'] = 'WAITING',
): TableView {
  return {
    id: 7,
    name: 'Soirée poker',
    settings: { smallBlind: 10, bigBlind: 20, startingChips: 1000, maxPlayers: 6 },
    ownerId: 1,
    status,
    local: true,
    players: players.map((p) => ({ userId: p.id, username: p.username })),
  }
}

/** Ouvre la page à l'adresse donnée, avec un faux serveur */
async function mountJoin(path: string, routes: Record<string, () => Response> = {}) {
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const key = `${init?.method ?? 'GET'} ${String(input)}`
      const route = routes[key]
      if (!route) {
        throw new Error(`Requête inattendue : ${key}`)
      }
      return route()
    }),
  )
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/join/:code?', name: 'join', component: JoinView },
      { path: '/login', name: 'login', component: { template: '<p>Connexion</p>' } },
      { path: '/tables/:id', name: 'game', component: { template: '<p>Table</p>' } },
    ],
  })
  await router.push(path)
  await router.isReady()

  const wrapper = mount(JoinView, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('JoinView', () => {
  beforeEach(() => {
    stomp.handlers.clear()
  })

  afterEach(() => {
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('le code du QR code est prérempli, et on propose un pseudo et la connexion', async () => {
    const { wrapper } = await mountJoin('/join/k7qm2x')

    expect((wrapper.find('#join-code').element as HTMLInputElement).value).toBe('K7QM2X')
    expect(wrapper.find('#guest-name').exists()).toBe(true)
    expect(wrapper.find('a').attributes('href')).toBe('/login?redirect=/join/K7QM2X')
  })

  it('un invité rejoint, puis attend dans la salle d’attente', async () => {
    const response: GuestJoinResponse = {
      token: 'jeton-invite',
      expiresAt: LATER,
      user: MAMIE,
      table: localTable([MAMIE]),
    }
    const { wrapper } = await mountJoin('/join/K7QM2X', {
      'POST /api/local/K7QM2X/guest': () => Response.json(response),
      'GET /api/tables/7': () => Response.json(localTable([MAMIE])),
    })

    await wrapper.find('#guest-name').setValue('Mamie')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('#waiting-title').text()).toBe('Soirée poker')
    expect(wrapper.find('.player-list .me').text()).toContain('Mamie')
  })

  it('affiche l’erreur du serveur sous le pseudo', async () => {
    const { wrapper } = await mountJoin('/join/K7QM2X', {
      'POST /api/local/K7QM2X/guest': () =>
        Response.json(
          {
            status: 400,
            detail: 'Certains champs sont invalides',
            errors: { username: 'Le pseudo doit faire entre 3 et 20 caractères' },
          },
          { status: 400 },
        ),
    })

    await wrapper.find('#guest-name').setValue('Mo')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toBe('Certains champs sont invalides')
    expect(wrapper.find('.field-error').text()).toBe(
      'Le pseudo doit faire entre 3 et 20 caractères',
    )
  })

  it('connecté avec un compte : pas de pseudo à saisir, on rejoint avec le compte', async () => {
    localStorage.setItem('poker.token', 'jeton-compte')
    localStorage.setItem('poker.expiresAt', LATER)
    localStorage.setItem('poker.user', JSON.stringify({ id: 2, username: 'Alice' }))
    const alice = { id: 2, username: 'Alice' }
    const { wrapper } = await mountJoin('/join/K7QM2X', {
      'POST /api/local/K7QM2X/join': () => Response.json(localTable([alice])),
      'GET /api/tables/7': () => Response.json(localTable([alice])),
    })

    expect(wrapper.find('#guest-name').exists()).toBe(false)
    expect(wrapper.text()).toContain('Tu joueras avec ton compte Alice')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('.player-list .me').text()).toContain('Alice')
  })

  it('la télé lance la partie : le téléphone passe sur la table', async () => {
    localStorage.setItem('poker.token', 'jeton-invite')
    localStorage.setItem('poker.expiresAt', LATER)
    localStorage.setItem('poker.user', JSON.stringify(MAMIE))
    localStorage.setItem('poker.localSeat', '7')
    const { router } = await mountJoin('/join', {
      'GET /api/tables/7': () => Response.json(localTable([MAMIE])),
    })

    stomp.handlers.get('/topic/tables/7')!(
      localTable([MAMIE, { id: 2, username: 'Alice' }], 'PLAYING'),
    )
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('game')
    expect(router.currentRoute.value.params.id).toBe('7')
  })
})
