import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api, ApiError } from '@/api/http'
import { connect, subscribe } from '@/api/stomp'
import type { GuestJoinResponse, TableView } from '@/api/types'
import { useAuthStore } from './auth'

/** Clé du localStorage : la table locale où je suis assis, pour la retrouver après un rechargement */
const STORAGE_KEY = 'poker.localSeat'

/**
 * Le joueur du mode local, sur son téléphone : il rejoint une table avec son code
 * (en invité ou avec son compte), attend le lancement, puis joue.
 *
 * Même principe que le store de la télé (localHost) : on garde de quoi retrouver la table,
 * et on la suit en direct pour voir arriver les autres joueurs et le lancement de la partie.
 */
export const useLocalPlayerStore = defineStore('localPlayer', () => {
  const auth = useAuthStore()

  /** La table où je suis assis, tenue à jour en direct ; null si je ne suis assis nulle part */
  const table = ref<TableView | null>(null)

  /** Fonction de désabonnement de /topic/tables/{id}, ou null si on n'écoute pas */
  let stopListening: (() => void) | null = null

  /** Vrai si je fais partie des joueurs de la table (calculé à partir de mon identifiant) */
  const isSeated = computed(
    () => table.value !== null && table.value.players.some((seat) => seat.userId === auth.user?.id),
  )

  /**
   * Rejoint en invité : le serveur crée l'invité, l'assoit et renvoie son jeton.
   * anonymous : surtout pas d'ancien jeton dans cette requête (voir http.ts).
   */
  async function joinAsGuest(code: string, username: string): Promise<void> {
    const response = await api<GuestJoinResponse>(`/api/local/${encodeURIComponent(code)}/guest`, {
      method: 'POST',
      body: { username },
      anonymous: true,
    })
    auth.saveSession(response) // désormais, je suis cet invité
    await seated(response.table)
  }

  /** Rejoint avec le compte connecté. */
  async function joinWithAccount(code: string): Promise<void> {
    const view = await api<TableView>(`/api/local/${encodeURIComponent(code)}/join`, {
      method: 'POST',
    })
    await seated(view)
  }

  /** Je viens de m'asseoir : on garde la table et on la suit. */
  async function seated(view: TableView): Promise<void> {
    localStorage.setItem(STORAGE_KEY, String(view.id))
    table.value = view
    await follow(view.id)
  }

  /**
   * Retrouve la table gardée (page rechargée). Si elle n'existe plus,
   * ou si je n'y suis plus assis, on l'oublie.
   */
  async function resume(): Promise<void> {
    const tableId = Number(localStorage.getItem(STORAGE_KEY))
    // Number(null) vaut 0 et Number("abc") vaut NaN : dans les deux cas, rien d'utilisable
    if (!Number.isInteger(tableId) || tableId <= 0 || !auth.isAuthenticated()) {
      forget()
      return
    }
    try {
      await follow(tableId)
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        forget()
        return
      }
      throw e
    }
    if (!isSeated.value) {
      forget()
    }
  }

  /**
   * Suit la table en direct : s'abonner D'ABORD, charger ENSUITE,
   * pour ne rater aucun changement (même raisonnement que le lobby et la télé).
   */
  async function follow(tableId: number): Promise<void> {
    stopLive()
    if (auth.token) {
      connect(auth.token)
      stopListening = subscribe<TableView>(`/topic/tables/${tableId}`, (view) => {
        table.value = view
      })
    }
    table.value = await api<TableView>(`/api/tables/${tableId}`)
  }

  /**
   * Quitte la table avant le lancement. Un invité n'a plus aucune raison d'exister ensuite :
   * il est déconnecté (pour rejouer, il rejoindra avec un nouveau pseudo).
   */
  async function leave(): Promise<void> {
    if (!table.value) {
      return
    }
    await api<void>(`/api/tables/${table.value.id}/leave`, { method: 'POST' })
    endLocalSession()
  }

  /**
   * Fin de mon passage à une table locale (je l'ai quittée, ou la partie est finie) :
   * on oublie la table, et un invité est déconnecté, car il n'existait que pour elle.
   * Un compte, lui, reste connecté.
   * Un seul endroit pour cette règle : quitter avant le lancement, quitter en cours de partie
   * et la fin de partie l'appellent tous les trois.
   */
  function endLocalSession(): void {
    forget()
    if (auth.isGuest) {
      auth.logout()
    }
  }

  function stopLive(): void {
    stopListening?.()
    stopListening = null
  }

  function forget(): void {
    stopLive()
    table.value = null
    localStorage.removeItem(STORAGE_KEY)
  }

  return {
    table,
    isSeated,
    joinAsGuest,
    joinWithAccount,
    resume,
    leave,
    endLocalSession,
    stopLive,
    forget,
  }
})
