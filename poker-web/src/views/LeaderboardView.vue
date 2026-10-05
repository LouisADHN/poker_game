<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, ApiError } from '@/api/http'
import type { PlayerStats } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import PlayerStatsCard from '@/components/PlayerStatsCard.vue'

const auth = useAuthStore()

const leaderboard = ref<PlayerStats[]>([])
const myStats = ref<PlayerStats | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

/** Formate un taux entre 0 et 1 en pourcentage français : 0.6667 -> "67 %" */
const percent = new Intl.NumberFormat('fr-FR', { style: 'percent', maximumFractionDigits: 0 })

onMounted(async () => {
  try {
    // Les deux requêtes partent en même temps ; on attend que les deux soient terminées
    const [ranking, mine] = await Promise.all([
      api<PlayerStats[]>('/api/leaderboard'),
      api<PlayerStats>('/api/users/me/stats'),
    ])
    leaderboard.value = ranking
    myStats.value = mine
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : 'Impossible de charger le classement.'
  } finally {
    loading.value = false
  }
})

function isMe(stats: PlayerStats): boolean {
  return stats.username === auth.user?.username
}
</script>

<template>
  <main class="leaderboard">
    <header>
      <h1>Classement</h1>
    </header>

    <!-- Trois états possibles : chargement, erreur, ou données affichées -->
    <p v-if="loading">Chargement…</p>

    <p v-else-if="error" class="error" role="alert">{{ error }}</p>

    <template v-else>
      <!-- <template> regroupe plusieurs éléments sous une même condition, sans ajouter de balise à la page -->

      <section v-if="myStats" aria-labelledby="my-stats-title">
        <h2 id="my-stats-title">Mes statistiques</h2>
        <PlayerStatsCard :stats="myStats" />
      </section>

      <section aria-labelledby="ranking-title">
        <h2 id="ranking-title">Classement général</h2>

        <p v-if="leaderboard.length === 0">
          Aucune partie terminée pour l'instant. Lancez-en une pour apparaître ici !
        </p>

        <table v-else>
          <!-- caption : le titre du tableau, lu par les lecteurs d'écran -->
          <caption class="visually-hidden">
            Classement des joueurs par victoires, puis par taux de victoire
          </caption>
          <thead>
            <tr>
              <!-- scope="col" : indique que l'en-tête décrit une colonne -->
              <th scope="col">Rang</th>
              <th scope="col">Joueur</th>
              <th scope="col">Parties</th>
              <th scope="col">Victoires</th>
              <th scope="col">Taux de victoire</th>
              <th scope="col">Mains gagnées</th>
            </tr>
          </thead>
          <tbody>
            <!-- index commence à 0 : le rang vaut donc index + 1 -->
            <tr
              v-for="(stats, index) in leaderboard"
              :key="stats.username"
              :class="{ me: isMe(stats) }"
              :aria-current="isMe(stats) ? 'true' : undefined"
            >
              <td>{{ index + 1 }}</td>
              <!-- scope="row" : le pseudo décrit toute la ligne -->
              <th scope="row">{{ stats.username }}</th>
              <td>{{ stats.gamesPlayed }}</td>
              <td>{{ stats.wins }}</td>
              <td>{{ percent.format(stats.winRate) }}</td>
              <td>{{ stats.handsWon }} / {{ stats.handsPlayed }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>
  </main>
</template>

<style scoped>
.me {
  font-weight: bold;
}

/* Masqué à l'écran, mais lu par les lecteurs d'écran */
.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
</style>
