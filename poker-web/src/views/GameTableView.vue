<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiError } from '@/api/http'
import type { ActionType, TableView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { TURN_SECONDS, useGameStore, type PlayerState } from '@/stores/game'
import PlayingCard from '@/components/PlayingCard.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const game = useGameStore()

const loadError = ref<string | null>(null)
/** Montant choisi pour une mise ou une relance */
const raiseTotal = ref(0)

/** Heure actuelle, rafraîchie régulièrement pour faire avancer le compte à rebours */
const now = ref(Date.now())
let clock: number | undefined

onMounted(async () => {
  clock = window.setInterval(() => {
    now.value = Date.now()
  }, 250)

  try {
    const table = await api<TableView>(`/api/tables/${Number(route.params.id)}`)
    game.start(table)
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : 'Impossible de charger la table.'
  }
})

onUnmounted(() => {
  window.clearInterval(clock)
  game.stop()
})

// Quand c'est à moi de jouer, on propose la relance minimum par défaut
watch(
  () => game.turn,
  (turn) => {
    if (turn) {
      raiseTotal.value = turn.legalActions.minRaiseTo
    }
  },
)

// ------------------------------------------------------------------
// Placement des joueurs autour de la table

/** Une place à la table : le joueur et ses positions, en % de la table */
interface Seat {
  player: PlayerState
  x: number
  y: number
  /** Position de ses jetons misés, entre sa place et le centre */
  betX: number
  betY: number
}

function isMe(name: string): boolean {
  return name === auth.user?.username
}

/**
 * Les joueurs répartis sur une ellipse.
 * On fait d'abord "tourner" la liste pour que je sois en premier : je suis donc en bas.
 */
const seats = computed<Seat[]>(() => {
  const players = game.players
  const myIndex = players.findIndex((p) => isMe(p.name))
  const ordered =
    myIndex <= 0 ? players : [...players.slice(myIndex), ...players.slice(0, myIndex)]

  return ordered.map((player, i) => {
    // π/2 = 90° = en bas de l'écran ; on tourne ensuite dans le sens des aiguilles d'une montre
    const angle = Math.PI / 2 + (2 * Math.PI * i) / ordered.length
    const cos = Math.cos(angle)
    const sin = Math.sin(angle)
    return {
      player,
      x: 50 + 47 * cos,
      y: 50 + 45 * sin,
      betX: 50 + 30 * cos,
      betY: 50 + 25 * sin,
    }
  })
})

/** Cartes du board pas encore révélées : dessinées en pointillés */
const emptyBoardSlots = computed(() => 5 - game.board.length)

function isInHand(player: PlayerState): boolean {
  return game.handNumber > 0 && (player.status === 'ACTIVE' || player.status === 'ALL_IN')
}

function statusText(player: PlayerState): string | null {
  if (player.status === 'OUT') return 'Éliminé'
  if (player.status === 'ALL_IN') return 'Tapis'
  return player.lastAction
}

// ------------------------------------------------------------------
// Mon tour

const secondsLeft = computed(() =>
  game.turnDeadline === null
    ? null
    : Math.max(0, Math.ceil((game.turnDeadline - now.value) / 1000)),
)

/** Remplissage de la jauge, de 100 % à 0 % */
const timerPercent = computed(() =>
  secondsLeft.value === null ? 0 : (secondsLeft.value / TURN_SECONDS) * 100,
)

const raiseLabel = computed(() => (game.turn?.currentBet === 0 ? 'Miser' : 'Relancer'))

/** Message de la barre d'actions quand ce n'est pas mon tour */
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

function raise() {
  const turn = game.turn
  if (!turn) {
    return
  }
  const { minRaiseTo, maxRaiseTo } = turn.legalActions
  const total = Math.min(Math.max(raiseTotal.value, minRaiseTo), maxRaiseTo)
  play(turn.currentBet === 0 ? 'BET' : 'RAISE', total)
}

function backToLobby() {
  router.push({ name: 'lobby' })
}
</script>

<template>
  <main class="game">
    <div v-if="loadError" class="error" role="alert">
      <p>{{ loadError }}</p>
      <button type="button" @click="backToLobby">Retour au lobby</button>
    </div>

    <p v-if="game.error" class="error" role="alert">{{ game.error }}</p>

    <div class="game-layout">
      <!-- Premier dans le code (le h1 reste le premier titre de la page),
           affiché en haut de la colonne de droite grâce à la grille -->
      <header class="game-header">
        <h1>{{ game.tableName || 'Table' }}</h1>
        <p>{{ game.handNumber > 0 ? `Main n°${game.handNumber}` : 'La partie va commencer…' }}</p>
      </header>

      <!-- La table -->
      <section class="table-area" aria-labelledby="table-title">
        <h2 id="table-title" class="visually-hidden">Table de jeu</h2>

        <div class="poker-table">
          <div class="felt">
            <div class="center">
              <div class="board" aria-label="Cartes communes">
                <PlayingCard v-for="card in game.board" :key="card" :card="card" />
                <span
                  v-for="n in emptyBoardSlots"
                  :key="`slot-${n}`"
                  class="card-slot"
                  aria-hidden="true"
                ></span>
              </div>
              <p class="pot">
                Pot <strong>{{ game.pot }}</strong>
              </p>
            </div>
          </div>

          <!-- Les jetons misés pendant ce tour, posés sur le tapis.
               Décoratifs (aria-hidden) : la mise est aussi indiquée dans la place du joueur. -->
          <template v-for="seat in seats" :key="`bet-${seat.player.name}`">
            <span
              v-if="seat.player.streetBet > 0"
              class="bet"
              :style="{ '--x': `${seat.betX}%`, '--y': `${seat.betY}%` }"
              aria-hidden="true"
            >
              {{ seat.player.streetBet }}
            </span>
          </template>

          <!-- Les joueurs -->
          <ul class="seats">
            <li
              v-for="seat in seats"
              :key="seat.player.name"
              class="seat"
              :class="{
                'seat--me': isMe(seat.player.name),
                'seat--turn': isMe(seat.player.name) && game.turn !== null,
                'seat--folded': seat.player.status === 'FOLDED',
                'seat--out': seat.player.status === 'OUT',
              }"
              :style="{ '--x': `${seat.x}%`, '--y': `${seat.y}%` }"
            >
              <p class="seat-name">
                {{ seat.player.name }}
                <span
                  v-if="seat.player.name === game.dealer"
                  class="dealer-button"
                  title="Dealer"
                  aria-label="Dealer"
                >D</span
                >
              </p>
              <p class="seat-chips">{{ seat.player.chips }}</p>
              <p v-if="seat.player.streetBet > 0" class="visually-hidden">
                Mise : {{ seat.player.streetBet }}
              </p>

              <!-- Cartes : montrées au showdown, les miennes, ou le dos pour un adversaire en jeu -->
              <div v-if="seat.player.revealedCards" class="seat-cards">
                <PlayingCard v-for="card in seat.player.revealedCards" :key="card" :card="card" />
              </div>
              <div
                v-else-if="isMe(seat.player.name) && game.myCards.length > 0"
                class="seat-cards"
              >
                <PlayingCard v-for="card in game.myCards" :key="card" :card="card" />
              </div>
              <div
                v-else-if="!isMe(seat.player.name) && isInHand(seat.player)"
                class="seat-cards"
              >
                <PlayingCard hidden />
                <PlayingCard hidden />
              </div>

              <p v-if="seat.player.category" class="seat-status">{{ seat.player.category }}</p>
              <p v-else-if="statusText(seat.player)" class="seat-status">
                {{ statusText(seat.player) }}
              </p>
            </li>
          </ul>
        </div>
      </section>

      <!-- Mes actions : la zone est toujours présente, pour que la mise en page ne saute pas -->
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
              <button type="button" @click="raise">{{ raiseLabel }} à {{ raiseTotal }}</button>
            </div>

            <button type="button" @click="play('ALL_IN')">Tapis ({{ game.turn.chips }})</button>
          </div>
        </template>

        <p v-else class="waiting">{{ waitingText }}</p>
      </section>

      <!-- Historique -->
      <section class="log-panel" aria-labelledby="log-title">
        <h2 id="log-title">Historique</h2>
        <ol class="log" aria-live="polite">
          <li v-for="(line, index) in game.log" :key="index">{{ line }}</li>
        </ol>
      </section>
    </div>

    <!-- Fin de partie -->
    <div v-if="game.standings" class="game-over">
      <div
        class="game-over-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="game-over-title"
      >
        <h2 id="game-over-title">Partie terminée</h2>
        <ol>
          <li v-for="standing in game.standings" :key="standing.player">
            <strong>{{ standing.player }}</strong> : {{ standing.chips }} jetons
          </li>
        </ol>
        <button type="button" class="primary" @click="backToLobby">Retour au lobby</button>
      </div>
    </div>
  </main>
