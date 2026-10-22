import { beforeEach, describe, expect, it, vi } from 'vitest'

/** Un message tel que la bibliothèque STOMP le donne : corps en texte et en-têtes */
interface FakeMessage {
  body: string
  headers: Record<string, string>
}

/**
 * L'état du faux client STOMP, partagé entre la fausse bibliothèque et les tests.
 * vi.hoisted : vi.mock est remonté tout en haut du fichier par Vitest,
 * ses variables doivent donc être créées avec lui.
 */
const fake = vi.hoisted(() => ({
  /** La configuration passée à new Client(...), dont onConnect */
  config: null as null | { onConnect: () => void },
  connected: false,
  /** Destinations des abonnements effectués, dans l'ordre */
  subscribed: [] as string[],
  callbacks: new Map<string, (message: FakeMessage) => void>(),
}))

// Remplace la vraie bibliothèque : aucune WebSocket n'est ouverte
vi.mock('@stomp/stompjs', () => ({
  Client: class {
    constructor(config: { onConnect: () => void }) {
      fake.config = config
    }
    get connected() {
      return fake.connected
    }
    activate() {}
    deactivate() {
      return Promise.resolve()
    }
    subscribe(destination: string, callback: (message: FakeMessage) => void) {
      fake.subscribed.push(destination)
      fake.callbacks.set(destination, callback)
      return { unsubscribe() {} }
    }
    publish() {}
  },
}))

// Importé APRÈS vi.mock : stomp.ts utilise la fausse bibliothèque
import { connect, disconnect, onEachConnection, subscribe } from '../stomp'

/** Simule l'établissement (ou le rétablissement) de la connexion */
function simulateConnection(): void {
  fake.connected = true
  fake.config!.onConnect()
}

describe('stomp', () => {
  beforeEach(async () => {
    await disconnect() // oublie la connexion et les abonnements du test précédent
    fake.config = null
    fake.connected = false
    fake.subscribed = []
    fake.callbacks.clear()
    connect('jeton')
  })

  it('transmet au handler le corps converti et les en-têtes', () => {
    const received: unknown[] = []
    subscribe<{ type: string }>('/topic/tables/7/game', (body, headers) => {
      received.push(body.type, headers['seq'])
    })
    simulateConnection()

    fake.callbacks.get('/topic/tables/7/game')!({
      body: '{"type":"HAND_STARTED"}',
      headers: { seq: '42' },
    })

    expect(received).toEqual(['HAND_STARTED', '42'])
  })

  it('onEachConnection appelle tout de suite si la connexion est déjà établie', () => {
    simulateConnection()
    const listener = vi.fn<() => void>()

    onEachConnection(listener)

    expect(listener).toHaveBeenCalledOnce()
  })

  it('à chaque (re)connexion, les abonnements sont rétablis AVANT de prévenir', () => {
    subscribe('/user/queue/game', () => {})
    // Au moment où il est prévenu, le listener note les abonnements déjà actifs
    let subscribedWhenNotified: string[] = []
    onEachConnection(() => {
      subscribedWhenNotified = [...fake.subscribed]
    })

    simulateConnection()

    expect(subscribedWhenNotified).toContain('/user/queue/game')
  })

  it('prévient à chaque reconnexion, jusqu’à ce qu’on se désinscrive', () => {
    const listener = vi.fn<() => void>()
    const stopListening = onEachConnection(listener)

    simulateConnection()
    simulateConnection() // coupure, puis reconnexion
    expect(listener).toHaveBeenCalledTimes(2)

    stopListening()
    simulateConnection()
    expect(listener).toHaveBeenCalledTimes(2)
  })
})
