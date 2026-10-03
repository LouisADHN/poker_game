import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '@/api/http'
import { connect, subscribe } from '@/api/stomp'
import type { CreateTableRequest, TableView } from '@/api/types'
import { useAuthStore } from './auth'

/**
 * État du lobby : la liste des tables, tenue à jour en direct par WebSocket.
 */
export const useLobbyStore = defineStore('lobby', () => {
  const auth = useAuthStore()

  const tables = ref<TableView[]>([])

  /** Fonction de désabonnement de /topic/lobby, ou null si on n'écoute pas. */
  let stopListening: (() => void) | null = null

  /** La table où l'utilisateur connecté est assis, ou null. Recalculée dès que la liste change. */
  const myTable = computed<TableView | null>(
    () => tables.value.find((table) => table.players.some((p) => p.userId === auth.user?.id)) ?? null,
  )

  /** Charge la liste en HTTP. */
  async function load(): Promise<void> {
    tables.value = await api<TableView[]>('/api/tables')
  }

  /** Commence à écouter les changements en direct. */
  function startLive(): void {
    if (stopListening || !auth.token) {
      return // déjà en écoute, ou pas connecté
    }
    connect(auth.token)
    stopListening = subscribe<TableView[]>('/topic/lobby', (list) => {
      tables.value = list
    })
  }

  function stopLive(): void {
    stopListening?.()
    stopListening = null
  }

  async function create(request: CreateTableRequest): Promise<void> {
    await api<TableView>('/api/tables', { method: 'POST', body: request })
    await load()
  }

  async function join(tableId: number): Promise<void> {
    await api<TableView>(`/api/tables/${tableId}/join`, { method: 'POST' })
    await load()
  }

  async function start(tableId: number): Promise<void> {
    await api<void>(`/api/tables/${tableId}/start`, { method: 'POST' })
  }

  async function leave(tableId: number): Promise<void> {
    await api<void>(`/api/tables/${tableId}/leave`, { method: 'POST' })
    await load()
  }

  return { tables, myTable, load, startLive, stopLive, create, join, start, leave }
})
