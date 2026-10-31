<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiError } from '@/api/http'
import type { TableView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { describePresence, useGameStore, type PlayerState } from '@/stores/game'
import ActionBar from '@/components/ActionBar.vue'
import LeaveGameButton from '@/components/LeaveGameButton.vue'
import LocalController from '@/components/LocalController.vue'
import PlayingCard from '@/components/PlayingCard.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const game = useGameStore()

const loadError = ref<string | null>(null)

onMounted(async () => {
  try {
    const table = await api<TableView>(`/api/tables/${Number(route.params.id)}`)
    game.start(table)
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : 'Impossible de charger la table.'
  }
})

onUnmounted(() => game.stop())

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
  const ordered = myIndex <= 0 ? players : [...players.slice(myIndex), ...players.slice(0, myIndex)]

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

/** Vrai si le joueur n'est pas là : déconnecté, absent ou parti */
function isAbsent(player: PlayerState): boolean {
  return player.presence !== 'PRESENT'
}

function statusText(player: PlayerState): string | null {
  if (player.status === 'OUT') return 'Éliminé'
  if (player.status === 'ALL_IN') return 'Tapis'
  return player.lastAction
}

function backToLobby() {
  router.push({ name: 'lobby' })
}

// ------------------------------------------------------------------
// Fin de partie

const gameOverTitle = computed(() =>
  game.endReason === 'ABANDONED' ? 'Partie abandonnée' : 'Partie terminée',
)

/** Après une partie locale, la télé repart vers la création d'une nouvelle partie */
function newLocalGame() {
  router.push({ name: 'local-host' })
}

// ------------------------------------------------------------------
// Grand écran (la télé du mode local)

/** Nombre de lignes d'historique sur la télé : lisibles de loin, donc peu nombreuses */
const BIG_SCREEN_LOG_LINES = 6

/** L'historique affiché : tout sur un ordinateur, seulement les dernières lignes sur la télé */
const visibleLog = computed(() =>
  game.bigScreen ? game.log.slice(0, BIG_SCREEN_LOG_LINES) : game.log,
)

/**
 * Le navigateur sait-il passer en plein écran ? (pas tous : certains téléphones, et jsdom dans les tests)
 * Une simple constante : la réponse ne change pas pendant la vie de la page.
 */
const canFullscreen = typeof document.documentElement.requestFullscreen === 'function'

/** Vrai quand la page est en plein écran, tenu à jour par l'événement fullscreenchange */
const fullscreen = ref(false)

function onFullscreenChange() {
  fullscreen.value = document.fullscreenElement !== null
}

onMounted(() => document.addEventListener('fullscreenchange', onFullscreenChange))
onUnmounted(() => document.removeEventListener('fullscreenchange', onFullscreenChange))

/**
 * Passe en plein écran, ou en sort.
 * Le navigateur ne l'accepte qu'après un geste de l'utilisateur (un clic) : impossible de le faire
 * automatiquement à l'arrivée sur la page, d'où ce bouton.
 */
async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen()
    } else {
      await document.documentElement.requestFullscreen()
    }
  } catch {
    // Refusé par le navigateur : la page reste simplement comme elle est
  }
}
</script>

