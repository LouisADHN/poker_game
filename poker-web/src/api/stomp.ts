import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'

/**
 * Connexion WebSocket (STOMP) unique, partagée par toute l'application.
 *
 * - connect() ouvre la connexion avec le jeton JWT dans la trame CONNECT ;
 * - subscribe() fonctionne même avant que la connexion soit établie :
 *   l'abonnement est mémorisé et activé dès que possible ;
 * - en cas de coupure, la bibliothèque se reconnecte, et tous les abonnements sont rétablis.
 */

/** Un abonnement mémorisé : sa destination, ce qu'il faut faire des messages, et l'abonnement actif. */
interface Registration {
  destination: string
  handler: (body: unknown) => void
  subscription: StompSubscription | null
}

const registrations = new Set<Registration>()
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
      // À chaque (re)connexion, on (ré)active tous les abonnements mémorisés
      for (const registration of registrations) {
        attach(registration)
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
  const current = client
  client = null
  await current?.deactivate()
}

/**
 * S'abonne à une destination. Les messages reçus sont convertis depuis le JSON.
 * Renvoie une fonction à appeler pour se désabonner.
 */
export function subscribe<T>(destination: string, handler: (body: T) => void): () => void {
  const registration: Registration = {
    destination,
    handler: handler as (body: unknown) => void,
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
    registration.handler(JSON.parse(message.body))
  })
}
