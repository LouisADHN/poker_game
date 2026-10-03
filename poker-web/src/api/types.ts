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
