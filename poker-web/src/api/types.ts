/**
 * Types des données échangées avec le serveur.
 * Ils reproduisent les records Java : si un record change côté serveur, il faut mettre à jour ce fichier.
 */

/** UserResponse côté serveur. */
export interface User {
  id: number
  username: string
}

/** LoginResponse côté serveur. */
export interface LoginResponse {
  token: string
  /** Date au format ISO, ex : "2026-10-02T21:30:00Z" */
  expiresAt: string
  user: User
}

/** Erreur renvoyée par l'ApiExceptionHandler (format Problem Details). */
export interface ProblemDetail {
  status: number
  title?: string
  detail?: string
  /** Erreurs de validation, champ par champ : { username: "Le pseudo doit…" } */
  errors?: Record<string, string>
}

// ------------------------------------------------------------------
// Lobby

/** Type littéral : l'équivalent de l'enum Java TableStatus. */
export type TableStatus = 'WAITING' | 'PLAYING'

/** Seat côté serveur : un joueur assis à une table. */
export interface Seat {
  userId: number
  username: string
}

/** TableSettings côté serveur. */
export interface TableSettings {
  smallBlind: number
  bigBlind: number
  startingChips: number
  maxPlayers: number
}

/** TableView côté serveur. */
export interface TableView {
  id: number
  name: string
  settings: TableSettings
  ownerId: number
  status: TableStatus
  players: Seat[]
}

/** CreateTableRequest côté serveur : les paramètres à plat, plus le nom. */
export interface CreateTableRequest {
  name: string
  smallBlind: number
  bigBlind: number
  startingChips: number
  maxPlayers: number
}

// ------------------------------------------------------------------

export type Street = 'PREFLOP' | 'FLOP' | 'TURN' | 'RIVER'

/** PlayerStatus côté moteur. */
export type PlayerStatus = 'ACTIVE' | 'FOLDED' | 'ALL_IN' | 'OUT'

export type ActionType = 'FOLD' | 'CHECK' | 'CALL' | 'BET' | 'RAISE' | 'ALL_IN'

/** Presence côté serveur : un joueur connecté, en délai de grâce, absent ou parti. */
export type Presence = 'PRESENT' | 'DISCONNECTED' | 'AWAY' | 'LEFT'

/** GameSession.EndReason côté serveur : partie allée à son terme, ou abandonnée. */
export type EndReason = 'COMPLETED' | 'ABANDONED'

/** Une action, envoyée au serveur ou reçue de lui. "total" n'existe que pour BET et RAISE. */
export interface ActionPayload {
  type: ActionType
  total?: number
}

/** LegalActions côté moteur. */
export interface LegalActions {
  canCheck: boolean
  toCall: number
  canRaise: boolean
  minRaiseTo: number
  maxRaiseTo: number
}

/** OpponentView côté moteur. */
export interface OpponentView {
  name: string
  chips: number
  status: PlayerStatus
  streetBet: number
}

/** Contenu du message YOUR_TURN : tout ce que le joueur a le droit de savoir pour décider. */
export interface YourTurn {
  street: Street
  holeCards: string[]
  board: string[]
  chips: number
  pot: number
  currentBet: number
  myStreetBet: number
  opponents: OpponentView[]
  legalActions: LegalActions
  /** Temps qu'il me reste pour jouer, en millisecondes (moins que le délai complet si je reviens en cours de tour) */
  timeLeftMs: number
  /** Délai complet d'un tour, en millisecondes : sert à dessiner la jauge */
  turnTimeoutMs: number
}

/** TableSnapshot.PlayerSnapshot côté serveur : ce qu'on voit d'un joueur. */
export interface PlayerSnapshot {
  name: string
  chips: number
  streetBet: number
  status: PlayerStatus
  /** Dernière action pendant ce tour d'enchères, ou null */
  lastAction: ActionPayload | null
  /** Cartes montrées au showdown, ou null */
  revealedCards: string[] | null
  /** Catégorie de la main au showdown ("FULL_HOUSE"…), ou null */
  category: string | null
}

/** TableSnapshot côté serveur : la photographie publique de la partie. */
export interface TableSnapshot {
  handNumber: number
  /** null avant la première main */
  dealer: string | null
  /** null avant la première main */
  street: Street | null
  board: string[]
  pot: number
  players: PlayerSnapshot[]
}

/** Contenu du message GAME_STATE : l'état complet de la partie, vu par moi. */
export interface GameState {
  /** Numéro du dernier message public déjà inclus dans cet état */
  seq: number
  table: TableSnapshot
  /** Présence de chaque joueur, par pseudo */
  presences: Record<string, Presence>
  /** Mes cartes (liste vide avant la première main) */
  holeCards: string[]
  /** Mon tour en cours, ou null si ce n'est pas à moi */
  yourTurn: YourTurn | null
}

export interface Standing {
  player: string
  chips: number
}

/** PlayerStats côté serveur. */
export interface PlayerStats {
  username: string
  gamesPlayed: number
  wins: number
  handsPlayed: number
  handsWon: number
  winRate: number
}

/**
 * Union discriminée de tous les messages de partie : le champ "type" détermine la forme de "data".
 * C'est l'équivalent de la sealed interface GameEvent côté Java.
 */
export type GameMessage =
  | { type: 'HAND_STARTED'; data: { handNumber: number; dealer: string } }
  | { type: 'BLIND_POSTED'; data: { player: string; amount: number } }
  | { type: 'HOLE_CARDS'; data: { player: string; cards: string[] } }
  | { type: 'BOARD'; data: { street: Street; board: string[] } }
  | {
  type: 'PLAYER_ACTED'
  data: { player: string; action: ActionPayload; streetBet: number; chipsLeft: number }
}
  | { type: 'HAND_REVEALED'; data: { player: string; cards: string[]; category: string } }
  | { type: 'POT_WON'; data: { player: string; amount: number } }
  | { type: 'HAND_ENDED'; data: { handNumber: number } }
  | { type: 'YOUR_TURN'; data: YourTurn }
  | { type: 'GAME_STATE'; data: GameState }
  | { type: 'PLAYER_PRESENCE'; data: { player: string; presence: Presence } }
  | { type: 'GAME_OVER'; data: { reason: EndReason; standings: Standing[] } }
  | { type: 'ERROR' | 'GAME_ERROR'; data: { message: string } }
