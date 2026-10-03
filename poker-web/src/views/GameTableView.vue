<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiError } from '@/api/http'
import type { ActionType, TableView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useGameStore } from '@/stores/game'
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
    // L'identifiant vient de l'URL : /tables/3 -> "3", converti en nombre
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

// watch : réagit à chaque changement d'une valeur.
// Quand c'est à moi de jouer, on propose la relance minimum par défaut.
watch(
  () => game.turn,
  (turn) => {
    if (turn) {
      raiseTotal.value = turn.legalActions.minRaiseTo
    }
  },
)

/** Secondes restantes pour jouer, ou null si ce n'est pas mon tour */
const secondsLeft = computed(() =>
  game.turnDeadline === null ? null : Math.max(0, Math.ceil((game.turnDeadline - now.value) / 1000)),
)

/** "Miser" si personne n'a encore misé sur ce tour, sinon "Relancer" */
const raiseLabel = computed(() => (game.turn?.currentBet === 0 ? 'Miser' : 'Relancer'))

function isMe(name: string): boolean {
  return name === auth.user?.username
}

/** Un adversaire encore dans la main : on affiche le dos de ses deux cartes */
function showCardBacks(status: string): boolean {
  return game.handNumber > 0 && (status === 'ACTIVE' || status === 'ALL_IN')
}

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
  // On garde le montant entre les bornes autorisées, au cas où la saisie serait hors limites
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
    <header>
      <h1>{{ game.tableName || 'Table' }}</h1>
      <p v-if="game.handNumber > 0">Main n°{{ game.handNumber }}</p>
      <p v-else>La partie va commencer…</p>
    </header>

    <div v-if="loadError" class="error" role="alert">
      <p>{{ loadError }}</p>
      <button type="button" @click="backToLobby">Retour au lobby</button>
    </div>

    <p v-if="game.error" class="error" role="alert">{{ game.error }}</p>

    <!-- Le centre de la table : le board et le pot -->
    <section class="board" aria-label="Cartes communes et pot">
      <div class="cards">
        <PlayingCard v-for="card in game.board" :key="card" :card="card" />
      </div>
      <p class="pot">Pot : {{ game.pot }}</p>
    </section>

    <!-- Les joueurs -->
    <section aria-label="Joueurs">
      <ul class="seats">
        <li
          v-for="player in game.players"
          :key="player.name"
          class="seat"
          :class="{
            me: isMe(player.name),
            folded: player.status === 'FOLDED',
            out: player.status === 'OUT',
          }"
        >
          <p>
            <strong>{{ player.name }}</strong>
            <span v-if="player.name === game.dealer" class="dealer" title="Dealer">D</span>
          </p>
          <p>{{ player.chips }} jetons</p>
          <p v-if="player.streetBet > 0">Mise : {{ player.streetBet }}</p>
          <p v-if="player.status === 'ALL_IN'"><strong>Tapis !</strong></p>
          <p v-if="player.status === 'OUT'">Éliminé</p>
          <p v-if="player.lastAction" class="last-action">{{ player.lastAction }}</p>

          <!-- Au showdown : les cartes montrées et la main obtenue -->
          <div v-if="player.revealedCards" class="cards">
            <PlayingCard v-for="card in player.revealedCards" :key="card" :card="card" />
            <p>{{ player.category }}</p>
          </div>
          <!-- Sinon, pour un adversaire encore en jeu : le dos de ses cartes -->
          <div v-else-if="!isMe(player.name) && showCardBacks(player.status)" class="cards">
            <PlayingCard hidden />
            <PlayingCard hidden />
          </div>
        </li>
      </ul>
    </section>

    <!-- Mes cartes -->
    <section v-if="game.myCards.length > 0" aria-label="Mes cartes">
      <h2>Mes cartes</h2>
      <div class="cards">
        <PlayingCard v-for="card in game.myCards" :key="card" :card="card" />
      </div>
    </section>

    <!-- Mes actions : affichées seulement quand c'est mon tour -->
    <section v-if="game.turn" class="actions" aria-label="Mes actions">
      <h2>
        À toi de jouer !
        <span v-if="secondsLeft !== null">({{ secondsLeft }} s)</span>
      </h2>

      <button type="button" @click="play('FOLD')">Se coucher</button>

      <button v-if="game.turn.legalActions.canCheck" type="button" @click="play('CHECK')">
        Checker
      </button>
      <button v-else type="button" @click="play('CALL')">
        Suivre {{ game.turn.legalActions.toCall }}
      </button>

      <div v-if="game.turn.legalActions.canRaise" class="raise">
        <label for="raise-total">{{ raiseLabel }} à</label>
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
    </section>

    <!-- Historique de la partie -->
    <section aria-label="Historique">
      <h2>Historique</h2>
      <ol class="log">
        <li v-for="(line, index) in game.log" :key="index">{{ line }}</li>
      </ol>
    </section>

    <!-- Fin de partie -->
    <div v-if="game.standings" class="game-over" role="dialog" aria-labelledby="game-over-title">
      <h2 id="game-over-title">Partie terminée</h2>
      <ol>
        <li v-for="standing in game.standings" :key="standing.player">
          {{ standing.player }} : {{ standing.chips }} jetons
        </li>
      </ol>
      <button type="button" @click="backToLobby">Retour au lobby</button>
    </div>
  </main>
</template>
