import { defineStore } from 'pinia'
import { ref } from 'vue'
import { connect, publish, subscribe } from '@/api/stomp'
import type {
  ActionPayload,
  GameMessage,
  PlayerStatus,
  Standing,
  Street,
  TableView,
  YourTurn,
} from '@/api/types'
import { useAuthStore } from './auth'

/** Ce qu'on affiche pour chaque joueur autour de la table. */
export interface PlayerState {
  name: string
  chips: number
  streetBet: number
  status: PlayerStatus
  /** Dernière action, en texte : "relance à 120" */
  lastAction: string | null
  /** Cartes montrées au showdown, sinon null */
  revealedCards: string[] | null
  /** Catégorie de la main au showdown : "Full", "Paire"… */
  category: string | null
}

/** Doit correspondre à poker.game.turn-timeout côté serveur. */
const TURN_SECONDS = 30

const CATEGORY_LABELS: Record<string, string> = {
  HIGH_CARD: 'Carte haute',
  ONE_PAIR: 'Paire',
  TWO_PAIR: 'Double paire',
  THREE_OF_A_KIND: 'Brelan',
  STRAIGHT: 'Quinte',
  FLUSH: 'Couleur',
  FULL_HOUSE: 'Full',
  FOUR_OF_A_KIND: 'Carré',
  STRAIGHT_FLUSH: 'Quinte flush',
}

const STREET_LABELS: Record<Street, string> = {
  PREFLOP: 'Preflop',
  FLOP: 'Flop',
  TURN: 'Turn',
  RIVER: 'River',
}

/** Traduit une action en texte, comme describe() dans la ConsoleUI. */
export function describeAction(action: ActionPayload): string {
  switch (action.type) {
    case 'FOLD':
      return 'se couche'
    case 'CHECK':
      return 'checke'
    case 'CALL':
      return 'suit'
    case 'BET':
      return `mise à ${action.total}`
    case 'RAISE':
      return `relance à ${action.total}`
    case 'ALL_IN':
      return 'fait tapis'
  }
}

/**
 * État de la partie en cours, reconstruit à partir des messages du serveur.
 */
