import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api, ApiError } from '@/api/http'
import { connect, subscribe } from '@/api/stomp'
import type { CreateTableRequest, LocalTable, TableView } from '@/api/types'
import { useAuthStore } from './auth'

/** Clé du localStorage où l'on garde la table locale en attente, pour la retrouver après un rechargement */
const STORAGE_KEY = 'poker.localTable'

/** Ce qu'on garde : de quoi retrouver la table et réafficher son code. */
interface RememberedTable {
  tableId: number
  joinCode: string
}

/**
 * La table locale de l'hôte : l'écran partagé (la télé) qui la crée, affiche son code
 * et la liste des joueurs en direct, puis lance la partie.
 *
 * Pourquoi garder le code dans le localStorage ? Le serveur ne l'envoie qu'UNE fois, à la création
 * (il n'est jamais dans TableView, qui est diffusée à tout le monde). Sans lui, un simple
 * rechargement de la page sur la télé ferait perdre le code : plus personne ne pourrait rejoindre.
 */
export const useLocalHostStore = defineStore('localHost', () => {
  const auth = useAuthStore()

  /** La table en attente, tenue à jour en direct ; null tant qu'aucune n'est créée */
  const table = ref<TableView | null>(null)
  const joinCode = ref<string | null>(null)

  /** Fonction de désabonnement de /topic/tables/{id}, ou null si on n'écoute pas */
  let stopListening: (() => void) | null = null

  /** L'adresse que le QR code ouvre sur les téléphones (la page sera créée à l'étape 4) */
  const joinUrl = computed(() =>
    joinCode.value === null ? null : `${window.location.origin}/join/${joinCode.value}`,
  )

  /** Au moins 2 joueurs assis, partie pas encore lancée */
  const canStart = computed(
    () =>
      table.value !== null && table.value.status === 'WAITING' && table.value.players.length >= 2,
  )

  /** Crée la table locale et commence à suivre ses joueurs. Peut lever une ApiError. */
  async function create(request: CreateTableRequest): Promise<void> {
    const created = await api<LocalTable>('/api/local', { method: 'POST', body: request })
    remember({ tableId: created.table.id, joinCode: created.joinCode })
    table.value = created.table
    joinCode.value = created.joinCode
    await follow(created.table.id)
  }

  /**
   * Retrouve la table gardée dans le localStorage, s'il y en a une (page rechargée).
   * Si elle n'existe plus (partie terminée, serveur redémarré) ou n'est pas à moi, on l'oublie.
   */
  async function resume(): Promise<void> {
    const saved = readRemembered()
    if (!saved) {
      return
    }
    joinCode.value = saved.joinCode
    try {
      await follow(saved.tableId)
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        forget()
        return
      }
      throw e
    }
    if (!table.value?.local || table.value.ownerId !== auth.user?.id) {
      forget()
    }
  }

  /**
   * Suit la table en direct.
   *
   * L'ordre compte : on s'abonne D'ABORD, on charge ENSUITE (comme le lobby).
   * Dans l'autre ordre, un joueur qui s'assoit entre le chargement et l'abonnement
   * ne serait jamais affiché : son message serait parti avant qu'on écoute.
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

  /** Lance la partie. Le statut PLAYING arrive aussi par WebSocket ; on l'applique sans attendre. */
  async function start(): Promise<void> {
    if (!table.value) {
      return
    }
    table.value = await api<TableView>(`/api/tables/${table.value.id}/start`, { method: 'POST' })
  }

  function stopLive(): void {
    stopListening?.()
    stopListening = null
  }

  /** Oublie complètement la table locale (elle n'existe plus, ou n'est pas à moi). */
  function forget(): void {
    stopLive()
    table.value = null
    joinCode.value = null
    localStorage.removeItem(STORAGE_KEY)
  }

  return { table, joinCode, joinUrl, canStart, create, resume, start, stopLive, forget }
})

function remember(saved: RememberedTable): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(saved))
}

function readRemembered(): RememberedTable | null {
  const stored = localStorage.getItem(STORAGE_KEY)
  if (!stored) {
    return null
  }
  try {
    return JSON.parse(stored) as RememberedTable
  } catch {
    return null // contenu corrompu : comme s'il n'y avait rien
  }
}
