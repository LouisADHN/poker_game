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
// props.card peut être undefined : ?. évite l'erreur, et ?? '' donne un texte vide à la place.
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

<!-- scoped : ce style ne s'applique qu'à ce composant -->
<style scoped>
.playing-card {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 2.8rem;
  height: 4rem;
  margin: 0.15rem;
  border: 1px solid #999;
  border-radius: 0.35rem;
  background: #fff;
  color: #111;
  font-weight: bold;
  font-size: 1.1rem;
  line-height: 1.1;
}

.red {
  color: #c0392b;
}

.back {
  background: repeating-linear-gradient(45deg, #1e4f9c, #1e4f9c 6px, #2a63bd 6px, #2a63bd 12px);
}
</style>
