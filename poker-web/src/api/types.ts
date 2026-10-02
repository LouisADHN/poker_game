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
