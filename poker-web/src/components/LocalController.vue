<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useGameStore } from '@/stores/game'
import ActionBar from './ActionBar.vue'
import LeaveGameButton from './LeaveGameButton.vue'
import PlayingCard from './PlayingCard.vue'

/**
 * La manette du mode local : le téléphone d'un joueur assis à une table locale.
 *
 * La table est déjà dessinée sur la télé, que tout le monde regarde. Le téléphone n'affiche
 * que ce qui est à moi (mes cartes, que personne d'autre ne doit voir) et de quoi jouer :
 * mon tapis, le pot, ce qu'il me faut pour suivre, et mes actions.
 */
const auth = useAuthStore()
const game = useGameStore()

/** Ma place à la table ; undefined tant que la liste des joueurs ne me contient pas */
const me = computed(() => game.players.find((p) => p.name === auth.user?.username))

/** Mon état dans la main, en toutes lettres ; null quand je joue normalement */
const myStatus = computed(() => {
  switch (me.value?.status) {
    case 'FOLDED':
      return 'Couché'
    case 'ALL_IN':
      return 'Tapis'
    case 'OUT':
      return 'Éliminé'
    default:
      return null
  }
})

/**
 * Ce qu'il me faut mettre pour suivre ; null quand la question ne se pose pas
 * (pas de main en cours, ou je ne joue plus cette main).
 * - pendant mon tour : le montant exact envoyé par le serveur ;
 * - sinon : la plus grosse mise de ce tour d'enchères, moins la mienne,
 *   sans dépasser mon tapis (au-delà, suivre veut dire faire tapis).
 */
const toCall = computed<number | null>(() => {
  const player = me.value
  if (game.handNumber === 0 || player === undefined || player.status !== 'ACTIVE') {
    return null
  }
  if (game.turn) {
    return game.turn.legalActions.toCall
  }
  // "...game.players.map(...)" : étale le tableau en arguments séparés, comme Math.max(20, 40, 10)
  const highestBet = Math.max(...game.players.map((p) => p.streetBet))
  return Math.min(highestBet - player.streetBet, player.chips)
})
</script>

<template>
  <div class="controller">
    <header class="controller-header">
      <h1>{{ game.tableName || 'Table' }}</h1>
      <p>{{ game.handNumber > 0 ? `Main n°${game.handNumber}` : 'La partie va commencer…' }}</p>
      <!-- En haut, loin des boutons de jeu où passe le pouce : pas de départ par mégarde -->
      <LeaveGameButton class="controller-leave" />
    </header>

    <section class="my-hand" aria-labelledby="my-hand-title">
      <h2 id="my-hand-title" class="visually-hidden">Mes cartes</h2>
      <div class="my-cards" :class="{ 'my-cards--folded': me?.status === 'FOLDED' }">
        <template v-if="game.myCards.length > 0">
          <PlayingCard v-for="card in game.myCards" :key="card" :card="card" />
        </template>
        <template v-else>
          <!-- Deux emplacements vides, pour que la page ne saute pas quand les cartes arrivent -->
          <span class="card-slot" aria-hidden="true"></span>
          <span class="card-slot" aria-hidden="true"></span>
          <p class="visually-hidden">Pas de cartes pour l’instant</p>
        </template>
      </div>
      <p v-if="myStatus" class="my-status">{{ myStatus }}</p>
    </section>

    <!-- dl : une liste de couples « intitulé / valeur », lue comme telle par les lecteurs d'écran -->
    <dl class="numbers">
      <div>
        <dt>Mon tapis</dt>
        <dd>{{ me?.chips ?? 0 }}</dd>
      </div>
      <div>
        <dt>Pot</dt>
        <dd>{{ game.pot }}</dd>
      </div>
      <div v-if="toCall !== null">
        <dt>À suivre</dt>
        <dd>{{ toCall }}</dd>
      </div>
    </dl>

    <!-- class="controller-actions" s'ajoute à celles de l'élément racine du composant -->
    <ActionBar class="controller-actions" />
  </div>
</template>

<style scoped>
/*
  Une colonne, centrée, pensée pour un téléphone tenu en portrait.
  Les actions restent collées en bas de l'écran : on joue avec le pouce.
*/
.controller {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-4);
  min-height: 100%;
  padding-bottom: var(--space-4);
  text-align: center;
}

.controller-header h1 {
  margin: 0;
  font-size: 1.3rem;
  overflow-wrap: anywhere;
}

.controller-header p {
  margin: 0;
  color: var(--muted);
}

.controller-leave {
  margin-top: var(--space-2);
}

/* Mes cartes : le plus gros de l'écran, c'est ce qu'on regarde avant de jouer */
.my-hand {
  display: grid;
  justify-items: center;
  gap: var(--space-2);
}

.my-cards {
  display: flex;
  gap: var(--space-2);
  --card-w: clamp(5rem, 30vw, 8rem);
}

/* Couché : mes cartes restent visibles, mais en retrait (le mot « Couché » est écrit dessous) */
.my-cards--folded {
  opacity: 0.45;
}

.card-slot {
  width: var(--card-w);
  height: calc(var(--card-w) * 1.43);
  margin: 0.15rem;
  border: 2px dashed var(--line);
  border-radius: calc(var(--card-w) * 0.12);
}

.my-status {
  margin: 0;
  font-family: var(--font-display);
  font-size: 1.2rem;
  font-weight: 700;
}

/* Les chiffres : trois colonnes égales */
.numbers {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-2);
  width: 100%;
  margin: 0;
}

.numbers div {
  padding: var(--space-2);
  border: 1px solid var(--line);
  border-radius: var(--radius-m);
  background: var(--ink-raised);
}

.numbers dt {
  color: var(--muted);
  font-size: 0.85rem;
}

.numbers dd {
  margin: 0;
  color: var(--brass);
  font-family: var(--font-display);
  font-size: 1.5rem;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

/*
  La barre d'actions : toute la largeur, collée en bas pendant le défilement.
  ".controller .controller-actions" : plus précis que la règle du composant
  (position: relative), pour gagner quel que soit l'ordre de chargement du CSS.
*/
.controller .controller-actions {
  position: sticky;
  bottom: 0;
  z-index: 2;
  width: 100%;
  margin-top: auto;
}
</style>