</template>

<style scoped>
/* ------------------------------------------------------------------
   Mise en page (ordinateur) : la page occupe exactement la hauteur
   de la fenêtre, et la table prend toute la place disponible.
     ┌──────────────────────────────┬──────────┐
     │                              │ titre    │
     │ table                        ├──────────┤
     │ (tout l'espace restant)      │historique│
     ├──────────────────────────────┤ (défile) │
     │ barre d'actions (1 ligne)    │          │
     └──────────────────────────────┴──────────┘
   ------------------------------------------------------------------ */
.game {
  display: flex;
  flex-direction: column;
  width: min(100% - 2rem, 90rem);
  height: calc(100dvh - var(--app-bar-height));
  padding-block: var(--space-3);
}

.game-layout {
  flex: 1;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 16rem;
  grid-template-rows: auto minmax(0, 1fr) auto;
  grid-template-areas:
    'table header'
    'table log'
    'actions log';
  gap: var(--space-3) var(--space-6);
  min-height: 0;
}

.game-header {
  grid-area: header;
}

.game-header h1 {
  margin: 0;
  font-size: 1.4rem;
  overflow-wrap: anywhere;
}

.game-header p {
  margin: 0;
  color: var(--muted);
}

/* ------------------------------------------------------------------
   La zone de la table : un conteneur de taille (unités cqw / cqh).
   La table y prend la plus grande taille possible au format 16/10.
   ------------------------------------------------------------------ */
