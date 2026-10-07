<script setup lang="ts">
import type { PlayerStats } from '@/api/types'

// Une seule prop, obligatoire (pas de ?) : les statistiques à afficher.
// Pas besoin de variable "props" : on ne l'utilise que dans le template.
defineProps<{
  stats: PlayerStats
}>()

/** 0.6667 -> "67 %" */
const percent = new Intl.NumberFormat('fr-FR', { style: 'percent', maximumFractionDigits: 0 })
</script>

<template>
  <dl class="stats-card">
    <div class="stat">
      <dt>Parties jouées</dt>
      <dd>{{ stats.gamesPlayed }}</dd>
    </div>

    <div class="stat">
      <dt>Victoires</dt>
      <dd>{{ stats.wins }}</dd>
    </div>

    <div class="stat">
      <dt>Taux de victoire</dt>
      <dd>{{ percent.format(stats.winRate) }}</dd>
    </div>

    <div class="stat">
      <dt>Mains gagnées</dt>
      <dd>{{ stats.handsWon }} / {{ stats.handsPlayed }}</dd>
    </div>
  </dl>
</template>

<style scoped>
/* Les quatre statistiques côte à côte, et les unes sous les autres sur un écran étroit */
.stats-card {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(8rem, 1fr));
  gap: 1rem;
  margin: 0;
}

.stat {
  padding: 0.75rem;
  border: 1px solid #ccc;
  border-radius: 0.5rem;
  text-align: center;
}

dt {
  font-size: 0.85rem;
  color: #555;
}

dd {
  margin: 0.25rem 0 0;
  font-size: 1.5rem;
  font-weight: bold;
}
</style>
