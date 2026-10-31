import router from '@/router'
import { useAuthStore } from '@/stores/auth'
import type { ProblemDetail } from './types'

/**
 * Erreur renvoyée par l'API.
 * Le message est celui du serveur ("Pseudo ou mot de passe incorrect"…), prêt à être affiché.
 */
export class ApiError extends Error {
  readonly status: number
  readonly problem: ProblemDetail

  constructor(status: number, problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `Erreur ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }

  /** Erreurs de validation champ par champ (vide s'il n'y en a pas). */
  get fieldErrors(): Record<string, string> {
    return this.problem.errors ?? {}
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  /**
   * Vrai pour appeler le serveur SANS le jeton, même si on en a un.
   * Sert à la route qui crée un invité : un vieux jeton expiré y provoquerait un 401,
   * car le serveur vérifie tout jeton présent, même sur une route publique.
   */
  anonymous?: boolean
}

/**
 * Appelle l'API du serveur.
 * - ajoute le jeton de l'utilisateur connecté ;
 * - convertit le corps en JSON ;
 * - lève une ApiError si la réponse n'est pas un succès.
 */
export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const auth = useAuthStore()

  const headers: Record<string, string> = {}
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (auth.token && !options.anonymous) {
    headers['Authorization'] = `Bearer ${auth.token}`
  }

  const response = await fetch(path, {
    method: options.method ?? 'GET',
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  })

  if (!response.ok) {
    // On avait un jeton mais le serveur le refuse : il a expiré. Retour à la connexion.
    // (Sans jeton, un 401 est une simple erreur de connexion : mauvais mot de passe.)
    if (response.status === 401 && headers['Authorization']) {
      const wasGuest = auth.isGuest // à lire AVANT logout(), qui efface l'utilisateur
      auth.logout()
      // Un invité n'a pas de mot de passe : il retourne à la page pour rejoindre une partie
      await router.push({ name: wasGuest ? 'join' : 'login' })
    }

    let problem: ProblemDetail = { status: response.status }
    try {
      problem = await response.json()
    } catch {
      // Réponse sans corps JSON : on garde le statut seul
    }
    throw new ApiError(response.status, problem)
  }

  // 204 No Content : rien à lire
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}