.table-area {
  grid-area: table;
  container-type: size;
  display: grid;
  place-items: center;
  min-height: 0;
  padding: 2.5rem 4rem;
}

/*
  La table est elle-même un conteneur (en largeur) : à l'intérieur,
  1cqw = 1 % de la largeur de la table. Places, cartes et textes
  s'expriment dans cette unité, pour grandir et rétrécir avec elle.
*/
.poker-table {
  container-type: inline-size;
  position: relative;
  width: min(100cqw, 160cqh);
  aspect-ratio: 16 / 10;
}

.felt {
  position: absolute;
  inset: 9% 7%;
  border: clamp(8px, 1.6cqw, 16px) solid var(--rail);
  border-radius: 50%;
  background: radial-gradient(
    ellipse at center,
    #2a7a60 0%,
    var(--felt) 45%,
    var(--felt-dark) 100%
  );
  box-shadow:
    inset 0 0 40px rgb(0 0 0 / 0.45),
    0 12px 30px rgb(0 0 0 / 0.35);
}

.center {
  position: absolute;
  inset: 0;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: clamp(0.25rem, 1.2cqw, 0.75rem);
  --card-w: clamp(2rem, 6cqw, 3.6rem);
}

.board {
  display: flex;
  gap: 0.15rem;
}

.card-slot {
  width: var(--card-w);
  height: calc(var(--card-w) * 1.43);
  margin: 0.15rem;
  border: 2px dashed rgb(255 255 255 / 0.18);
  border-radius: 6px;
}

.pot {
  margin: 0;
  color: rgb(255 255 255 / 0.75);
  font-size: clamp(0.8rem, 1.6cqw, 1rem);
}

.pot strong {
  color: var(--paper);
  font-family: var(--font-display);
  font-size: clamp(1rem, 2.6cqw, 1.5rem);
}

/* Les jetons misés, posés sur le tapis, devant les places (z-index) */
.bet {
  position: absolute;
  z-index: 1;
  left: var(--x);
  top: var(--y);
  transform: translate(-50%, -50%);
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  padding: 0.1rem 0.5rem 0.1rem 0.2rem;
  border-radius: 999px;
  background: rgb(0 0 0 / 0.45);
  color: var(--paper);
  font-weight: 700;
  font-size: clamp(0.75rem, 1.5cqw, 0.95rem);
}

/* Le petit jeton doré devant le montant */
.bet::before {
  content: '';
  width: 0.85rem;
  height: 0.85rem;
  border: 3px dashed var(--card);
  border-radius: 50%;
  background: var(--brass);
}

/* ------------------------------------------------------------------
   Les places : compactes, posées sur le rebord de la table
   ------------------------------------------------------------------ */
.seats {
  margin: 0;
  padding: 0;
  list-style: none;
}

