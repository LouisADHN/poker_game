import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { publish } from '@/api/stomp'
import type { TableView, YourTurn } from '@/api/types'
import { useGameStore } from '@/stores/game'
import ActionBar from '../ActionBar.vue'

// Pas de vraie WebSocket : publish est espionné, pour vérifier l'action envoyée au serveur
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

/** C'est à moi de jouer : je peux checker, ou relancer entre 40 et 1000. */
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

/** Le store prêt pour la table 7 (sans jeton : aucune connexion), et la barre affichée. */
function mountBar() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const game = useGameStore()
  game.start(table)
  const wrapper = mount(ActionBar, { global: { plugins: [pinia] } })
  return { wrapper, game }
}

/** Trouve un bouton par son texte (plus lisible qu'un sélecteur CSS). */
function button(wrapper: ReturnType<typeof mountBar>['wrapper'], text: string) {
  const found = wrapper.findAll('button').find((b) => b.text().startsWith(text))
  if (!found) {
    throw new Error(`Bouton « ${text} » introuvable`)
  }
  return found
}

describe('ActionBar', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('avant la première main, annonce que la partie va commencer', () => {
    const { wrapper } = mountBar()

    expect(wrapper.find('.waiting').text()).toBe('La partie va commencer…')
    expect(wrapper.findAll('button')).toHaveLength(0)
  })

  it('pendant une main, sans être mon tour : attend les autres joueurs', async () => {
    const { wrapper, game } = mountBar()

    game.handle({ type: 'HAND_STARTED', data: { handNumber: 1, dealer: 'Bob' } })
    await flushPromises()

    expect(wrapper.find('.waiting').text()).toBe('En attente des autres joueurs…')
  })

  it('checker envoie CHECK au serveur, puis retire les boutons', async () => {
    const { wrapper, game } = mountBar()
    game.handle({ type: 'YOUR_TURN', data: yourTurn })
    await flushPromises()

    await button(wrapper, 'Checker').trigger('click')

    expect(publish).toHaveBeenCalledWith('/app/tables/7/action', { type: 'CHECK' })
    expect(wrapper.find('.waiting').exists()).toBe(true)
  })

  it('face à une mise : propose « Suivre » avec le montant, et envoie CALL', async () => {
    const { wrapper, game } = mountBar()
    game.handle({
      type: 'YOUR_TURN',
      data: {
        ...yourTurn,
        legalActions: { ...yourTurn.legalActions, canCheck: false, toCall: 20 },
      },
    })
    await flushPromises()

    expect(wrapper.text()).not.toContain('Checker')
    await button(wrapper, 'Suivre 20').trigger('click')

    expect(publish).toHaveBeenCalledWith('/app/tables/7/action', { type: 'CALL' })
  })

  it('propose la relance minimum par défaut, même si le tour a commencé avant l’affichage', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const game = useGameStore()
    game.start(table)
    game.handle({ type: 'YOUR_TURN', data: yourTurn }) // AVANT le montage de la barre

    const wrapper = mount(ActionBar, { global: { plugins: [pinia] } })
    await flushPromises()

    expect(wrapper.find('.raise-button').text()).toBe('Relancer à 40')
  })

  it('un montant trop grand est ramené au maximum autorisé', async () => {
    const { wrapper, game } = mountBar()
    game.handle({ type: 'YOUR_TURN', data: yourTurn })
    await flushPromises()

    await wrapper.find('input[type="number"]').setValue(5000)
    await wrapper.find('.raise-button').trigger('click')

    expect(publish).toHaveBeenCalledWith('/app/tables/7/action', { type: 'RAISE', total: 1000 })
  })

  it('sans mise à suivre, on « mise » (BET) au lieu de relancer', async () => {
    const { wrapper, game } = mountBar()
    game.handle({ type: 'YOUR_TURN', data: { ...yourTurn, currentBet: 0 } })
    await flushPromises()

    expect(wrapper.find('.raise-button').text()).toBe('Miser à 40')
    await wrapper.find('.raise-button').trigger('click')

    expect(publish).toHaveBeenCalledWith('/app/tables/7/action', { type: 'BET', total: 40 })
  })

  it('tapis envoie ALL_IN', async () => {
    const { wrapper, game } = mountBar()
    game.handle({ type: 'YOUR_TURN', data: yourTurn })
    await flushPromises()

    await button(wrapper, 'Tapis (980)').trigger('click')

    expect(publish).toHaveBeenCalledWith('/app/tables/7/action', { type: 'ALL_IN' })
  })

  it('connexion coupée : l’erreur est affichée au lieu de faire planter la page', async () => {
    vi.mocked(publish).mockImplementationOnce(() => {
      throw new Error('Connexion perdue')
    })
    const { wrapper, game } = mountBar()
    game.handle({ type: 'YOUR_TURN', data: yourTurn })
    await flushPromises()

    await button(wrapper, 'Se coucher').trigger('click')

    expect(game.error).toBe('Connexion perdue')
  })

  it('le compte à rebours avance avec le temps', async () => {
    vi.useFakeTimers() // Date.now et setInterval obéissent maintenant au test
    const { wrapper, game } = mountBar()
    game.handle({ type: 'YOUR_TURN', data: yourTurn })
    await flushPromises()
    expect(wrapper.find('.seconds').text()).toBe('30 s')

    await vi.advanceTimersByTimeAsync(5_000)

    expect(wrapper.find('.seconds').text()).toBe('25 s')
  })
})
