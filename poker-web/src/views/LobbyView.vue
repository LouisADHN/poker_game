<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import type { TableView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useLobbyStore } from '@/stores/lobby'
import CreateTableForm from '@/components/CreateTableForm.vue'

const auth = useAuthStore()
const lobby = useLobbyStore()
const router = useRouter()

const error = ref<string | null>(null)

onMounted(async () => {
  lobby.startLive() // écoute en direct d'abord, pour ne rater aucun changement
  await run(() => lobby.load())
})

onUnmounted(() => {
  lobby.stopLive()
})

// Quand ma table passe en jeu, j'y vais : ça fonctionne pour tous les joueurs assis
watch(
  () => lobby.myTable?.status,
  (status) => {
    if (status === 'PLAYING' && lobby.myTable) {
      router.push({ name: 'game', params: { id: lobby.myTable.id } })
    }
  },
)

/** Exécute une action et affiche l'erreur éventuelle. */
async function run(action: () => Promise<void>) {
  error.value = null
  try {
    await action()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : 'Impossible de contacter le serveur.'
  }
}

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

function canStart(table: TableView): boolean {
  return isOwner(table) && table.status === 'WAITING' && table.players.length >= 2
}

function isMine(table: TableView): boolean {
  return table.id === lobby.myTable?.id
}

function playersText(table: TableView): string {
  return `${table.players.length}/${table.settings.maxPlayers} joueurs`
}
</script>

<template>
  <main class="lobby">
    <header>
      <h1>Tables</h1>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <div class="lobby-layout">
      <!-- Colonne de gauche : ma table, ou le formulaire de création -->
      <section v-if="lobby.myTable" class="panel panel--mine" aria-labelledby="my-table-title">
        <h2 id="my-table-title">Ma table</h2>
        <p class="table-name">{{ lobby.myTable.name }}</p>

        <dl class="table-settings">
          <dt>Blinds</dt>
          <dd>{{ lobby.myTable.settings.smallBlind }}/{{ lobby.myTable.settings.bigBlind }}</dd>
          <dt>Tapis de départ</dt>
          <dd>{{ lobby.myTable.settings.startingChips }}</dd>
          <dt>Joueurs</dt>
          <dd>{{ lobby.myTable.players.length }}/{{ lobby.myTable.settings.maxPlayers }}</dd>
        </dl>

        <ul class="players" aria-label="Joueurs assis">
          <li v-for="player in lobby.myTable.players" :key="player.userId">
            {{ player.username }}
            <span v-if="player.userId === lobby.myTable.ownerId" class="owner">créateur</span>
          </li>
        </ul>

        <!-- Ce qui va se passer ensuite -->
        <p v-if="isOwner(lobby.myTable) && lobby.myTable.players.length < 2" class="hint">
          Il faut au moins 2 joueurs pour lancer la partie.
        </p>
        <p v-else-if="!isOwner(lobby.myTable)" class="hint">
          En attente du lancement par le créateur…
        </p>

        <div class="panel-actions">
          <button
            v-if="canStart(lobby.myTable)"
            type="button"
            class="primary"
            @click="run(() => lobby.start(lobby.myTable!.id))"
          >
            Lancer la partie
          </button>
          <button type="button" @click="run(() => lobby.leave(lobby.myTable!.id))">
            Quitter la table
          </button>
        </div>
      </section>

      <section v-else class="panel" aria-labelledby="create-title">
        <h2 id="create-title">Créer une table</h2>
        <CreateTableForm />
      </section>

      <!-- Colonne de droite : les tables ouvertes -->
      <section class="tables" aria-labelledby="tables-title">
        <h2 id="tables-title">
          Tables ouvertes <span class="count">({{ lobby.tables.length }})</span>
        </h2>

        <p v-if="lobby.tables.length === 0" class="empty">
          Aucune table pour l'instant. Crée la première !
        </p>

        <ul v-else class="table-list">
          <li
            v-for="table in lobby.tables"
            :key="table.id"
            class="table-card"
            :class="{ 'table-card--mine': isMine(table) }"
          >
            <div class="table-info">
              <p class="table-name">{{ table.name }}</p>
              <p class="table-details">
                Blinds {{ table.settings.smallBlind }}/{{ table.settings.bigBlind }} ·
                {{ playersText(table) }}
                <span v-if="isMine(table)"> · ta table</span>
              </p>
            </div>

            <span
              class="status"
              :class="table.status === 'WAITING' ? 'status--waiting' : 'status--playing'"
            >
              {{ table.status === 'WAITING' ? 'En attente' : 'En cours' }}
            </span>

            <button
              v-if="canJoin(table)"
              type="button"
              class="primary"
              :aria-label="`Rejoindre la table ${table.name}`"
              @click="run(() => lobby.join(table.id))"
            >
              Rejoindre
            </button>
          </li>
        </ul>
      </section>
    </div>
  </main>
</template>

<style scoped>
/* Ordinateur : panneau à gauche, liste des tables à droite */
.lobby-layout {
  display: grid;
  grid-template-columns: 22rem minmax(0, 1fr);
  align-items: start;
  gap: var(--space-8);
}

.panel {
  padding: var(--space-6);
  border: 1px solid var(--line);
  border-radius: var(--radius-m);
  background: var(--ink-raised);
}

.panel--mine {
  border-color: var(--brass);
}

.table-name {
  margin: 0;
  font-family: var(--font-display);
  font-size: 1.3rem;
  font-weight: 700;
  overflow-wrap: anywhere;
}

/* Paramètres de la table : libellés à gauche, valeurs à droite */
.table-settings {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: var(--space-1) var(--space-4);
  margin: var(--space-4) 0;
}

.table-settings dt {
  color: var(--muted);
}

.table-settings dd {
  margin: 0;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

/* Les joueurs : des étiquettes arrondies */
.players {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin: 0 0 var(--space-4);
  padding: 0;
  list-style: none;
}

.players li {
  padding: 0.3rem 0.75rem;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--ink);
  font-weight: 700;
}

.owner {
  margin-left: var(--space-1);
  color: var(--brass);
  font-size: 0.8rem;
  font-weight: 400;
}

.hint {
  color: var(--muted);
  font-size: 0.95rem;
}

.panel-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

/* ------------------------------------------------------------------
   Liste des tables ouvertes
   ------------------------------------------------------------------ */
.count {
  color: var(--muted);
  font-weight: 400;
}

.empty {
  color: var(--muted);
}

.table-list {
  display: grid;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.table-card {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3) var(--space-4);
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--line);
  border-radius: var(--radius-m);
  background: var(--ink-raised);
}

.table-card--mine {
  border-color: var(--brass);
}

.table-info {
  flex: 1;
  min-width: 12rem;
}

.table-details {
  margin: 0;
  color: var(--muted);
  font-size: 0.95rem;
  font-variant-numeric: tabular-nums;
}

/* Pastilles de statut : vert feutre en attente, laiton en cours */
.status {
  padding: 0.15rem 0.65rem;
  border-radius: 999px;
  font-size: 0.85rem;
  font-weight: 700;
  white-space: nowrap;
}

.status--waiting {
  background: rgb(31 94 74 / 0.4);
  color: #8fd3b6;
}

.status--playing {
  background: rgb(212 175 90 / 0.15);
  color: var(--brass);
}

/* Téléphone : les deux colonnes s'empilent */
@media (max-width: 52rem) {
  .lobby-layout {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