export const useGameStore = defineStore('game', () => {
  const auth = useAuthStore()

  const tableId = ref<number | null>(null)
  const tableName = ref('')
  const players = ref<PlayerState[]>([])
  const board = ref<string[]>([])
  const pot = ref(0)
  const myCards = ref<string[]>([])
  const dealer = ref<string | null>(null)
  const handNumber = ref(0)
  /** Non null quand c'est à moi de jouer : contient mes actions possibles */
  const turn = ref<YourTurn | null>(null)
  /** Heure limite pour jouer (en millisecondes), pour afficher le compte à rebours */
  const turnDeadline = ref<number | null>(null)
  /** Historique de la partie, le plus récent en premier */
  const log = ref<string[]>([])
  /** Classement final, non null quand la partie est terminée */
  const standings = ref<Standing[] | null>(null)
  const error = ref<string | null>(null)

  let unsubscribers: (() => void)[] = []

  /** Prépare l'état à partir de la table du lobby, et s'abonne aux messages de la partie. */
  function start(table: TableView): void {
    stop()

    tableId.value = table.id
    tableName.value = table.name
    players.value = table.players.map((seat) => ({
      name: seat.username,
      chips: table.settings.startingChips,
      streetBet: 0,
      status: 'ACTIVE',
      lastAction: null,
      revealedCards: null,
      category: null,
    }))
    board.value = []
    pot.value = 0
    myCards.value = []
    dealer.value = null
    handNumber.value = 0
    turn.value = null
    turnDeadline.value = null
    log.value = []
    standings.value = null
    error.value = null

    if (!auth.token) {
      return
    }
    connect(auth.token)
    unsubscribers = [
      subscribe<GameMessage>(`/topic/tables/${table.id}/game`, handle),
      subscribe<GameMessage>('/user/queue/game', handle),
    ]
  }

  function stop(): void {
    unsubscribers.forEach((unsubscribe) => unsubscribe())
    unsubscribers = []
  }

  /** Envoie mon action au serveur. Peut lever une erreur si la connexion est coupée. */
  function act(action: ActionPayload): void {
    if (tableId.value === null) {
      return
    }
    error.value = null
    publish(`/app/tables/${tableId.value}/action`, action)
    turn.value = null
    turnDeadline.value = null
  }

  // ------------------------------------------------------------------
  // Traitement des messages

  function findPlayer(name: string): PlayerState | undefined {
    return players.value.find((p) => p.name === name)
  }

  function addLog(line: string): void {
    log.value = [line, ...log.value].slice(0, 50)
  }

  /**
   * Met à jour l'état selon le message reçu.
   * Dans chaque case, TypeScript connaît la forme exacte de message.data.
   */
  function handle(message: GameMessage): void {
    switch (message.type) {
      case 'HAND_STARTED': {
        handNumber.value = message.data.handNumber
        dealer.value = message.data.dealer
        board.value = []
        pot.value = 0
        myCards.value = []
        for (const p of players.value) {
          p.streetBet = 0
          p.lastAction = null
          p.revealedCards = null
          p.category = null
          p.status = p.chips > 0 ? 'ACTIVE' : 'OUT'
        }
        addLog(`Main n°${message.data.handNumber} (dealer : ${message.data.dealer})`)
        break
      }

      case 'BLIND_POSTED': {
        const { player, amount } = message.data
        const p = findPlayer(player)
        if (p) {
          p.chips -= amount
          p.streetBet += amount
          if (p.chips === 0) {
            p.status = 'ALL_IN'
          }
        }
        pot.value += amount
        addLog(`${player} pose la blind de ${amount}`)
        break
      }

      case 'HOLE_CARDS':
        myCards.value = message.data.cards
        break

      case 'BOARD': {
        board.value = message.data.board
        // Nouveau tour d'enchères : les mises du tour précédent sont dans le pot
        for (const p of players.value) {
          p.streetBet = 0
          p.lastAction = null
        }
        addLog(`${STREET_LABELS[message.data.street]} : ${message.data.board.join(' ')}`)
        break
      }

      case 'PLAYER_ACTED': {
        const { player, action, streetBet, chipsLeft } = message.data
        const p = findPlayer(player)
        if (p) {
          pot.value += p.chips - chipsLeft // ce qu'il vient de mettre au pot
          p.chips = chipsLeft
          p.streetBet = streetBet
          p.lastAction = describeAction(action)
          if (action.type === 'FOLD') {
            p.status = 'FOLDED'
          } else if (chipsLeft === 0) {
            p.status = 'ALL_IN'
          }
        }
        addLog(`${player} ${describeAction(action)}`)
        break
      }

      case 'HAND_REVEALED': {
        const { player, cards, category } = message.data
        const label = CATEGORY_LABELS[category] ?? category
        const p = findPlayer(player)
        if (p) {
          p.revealedCards = cards
          p.category = label
        }
        addLog(`${player} montre ${cards.join(' ')} : ${label}`)
        break
      }

      case 'POT_WON': {
        const { player, amount } = message.data
        const p = findPlayer(player)
        if (p) {
          p.chips += amount
        }
        pot.value = Math.max(0, pot.value - amount)
        addLog(`${player} remporte ${amount}`)
        break
      }

      case 'HAND_ENDED':
        turn.value = null
        turnDeadline.value = null
        break

      case 'YOUR_TURN': {
        const data = message.data
        turn.value = data
        turnDeadline.value = Date.now() + TURN_SECONDS * 1000

        // Le serveur envoie ici l'état exact : on s'en sert pour corriger l'affichage
        pot.value = data.pot
        myCards.value = data.holeCards
        board.value = data.board
        const me = findPlayer(auth.user?.username ?? '')
        if (me) {
          me.chips = data.chips
          me.streetBet = data.myStreetBet
        }
        for (const opponent of data.opponents) {
          const p = findPlayer(opponent.name)
          if (p) {
            p.chips = opponent.chips
            p.streetBet = opponent.streetBet
            p.status = opponent.status
          }
        }
        break
      }

      case 'GAME_OVER':
        standings.value = message.data.standings
        turn.value = null
        turnDeadline.value = null
        addLog('Fin de la partie')
        break

      case 'ERROR':
      case 'GAME_ERROR':
        error.value = message.data.message
        break
    }
  }

  return {
    tableId,
    tableName,
    players,
    board,
    pot,
    myCards,
    dealer,
    handNumber,
    turn,
    turnDeadline,
    log,
    standings,
    error,
    start,
    stop,
    act,
  }
})
