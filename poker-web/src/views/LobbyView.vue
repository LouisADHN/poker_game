<script setup lang="ts">
import {onMounted, onUnmounted, ref, watch} from 'vue'
import {useRouter} from 'vue-router'
import {ApiError} from '@/api/http'
import type {TableView} from '@/api/types'
import {useAuthStore} from '@/stores/auth'
import {useLobbyStore} from '@/stores/lobby'
import CreateTableForm from '@/components/CreateTableForm.vue'

const auth = useAuthStore()
const lobby = useLobbyStore()
const router = useRouter()

const error = ref<string | null>(null)

// --- Cycle de vie de la page
// onMounted : appelé quand la page s'affiche ; onUnmounted : quand on la quitte
onMounted(async () => {
  lobby.startLive() // écoute en direct d'abord, pour ne rater aucun changement
  await run(() => lobby.load())
})

onUnmounted(() => {
  lobby.stopLive()
})

/**
 * Exécute une action et affiche l'erreur éventuelle.
 * Le paramètre "action" est une FONCTION qui renvoie une promesse :
 * son type s'écrit () => Promise<void>.
 */
async function run(action: () => Promise<void>) {
  error.value = null
  try {
    await action()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : 'Impossible de contacter le serveur.'
  }
}

// --- Petites fonctions d'aide pour le template

/** Un joueur peut rejoindre une table en attente, non pleine, s'il n'est assis nulle part. */
function canJoin(table: TableView): boolean {
  return (
    lobby.myTable === null &&
    table.status === 'WAITING' &&
    table.players.length < table.settings.maxPlayers
  )
}

function isOwner(table: TableView): boolean {
  return table.ownerId === auth.user?.id
}

async function logout() {
  lobby.stopLive()
  auth.logout()
  await router.push({name: 'login'})
}

// Quand ma table passe en jeu, j'y vais : ça fonctionne pour tous les joueurs assis
watch(
  () => lobby.myTable?.status,
  (status) => {
    if (status === 'PLAYING' && lobby.myTable) {
      router.push({ name: 'game', params: { id: lobby.myTable.id } })
    }
  },
)
</script>

<template>
  <main class="lobby">
    <header>
      <h1>Lobby</h1>
      <p>Bienvenue, {{ auth.user?.username }} !</p>
      <button type="button" @click="logout">Se déconnecter</button>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <!-- Ma table : affichée seulement si je suis assis quelque part -->
    <section v-if="lobby.myTable" aria-labelledby="my-table-title">
      <h2 id="my-table-title">Ma table : {{ lobby.myTable.name }}</h2>
      <p>
        {{ lobby.myTable.players.length }} / {{ lobby.myTable.settings.maxPlayers }} joueurs
        <span v-if="isOwner(lobby.myTable)">(tu es le créateur)</span>
      </p>
      <ul>
        <li v-for="player in lobby.myTable.players" :key="player.userId">{{ player.username }}</li>
      </ul>
      <button
        v-if="isOwner(lobby.myTable) && lobby.myTable.status === 'WAITING' && lobby.myTable.players.length >=2"
        type="button" @click="run(() => lobby.start(lobby.myTable!.id))">Lancer la partie
      </button>
      <button type="button" @click="run(() => lobby.leave(lobby.myTable!.id))">Quitter la table
      </button>
    </section>

    <!-- Sinon : le formulaire de création -->
    <section v-else aria-labelledby="create-title">
      <h2 id="create-title">Créer une table</h2>
      <CreateTableForm/>
    </section>

    <!-- La liste des tables -->
    <section aria-labelledby="tables-title">
      <h2 id="tables-title">Tables ouvertes</h2>

      <p v-if="lobby.tables.length === 0">Aucune table pour l'instant. Crée la première !</p>

      <ul v-else>
        <!-- v-for parcourt la liste ; :key aide Vue à suivre chaque élément quand la liste change -->
        <li v-for="table in lobby.tables" :key="table.id">
          <strong>{{ table.name }}</strong>
          — blinds {{ table.settings.smallBlind }}/{{ table.settings.bigBlind }}
          — {{ table.players.length }}/{{ table.settings.maxPlayers }} joueurs
          — {{ table.status === 'WAITING' ? 'en attente' : 'partie en cours' }}

          <button v-if="canJoin(table)" type="button" @click="run(() => lobby.join(table.id))">
            Rejoindre
          </button>
        </li>
      </ul>
    </section>
  </main>
</template>