<template>
  <main class="game" :class="{ 'game--big': game.bigScreen, 'game--controller': game.controller }">
    <div v-if="loadError" class="error" role="alert">
      <p>{{ loadError }}</p>
      <button type="button" @click="backToLobby">Retour au lobby</button>
    </div>

    <p v-if="game.error" class="error" role="alert">{{ game.error }}</p>

    <!--
      Trois écrans pour une même partie :
      - la manette : le téléphone d'un joueur d'une table locale (la table est sur la télé) ;
      - sinon la table complète, sur ordinateur comme sur la télé (game--big).
      La fin de partie, plus bas, est commune aux trois.
    -->
    <LocalController v-if="game.controller" />

    <div v-else class="game-layout">
      <!-- Premier dans le code (le h1 reste le premier titre de la page),
           affiché en haut de la colonne de droite grâce à la grille -->
      <header class="game-header">
        <h1>{{ game.tableName || 'Table' }}</h1>
        <p>{{ game.handNumber > 0 ? `Main n°${game.handNumber}` : 'La partie va commencer…' }}</p>
        <!-- La télé : pas de badge ni de lien, seulement le plein écran -->
        <template v-if="game.bigScreen">
          <button
            v-if="canFullscreen"
            type="button"
            class="fullscreen-button"
            @click="toggleFullscreen"
          >
            {{ fullscreen ? 'Quitter le plein écran' : 'Plein écran' }}
          </button>
        </template>
        <template v-else-if="game.spectator">
          <p class="spectator-badge">Mode spectateur</p>
          <RouterLink :to="{ name: 'lobby' }" class="back-link">Retour au lobby</RouterLink>
        </template>
        <!-- Ne s'affiche que pour un joueur, pendant la partie (le composant le décide) -->
        <LeaveGameButton />
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
                'seat--away': isAbsent(seat.player),
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
              <p v-if="isAbsent(seat.player)" class="seat-presence">
                {{ describePresence(seat.player.presence) }}
              </p>
              <p class="seat-chips">{{ seat.player.chips }}</p>
              <p v-if="seat.player.streetBet > 0" class="visually-hidden">
                Mise : {{ seat.player.streetBet }}
              </p>

              <!-- Cartes : montrées au showdown, les miennes, ou le dos pour un adversaire en jeu -->
              <div v-if="seat.player.revealedCards" class="seat-cards">
                <PlayingCard v-for="card in seat.player.revealedCards" :key="card" :card="card" />
              </div>
              <div v-else-if="isMe(seat.player.name) && game.myCards.length > 0" class="seat-cards">
                <PlayingCard v-for="card in game.myCards" :key="card" :card="card" />
              </div>
              <div v-else-if="!isMe(seat.player.name) && isInHand(seat.player)" class="seat-cards">
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

      <!-- Mes actions (un spectateur n'en a pas) -->
      <ActionBar v-if="!game.spectator" />

      <!-- Historique -->
      <section class="log-panel" aria-labelledby="log-title">
        <h2 id="log-title">{{ game.bigScreen ? 'Dernières actions' : 'Historique' }}</h2>
        <ol class="log" aria-live="polite">
          <li v-for="(line, index) in visibleLog" :key="index">{{ line }}</li>
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
        <h2 id="game-over-title">{{ gameOverTitle }}</h2>
        <p v-if="game.local">Partie locale : elle ne compte pas au classement.</p>
        <p v-else-if="game.endReason === 'ABANDONED'">
          Trop de joueurs sont partis : cette partie ne compte pas au classement.
        </p>
        <ol>
          <li v-for="standing in game.standings" :key="standing.player">
            <strong>{{ standing.player }}</strong> : {{ standing.chips }} jetons
          </li>
        </ol>
        <button v-if="game.bigScreen" type="button" class="primary" @click="newLocalGame">
          Nouvelle partie
        </button>
        <button v-else type="button" class="primary" @click="backToLobby">Retour au lobby</button>
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

/*
  La manette : la page fait AU MOINS la hauteur de l'écran (elle peut défiler au-delà),
  et la manette (flex: 1) remplit l'espace : sa barre d'actions descend tout en bas.
  ".game.game--controller" (deux classes) l'emporte sur ".game" seul, y compris sur
  la règle "display: block" du téléphone plus bas.
*/
.game.game--controller {
  display: flex;
  height: auto;
  min-height: calc(100dvh - var(--app-bar-height));
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

.leave-button {
  margin-top: var(--space-2);
}

/* Plus spécifique que ".game-header p" : le badge garde une couleur bien contrastée */
.game-header .spectator-badge {
  display: inline-block;
  margin-top: var(--space-2);
  padding: 0.1rem 0.6rem;
  border: 1px solid var(--brass);
  border-radius: 999px;
  color: var(--paper);
  font-weight: 700;
}

.back-link {
  display: block;
  margin-top: var(--space-2);
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

/*
  Joueur déconnecté, absent ou parti : bordure en pointillés, et sa présence écrite
  en toutes lettres (la couleur ou la forme seule ne suffit pas). Pas d'opacité ici :
  le texte doit garder un contraste suffisant (RGAA, critère 3.2).
*/
.seat--away {
  border-style: dashed;
  border-color: var(--muted);
}

.seat-presence {
  color: var(--paper);
  font-size: 0.85em;
  font-weight: 700;
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
   Barre d'actions (composant ActionBar) : son apparence est dans le composant,
   la page ne décide que de sa place dans la grille.
   Ça marche malgré "scoped" : Vue applique aussi les styles du parent
   à l'élément racine d'un composant enfant (ici la <section class="action-bar">).
   ------------------------------------------------------------------ */
.action-bar {
  grid-area: actions;
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
   Grand écran : la télé du mode local, regardée depuis le canapé.
   Pas de barre en haut (App.vue), donc toute la hauteur de l'écran.
   Mêmes règles qu'au-dessus, avec des maximums bien plus grands :
   les clamp(min, idéal, max) des places et des cartes plafonnaient à une taille d'ordinateur.
   ------------------------------------------------------------------ */
.game--big {
  width: calc(100% - 2rem);
  height: 100dvh;
}

/* Pas de barre d'actions : la table occupe toute la hauteur de la colonne de gauche */
.game--big .game-layout {
  grid-template-columns: minmax(0, 1fr) clamp(16rem, 22vw, 28rem);
  grid-template-areas:
    'table header'
    'table log'
    'table log';
}

.game--big .table-area {
  padding: clamp(2.5rem, 9vh, 7rem) clamp(4rem, 6vw, 8rem);
}

.game--big .game-header h1 {
  font-size: clamp(1.4rem, 2.4vw, 2.6rem);
}

.game--big .game-header p {
  font-size: clamp(1rem, 1.5vw, 1.6rem);
}

.fullscreen-button {
  margin-top: var(--space-2);
}

.game--big .center {
  --card-w: clamp(2rem, 7.5cqw, 8rem);
}

.game--big .pot {
  font-size: clamp(0.8rem, 2.2cqw, 2.2rem);
}

.game--big .pot strong {
  font-size: clamp(1rem, 3.6cqw, 3.6rem);
}

.game--big .bet {
  font-size: clamp(0.75rem, 2cqw, 2rem);
}

.game--big .seat {
  width: clamp(6.5rem, 17cqw, 18rem);
  font-size: clamp(0.75rem, 2.1cqw, 2.1rem);
  --card-w: clamp(1.3rem, 3.4cqw, 3.6rem);
}

.game--big .log-panel h2 {
  font-size: clamp(1.05rem, 1.5vw, 1.7rem);
}

.game--big .log {
  font-size: clamp(0.875rem, 1.25vw, 1.4rem);
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

  /*
    ".game-layout .action-bar" et pas ".action-bar" seul : le composant déclare
    position: relative avec la même force. Un sélecteur plus précis l'emporte
    toujours, quel que soit l'ordre de chargement des deux feuilles de style.
  */
  .game-layout .action-bar {
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
