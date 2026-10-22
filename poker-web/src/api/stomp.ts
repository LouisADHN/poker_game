import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'

/**
 * Connexion WebSocket (STOMP) unique, partagée par toute l'application.
 *
 * - connect() ouvre la connexion avec le jeton JWT dans la trame CONNECT ;
 * - subscribe() fonctionne même avant que la connexion soit établie :
 *   l'abonnement est mémorisé et activé dès que possible ;
 * - en cas de coupure, la bibliothèque se reconnecte, et tous les abonnements sont rétablis ;
 * - onEachConnection() prévient à chaque (re)connexion, APRÈS le rétablissement des abonnements.
 */

/** En-têtes STOMP d'un message reçu, par exemple { seq: "42", destination: "/topic/…" } */
export type StompHeaders = Record<string, string>

/** Ce qu'on fait d'un message reçu : son corps (converti depuis le JSON) et ses en-têtes. */
export type MessageHandler<T> = (body: T, headers: StompHeaders) => void

/** Un abonnement mémorisé : sa destination, ce qu'il faut faire des messages, et l'abonnement actif. */
interface Registration {
  destination: string
  handler: MessageHandler<unknown>
  subscription: StompSubscription | null
}

const registrations = new Set<Registration>()
/** Fonctions à appeler à chaque (re)connexion */
const connectionListeners = new Set<() => void>()
let client: Client | null = null

/** Ouvre la connexion si elle n'existe pas encore. */
export function connect(token: string): void {
  if (client) {
    return
  }

  // ws:// en local, wss:// (chiffré) si le site est servi en https
  const protocol = location.protocol === 'https:' ? 'wss' : 'ws'

  client = new Client({
    brokerURL: `${protocol}://${location.host}/ws`,
    connectHeaders: { Authorization: `Bearer ${token}` },
    reconnectDelay: 3000, // nouvelle tentative 3 secondes après une coupure
    onConnect: () => {
      // À chaque (re)connexion, on (ré)active tous les abonnements mémorisés…
      for (const registration of registrations) {
        attach(registration)
      }
      // … puis seulement on prévient : un listener qui demande quelque chose au serveur
      // est sûr que les abonnements qui recevront la réponse sont déjà actifs
      for (const listener of connectionListeners) {
        listener()
      }
    },
    onStompError: (frame) => {
      console.error('Erreur WebSocket :', frame.headers['message'])
    },
  })
  client.activate()
}

/** Ferme la connexion et oublie tous les abonnements (à la déconnexion de l'utilisateur). */
export async function disconnect(): Promise<void> {
  registrations.clear()
  connectionListeners.clear()
  const current = client
  client = null
  await current?.deactivate()
}

/**
 * S'abonne à une destination. Les messages reçus sont convertis depuis le JSON,
 * et le handler reçoit aussi leurs en-têtes (il peut les ignorer).
 * Renvoie une fonction à appeler pour se désabonner.
 */
export function subscribe<T>(destination: string, handler: MessageHandler<T>): () => void {
  const registration: Registration = {
    destination,
    handler: handler as MessageHandler<unknown>,
    subscription: null,
  }
  registrations.add(registration)
  attach(registration)

  return () => {
    if (client?.connected) {
      registration.subscription?.unsubscribe()
    }
    registrations.delete(registration)
  }
}

/**
 * Appelle listener maintenant si la connexion est déjà établie, puis à chaque reconnexion.
 * Renvoie une fonction à appeler pour ne plus être prévenu.
 */
export function onEachConnection(listener: () => void): () => void {
  connectionListeners.add(listener)
  if (client?.connected) {
    listener()
  }
  return () => {
    connectionListeners.delete(listener)
  }
}

/** Envoie un message au serveur (vers une destination /app/...). */
export function publish(destination: string, body: unknown): void {
  if (!client?.connected) {
    throw new Error('La connexion temps réel est indisponible. Réessaie dans un instant.')
  }
  client.publish({
    destination,
    body: JSON.stringify(body),
    headers: { 'content-type': 'application/json' },
  })
}

/** Active un abonnement, si la connexion est établie (sinon, onConnect s'en chargera). */
function attach(registration: Registration): void {
  if (!client?.connected) {
    return
  }
  registration.subscription = client.subscribe(registration.destination, (message: IMessage) => {
    registration.handler(JSON.parse(message.body), message.headers)
  })
}
