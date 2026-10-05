<script setup lang="ts">
import { computed } from 'vue'

// Les paramètres du composant : <PlayingCard card="As" /> ou <PlayingCard hidden />
const props = defineProps<{
  card?: string // "As", "Th"… ; le ? signifie "facultatif"
  hidden?: boolean
}>()

// Tables de correspondance : code -> texte
const SUIT_SYMBOLS: Record<string, string> = { s: '♠', h: '♥', d: '♦', c: '♣' }
const SUIT_NAMES: Record<string, string> = { s: 'pique', h: 'cœur', d: 'carreau', c: 'trèfle' }
const RANK_NAMES: Record<string, string> = { A: 'As', K: 'Roi', Q: 'Dame', J: 'Valet', T: '10' }

// Étape 1 : découper "As" en "A" et "s".
const rankCode = computed(() => props.card?.charAt(0) ?? '')
const suitCode = computed(() => props.card?.charAt(1) ?? '')

// Étape 2 : ce qu'on affiche
const rank = computed(() => (rankCode.value === 'T' ? '10' : rankCode.value))
const suit = computed(() => SUIT_SYMBOLS[suitCode.value] ?? '')
const isRed = computed(() => suitCode.value === 'h' || suitCode.value === 'd')

// Étape 3 : le texte lu par les lecteurs d'écran ("As de pique", "10 de cœur"…)
const label = computed(() => {
  if (props.hidden || !props.card) {
    return 'Carte cachée'
  }
  const rankName = RANK_NAMES[rankCode.value] ?? rankCode.value
  return `${rankName} de ${SUIT_NAMES[suitCode.value]}`
})
</script>

<template>
  <!-- Le dos de la carte -->
  <span v-if="hidden || !card" class="playing-card back" role="img" :aria-label="label"></span>

  <!-- La face : role="img" + aria-label font lire "As de pique" au lieu de "A pique" -->
  <span v-else class="playing-card" :class="{ red: isRed }" role="img" :aria-label="label">
    <span class="rank" aria-hidden="true">{{ rank }}</span>
    <span class="suit" aria-hidden="true">{{ suit }}</span>
  </span>
</template>

<style scoped>
/*
  La taille vient de la variable --card-w, définie par le parent
  (2.8rem par défaut) : la même carte peut être grande au centre de la table,
  et plus petite devant un adversaire.
*/
.playing-card {
  --w: var(--card-w, 2.8rem);

  display: inline-flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: var(--w);
  height: calc(var(--w) * 1.43); /* proportions d'une vraie carte */
  margin: 0.15rem;
  border-radius: calc(var(--w) * 0.12);
  background: var(--card);
  color: var(--card-black);
  font-family: var(--font-display);
  font-size: calc(var(--w) * 0.4);
  font-weight: 700;
  line-height: 1.05;
  box-shadow: 0 2px 4px rgb(0 0 0 / 0.35);
}

.red {
  color: var(--card-red);
}

/* Le dos : un motif en losanges laiton sur fond bois, avec une marge ivoire */
.back {
  border: calc(var(--w) * 0.07) solid var(--card);
  background:
    repeating-linear-gradient(45deg, transparent 0 5px, rgb(212 175 90 / 0.45) 5px 6px),
    repeating-linear-gradient(-45deg, transparent 0 5px, rgb(212 175 90 / 0.45) 5px 6px),
    var(--rail);
}
</style>
