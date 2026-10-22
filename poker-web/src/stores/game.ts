import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/http'
import { connect, onEachConnection, publish, subscribe, type StompHeaders } from '@/api/stomp'
import type {
  ActionPayload,
  EndReason,
  GameMessage,
  GameState,
  PlayerStatus,
  Presence,
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
  /** Connecté, déconnecté, absent ou parti (message PLAYER_PRESENCE) */
  presence: Presence
}

/** Délai d'un tour tant que le serveur ne l'a pas encore indiqué (il l'envoie avec chaque YOUR_TURN) */
const DEFAULT_TURN_TIMEOUT_MS = 30_000

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
 * Texte court à afficher sur la place d'un joueur selon sa présence.
 * null quand il n'y a rien à signaler (le joueur est là).
 */
export function describePresence(presence: Presence): string | null {
  switch (presence) {
    case 'PRESENT':
      return null
    case 'DISCONNECTED':
      return 'Déconnecté…'
    case 'AWAY':
      return 'Absent'
    case 'LEFT':
      return 'A quitté la partie'
  }
}

/** Ligne d'historique annonçant un changement de présence. */
function presenceLogLine(player: string, presence: Presence): string {
  switch (presence) {
    case 'PRESENT':
      return `${player} est de retour`
    case 'DISCONNECTED':
      return `${player} s'est déconnecté`
    case 'AWAY':
      return `${player} est absent : il joue en automatique`
    case 'LEFT':
      return `${player} a quitté la partie`
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
  /** Délai complet d'un tour, envoyé par le serveur : sert à dessiner la jauge */
  const turnTimeoutMs = ref(DEFAULT_TURN_TIMEOUT_MS)
  /** Historique de la partie, le plus récent en premier */
  const log = ref<string[]>([])
  /** Classement final, non null quand la partie est terminée */
  const standings = ref<Standing[] | null>(null)
  /** Motif de fin de partie, non null quand la partie est terminée */
  const endReason = ref<EndReason | null>(null)
  const error = ref<string | null>(null)

  let unsubscribers: (() => void)[] = []

  /**
   * Numéro du dernier message public de la table pris en compte (en-tête "seq").
   * Variable simple et pas ref : rien ne l'affiche, Vue n'a pas besoin de la surveiller.
   */
  let lastSeq = 0
  /**
   * Vrai entre une demande d'état et la réception du GAME_STATE.
   * Pendant ce temps, les messages publics sont ignorés : ils sont tous inclus dans le GAME_STATE
   * qui arrive après eux (le serveur envoie tout dans un ordre unique).
   */
  let syncPending = true

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
      presence: 'PRESENT',
    }))
    board.value = []
    pot.value = 0
    myCards.value = []
    dealer.value = null
    handNumber.value = 0
    turn.value = null
    turnDeadline.value = null
    turnTimeoutMs.value = DEFAULT_TURN_TIMEOUT_MS
    lastSeq = 0
    syncPending = true
    log.value = []
    standings.value = null
    endReason.value = null
    error.value = null

    if (!auth.token) {
      return
    }
    connect(auth.token)
    unsubscribers = [
      subscribe<GameMessage>(`/topic/tables/${table.id}/game`, receiveTableMessage),
      subscribe<GameMessage>('/user/queue/game', handle),
      // APRÈS les abonnements : à la connexion (ou tout de suite si elle existe déjà),
      // puis à chaque reconnexion, on redemande l'état complet de la partie
      onEachConnection(requestSync),
    ]
  }

  /** Demande l'état complet de la partie : il arrivera en message privé GAME_STATE. */
  function requestSync(): void {
    if (tableId.value === null) {
      return
    }
    syncPending = true
    try {
      publish(`/app/tables/${tableId.value}/sync`, {})
    } catch {
      // Connexion coupée : la reconnexion redemandera l'état (onEachConnection)
    }
  }

  /**
   * Un message public de la table arrive, avec son numéro dans l'en-tête "seq".
   * - pendant une demande d'état : ignoré (le GAME_STATE attendu l'inclut déjà) ;
   * - numéro déjà vu : ignoré (déjà inclus dans le dernier GAME_STATE) ;
   * - numéro qui saute : un message a été perdu, on redemande l'état complet ;
   * - sinon : appliqué normalement.
   */
  function receiveTableMessage(message: GameMessage, headers: StompHeaders): void {
    if (syncPending) {
      return
    }
    const seq = Number(headers['seq'])
    if (seq <= lastSeq) {
      return
    }
    if (seq > lastSeq + 1) {
      requestSync()
      return
    }
    lastSeq = seq
    handle(message)
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

  /**
   * Quitte la partie en cours, définitivement : le serveur jouera pour moi jusqu'à la fin.
   * Peut lever une ApiError (par exemple 409 si la partie vient de se terminer).
   */
  async function leaveGame(): Promise<void> {
    if (tableId.value === null) {
      return
    }
    await api<void>(`/api/tables/${tableId.value}/leave-game`, { method: 'POST' })
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

  /** C'est à moi : affiche mes actions et le compte à rebours, à partir du temps envoyé par le serveur. */
  function startTurn(data: YourTurn): void {
    turn.value = data
    turnDeadline.value = Date.now() + data.timeLeftMs
    turnTimeoutMs.value = data.turnTimeoutMs
  }

  /** Remplace tout l'état de la partie par celui envoyé par le serveur (message GAME_STATE). */
  function applyState(state: GameState): void {
    const { table } = state
    handNumber.value = table.handNumber
    dealer.value = table.dealer
    board.value = table.board
    pot.value = table.pot
    myCards.value = state.holeCards
    players.value = table.players.map((p) => ({
      name: p.name,
      chips: p.chips,
      streetBet: p.streetBet,
      status: p.status,
      lastAction: p.lastAction ? describeAction(p.lastAction) : null,
      revealedCards: p.revealedCards,
      category: p.category ? (CATEGORY_LABELS[p.category] ?? p.category) : null,
      presence: state.presences[p.name] ?? 'PRESENT',
    }))

    if (state.yourTurn) {
      startTurn(state.yourTurn)
    } else {
      turn.value = null
      turnDeadline.value = null
    }

    // Les messages publics suivants reprennent juste après ce numéro
    lastSeq = state.seq
    syncPending = false
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
        // C'est moi qui viens d'agir : si le serveur a joué à ma place (délai écoulé),
        // mon tour est fini, il faut retirer les boutons et le compte à rebours
        if (player === auth.user?.username) {
          turn.value = null
          turnDeadline.value = null
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
        startTurn(data)

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

      case 'GAME_STATE':
        applyState(message.data)
        break

      case 'PLAYER_PRESENCE': {
        const { player, presence } = message.data
        const p = findPlayer(player)
        if (p) {
          p.presence = presence
        }
        addLog(presenceLogLine(player, presence))
        break
      }

      case 'GAME_OVER':
        standings.value = message.data.standings
        endReason.value = message.data.reason
        turn.value = null
        turnDeadline.value = null
        addLog(message.data.reason === 'ABANDONED' ? 'Partie abandonnée' : 'Fin de la partie')
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
    turnTimeoutMs,
    log,
    standings,
    endReason,
    error,
    start,
    stop,
    act,
    leaveGame,
    /** Exposé pour les tests : applique un message comme s'il venait du serveur. */
    handle,
    /** Exposé pour les tests : un message public de la table, avec ses en-têtes. */
    receiveTableMessage,
  }
})