.seat {
  position: absolute;
  left: var(--x);
  top: var(--y);
  transform: translate(-50%, -50%);
  width: clamp(6.5rem, 15cqw, 9rem);
  padding: 0.3rem 0.5rem;
  border: 1px solid var(--line);
  border-radius: var(--radius-m);
  background: var(--ink-raised);
  font-size: clamp(0.75rem, 1.5cqw, 0.95rem);
  line-height: 1.3;
  text-align: center;
  --card-w: clamp(1.3rem, 2.8cqw, 1.8rem);
}

.seat p {
  margin: 0;
}

.seat-name {
  font-family: var(--font-display);
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.seat-chips {
  color: var(--brass);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.seat-cards {
  display: flex;
  justify-content: center;
}

.seat-status {
  color: var(--muted);
  font-size: 0.9em;
}

.dealer-button {
  display: inline-grid;
  place-items: center;
  width: 1.3em;
  height: 1.3em;
  margin-left: 0.2em;
  border-radius: 50%;
  background: var(--card);
  color: var(--card-black);
  font-size: 0.8em;
  vertical-align: 0.1em;
}

/* Ma place : un peu plus grande, avec mes cartes bien visibles */
.seat--me {
  width: clamp(8rem, 18cqw, 10.5rem);
  border-color: var(--brass);
  --card-w: clamp(1.9rem, 4.2cqw, 2.7rem);
}

.seat--folded {
  opacity: 0.55;
}

.seat--out {
  opacity: 0.35;
}

/* La seule animation de la page : c'est mon tour */
.seat--turn {
  animation: my-turn 1.6s ease-in-out infinite;
}

@keyframes my-turn {
  0%,
  100% {
    box-shadow: 0 0 0 2px var(--brass);
  }
  50% {
    box-shadow: 0 0 0 6px rgb(212 175 90 / 0.35);
  }
}

/* ------------------------------------------------------------------
   Barre d'actions : une seule ligne, hauteur réservée en permanence
   ------------------------------------------------------------------ */
.action-bar {
  grid-area: actions;
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

/* ------------------------------------------------------------------
   Historique : défile à l'intérieur de sa colonne.
   contain: size l'empêche d'agrandir la grille, quelle que soit sa longueur.
   ------------------------------------------------------------------ */
.log-panel {
  grid-area: log;
  contain: size;
  overflow-y: auto;
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--line);
  border-radius: var(--radius-m);
}

.log-panel h2 {
  font-size: 1.05rem;
}

.log {
  margin: 0;
  padding: 0;
  list-style: none;
  color: var(--muted);
  font-size: 0.875rem;
}

.log li {
  padding: var(--space-1) 0;
  border-bottom: 1px solid rgb(58 69 88 / 0.5);
}

/* La ligne la plus récente, en haut, ressort */
.log li:first-child {
  color: var(--paper);
}

/* ------------------------------------------------------------------
   Fin de partie
   ------------------------------------------------------------------ */
.game-over {
  position: fixed;
  inset: 0;
  z-index: 10;
  display: grid;
  place-items: center;
  padding: var(--space-4);
  background: rgb(0 0 0 / 0.6);
}

.game-over-panel {
  width: min(100%, 24rem);
  padding: var(--space-6);
  border: 1px solid var(--brass);
  border-radius: var(--radius-m);
  background: var(--ink-raised);
}

.game-over-panel ol {
  margin: 0 0 var(--space-6);
  padding-left: var(--space-6);
}

/* ------------------------------------------------------------------
   Téléphone : la page défile normalement, sur une seule colonne,
   avec une table plus haute que large et la barre d'actions collée en bas
   ------------------------------------------------------------------ */
@media (max-width: 52rem) {
  .game {
    display: block;
    width: min(100% - 1rem, 90rem);
    height: auto;
  }

  .game-layout {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: auto;
    grid-template-areas:
      'header'
      'table'
      'actions'
      'log';
  }

  .table-area {
    container-type: normal;
    display: block;
    padding: var(--space-8) var(--space-2) var(--space-8);
  }

  .poker-table {
    width: 100%;
    aspect-ratio: 4 / 5;
  }

  .felt {
    inset: 10% 12%;
  }

  .seat {
    width: clamp(5.5rem, 24cqw, 7rem);
    font-size: clamp(0.7rem, 3cqw, 0.85rem);
  }

  .seat--me {
    width: clamp(7rem, 30cqw, 8.5rem);
  }

  .action-bar {
    position: sticky;
    bottom: 0;
    z-index: 2;
  }

  .log-panel {
    contain: none;
    max-height: 16rem;
  }
}
</style>
