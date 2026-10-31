<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type { ActionType } from '@/api/types'
import { useGameStore } from '@/stores/game'

/**
 * La barre d'actions du joueur : le compte à rebours et les boutons quand c'est son tour,
 * un message d'attente sinon.
 *
 * Elle lit et modifie directement le store de la partie : son parent n'a rien à lui passer.
 * Le parent ne décide que deux choses : s'il l'affiche, et où il la place (grid-area…).
 */
const game = useGameStore()

/** Montant choisi pour une mise ou une relance */
const raiseTotal = ref(0)

/** Heure actuelle, rafraîchie régulièrement pour faire avancer le compte à rebours */
const now = ref(Date.now())
let clock: number | undefined

onMounted(() => {
  clock = window.setInterval(() => {
    now.value = Date.now()
  }, 250)
})

onUnmounted(() => window.clearInterval(clock))

// Quand c'est à moi de jouer, on propose la relance minimum par défaut.
// immediate : vérifie aussi tout de suite, au cas où mon tour aurait commencé avant l'affichage
watch(
  () => game.turn,
  (turn) => {
    if (turn) {
      raiseTotal.value = turn.legalActions.minRaiseTo
    }
  },
  { immediate: true },
)

const secondsLeft = computed(() =>
  game.turnDeadline === null
    ? null
    : Math.max(0, Math.ceil((game.turnDeadline - now.value) / 1000)),
)

/** Remplissage de la jauge, de 100 % à 0 % */
const timerPercent = computed(() =>
  secondsLeft.value === null ? 0 : ((secondsLeft.value * 1000) / game.turnTimeoutMs) * 100,
)

const raiseLabel = computed(() => (game.turn?.currentBet === 0 ? 'Miser' : 'Relancer'))

/** Message affiché quand ce n'est pas mon tour */
const waitingText = computed(() =>
  game.handNumber === 0 ? 'La partie va commencer…' : 'En attente des autres joueurs…',
)

function play(type: ActionType, total?: number) {
  try {
    game.act(total === undefined ? { type } : { type, total })
  } catch (e) {
    game.error = e instanceof Error ? e.message : 'Action impossible.'
  }
}

/** Mise ou relance, avec un montant ramené entre le minimum et le maximum autorisés */
function raise() {
  const turn = game.turn
  if (!turn) {
    return
  }
  const { minRaiseTo, maxRaiseTo } = turn.legalActions
  const total = Math.min(Math.max(raiseTotal.value, minRaiseTo), maxRaiseTo)
  play(turn.currentBet === 0 ? 'BET' : 'RAISE', total)
}
</script>

<template>
  <!-- La zone est toujours présente, pour que la mise en page ne saute pas -->
  <section
    class="action-bar"
    :class="{ 'action-bar--active': game.turn }"
    aria-labelledby="actions-title"
  >
    <h2 id="actions-title" class="visually-hidden">Mes actions</h2>

    <template v-if="game.turn">
      <!-- Jauge du temps restant : un trait fin en haut de la barre -->
      <div class="timer" aria-hidden="true">
        <span :style="{ width: `${timerPercent}%` }"></span>
      </div>

      <p class="turn-title">
        À toi
        <span v-if="secondsLeft !== null" class="seconds">{{ secondsLeft }} s</span>
      </p>

      <div class="actions">
        <button type="button" @click="play('FOLD')">Se coucher</button>

        <button
          v-if="game.turn.legalActions.canCheck"
          type="button"
          class="primary"
          @click="play('CHECK')"
        >
          Checker
        </button>
        <button v-else type="button" class="primary" @click="play('CALL')">
          Suivre {{ game.turn.legalActions.toCall }}
        </button>

        <div v-if="game.turn.legalActions.canRaise" class="raise">
          <label for="raise-total" class="visually-hidden">Montant de la relance</label>
          <input
            id="raise-total"
            v-model.number="raiseTotal"
            type="range"
            :min="game.turn.legalActions.minRaiseTo"
            :max="game.turn.legalActions.maxRaiseTo"
          />
          <input
            v-model.number="raiseTotal"
            type="number"
            aria-label="Montant"
            :min="game.turn.legalActions.minRaiseTo"
            :max="game.turn.legalActions.maxRaiseTo"
          />
          <button type="button" class="raise-button" @click="raise">
            {{ raiseLabel }} à {{ raiseTotal }}
          </button>
        </div>

        <button type="button" @click="play('ALL_IN')">Tapis ({{ game.turn.chips }})</button>
      </div>
    </template>

    <p v-else class="waiting">{{ waitingText }}</p>
  </section>
</template>

<style scoped>
/*
  L'apparence de la barre. Sa PLACE dans la page (grid-area, collée en bas sur téléphone)
  est décidée par le parent : la même barre pourra ainsi servir sur un autre écran.
*/
.action-bar {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) var(--space-4);
  min-height: 4rem;
  padding: var(--space-2) var(--space-4);
  border: 1px solid var(--line);
  border-radius: var(--radius-m);
  background: var(--ink-raised);
  overflow: hidden;
}

.action-bar--active {
  border-color: var(--brass);
}

.action-bar button {
  min-height: 2.5rem;
  padding: 0.35rem 0.9rem;
}

.action-bar input[type='number'] {
  padding-block: 0.4rem;
}

.waiting {
  margin: 0 auto;
  color: var(--muted);
}

/* La jauge : un trait fin collé en haut de la barre */
.timer {
  position: absolute;
  inset: 0 0 auto;
  height: 3px;
  background: var(--line);
}

.timer span {
  display: block;
  height: 100%;
  background: var(--brass);
  transition: width 0.25s linear;
}

.turn-title {
  margin: 0;
  font-family: var(--font-display);
  font-size: 1.15rem;
  font-weight: 700;
  white-space: nowrap;
}

.seconds {
  margin-left: var(--space-1);
  color: var(--brass);
  font-variant-numeric: tabular-nums;
}

.actions,
.raise {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.raise input[type='range'] {
  width: 8rem;
}

.raise input[type='number'] {
  width: 5.5rem;
}
</style>
