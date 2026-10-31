<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useGameStore } from '@/stores/game'
import { useLocalPlayerStore } from '@/stores/localPlayer'

/**
 * Le bouton « Quitter la partie » : demande confirmation, prévient le serveur,
 * puis emmène le joueur ailleurs (le lobby pour un compte, /join pour un invité).
 *
 * Il décide lui-même s'il s'affiche : il n'a de sens que pour un joueur, pendant la partie.
 * Le parent ne choisit que l'endroit où il le pose.
 */
const router = useRouter()
const auth = useAuthStore()
const game = useGameStore()

/** Vrai pendant l'envoi de la demande : le bouton est désactivé, pas de double envoi */
const leaving = ref(false)

/** Une fois la table chargée, avant la fin, et seulement pour un joueur (pas un spectateur) */
const canLeave = computed(() => game.tableId !== null && game.standings === null && !game.spectator)

async function leave() {
  const confirmed = window.confirm(
    'Quitter la partie ? Tu ne pourras pas y revenir : jusqu’à la fin, ' +
      'le serveur jouera pour toi (check si possible, sinon couché).',
  )
  if (!confirmed) {
    return
  }

  leaving.value = true
  try {
    await game.leaveGame()
    if (auth.isGuest) {
      // Un invité n'existe que pour cette partie : il l'a quittée, on oublie tout.
      // Sinon, la page /join retrouverait sa table (toujours en cours) et le renverrait ici !
      useLocalPlayerStore().forget()
      auth.logout()
      await router.push({ name: 'join' })
    } else {
      await router.push({ name: 'lobby' })
    }
  } catch (e) {
    game.error = e instanceof ApiError ? e.message : 'Impossible de quitter la partie.'
  } finally {
    leaving.value = false
  }
}
</script>

<template>
  <button v-if="canLeave" type="button" class="leave-button" :disabled="leaving" @click="leave">
    {{ leaving ? 'Départ…' : 'Quitter la partie' }}
  </button>
</template>
