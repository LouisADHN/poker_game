<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useLocalPlayerStore } from '@/stores/localPlayer'

/**
 * La page ouverte par le QR code de la télé, sur le téléphone d'un joueur.
 * 1. il saisit son pseudo (invité) ou rejoint avec son compte ;
 * 2. il attend le lancement, en voyant arriver les autres ;
 * 3. la partie démarre : il passe sur la table.
 */
const auth = useAuthStore()
const player = useLocalPlayerStore()
const route = useRoute()
const router = useRouter()

/**
 * Le code, prérempli depuis l'adresse /join/K7QM2X (celle du QR code).
 * route.params.code est une string, ou une liste, ou absent : on ne garde qu'une string.
 */
const code = ref(typeof route.params.code === 'string' ? route.params.code.toUpperCase() : '')
const username = ref('')

const loading = ref(true)
const submitting = ref(false)
const error = ref<string | null>(null)
const fieldErrors = ref<Record<string, string>>({})

/** Connecté avec un vrai compte : on lui propose de jouer avec, plutôt qu'en invité */
const hasAccount = computed(() => auth.isAuthenticated() && !auth.isGuest)

/** Pour revenir ici, avec le code, après s'être connecté */
const loginLink = computed(() => ({
  name: 'login',
  query: { redirect: code.value ? `/join/${code.value}` : '/join' },
}))

onMounted(async () => {
  try {
    await player.resume()
  } catch {
    error.value = 'Impossible de retrouver ta table.'
  } finally {
    loading.value = false
  }
})

onUnmounted(() => {
  player.stopLive()
})

// La télé lance la partie : je passe sur la table
watch(
  () => player.table?.status,
  (status) => {
    if (status === 'PLAYING' && player.table && player.isSeated) {
      router.push({ name: 'game', params: { id: player.table.id } })
    }
  },
)

/** Le code tel qu'on l'envoie : sans espaces, en majuscules (le serveur le fait aussi) */
function normalizedCode(): string {
  return code.value.trim().toUpperCase()
}

async function submit() {
  error.value = null
  fieldErrors.value = {}
  submitting.value = true
  try {
    if (hasAccount.value) {
      await player.joinWithAccount(normalizedCode())
    } else {
      await player.joinAsGuest(normalizedCode(), username.value)
    }
  } catch (e) {
    if (e instanceof ApiError) {
      error.value = e.message // ex : "Aucune partie en attente avec ce code"
      fieldErrors.value = e.fieldErrors // ex : { username: "Le pseudo doit faire…" }
    } else {
      error.value = 'Impossible de contacter le serveur. Réessaie dans un instant.'
    }
  } finally {
    submitting.value = false
  }
}

async function leave() {
  error.value = null
  try {
    await player.leave()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : 'Impossible de quitter la table.'
  }
}
</script>

<template>
  <main class="auth-page join">
    <h1>Rejoindre une partie</h1>

    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <p v-if="loading" class="hint">Chargement…</p>

    <!-- Assis : la salle d'attente -->
    <section v-else-if="player.table && player.isSeated" aria-labelledby="waiting-title">
      <h2 id="waiting-title">{{ player.table.name }}</h2>
      <p class="hint">Tu es assis. La partie sera lancée depuis l'écran partagé.</p>

      <ul class="player-list" aria-label="Joueurs assis" aria-live="polite">
        <li
          v-for="seat in player.table.players"
          :key="seat.userId"
          :class="{ me: seat.userId === auth.user?.id }"
        >
          {{ seat.username }}
          <span v-if="seat.userId === auth.user?.id" class="visually-hidden">(toi)</span>
        </li>
      </ul>

      <button type="button" @click="leave">Quitter la table</button>
    </section>

    <!-- Pas encore assis : le formulaire -->
    <form v-else @submit.prevent="submit">
      <label for="join-code">Code affiché sur l'écran</label>
      <!--
        autocapitalize : le clavier du téléphone propose directement les majuscules ;
        spellcheck / autocomplete off : pas de correction automatique sur un code
      -->
      <input
        id="join-code"
        v-model.trim="code"
        type="text"
        class="code-input"
        maxlength="6"
        autocapitalize="characters"
        autocomplete="off"
        spellcheck="false"
        required
      />

      <template v-if="hasAccount">
        <p class="hint">
          Tu joueras avec ton compte <strong>{{ auth.user?.username }}</strong
          >.
        </p>
      </template>
      <template v-else>
        <label for="guest-name">Ton pseudo</label>
        <input
          id="guest-name"
          v-model.trim="username"
          type="text"
          minlength="3"
          maxlength="20"
          autocomplete="nickname"
          aria-describedby="guest-name-help"
          required
        />
        <p id="guest-name-help" class="hint">
          3 à 20 caractères : lettres, chiffres, tirets. Pas besoin de compte.
        </p>
        <p v-if="fieldErrors.username" class="field-error">{{ fieldErrors.username }}</p>
      </template>

      <button type="submit" :disabled="submitting">
        {{ submitting ? 'Connexion à la table…' : 'Rejoindre' }}
      </button>

      <p v-if="!hasAccount" class="hint">
        Tu as un compte ? <RouterLink :to="loginLink">Connecte-toi</RouterLink> pour jouer avec.
      </p>
    </form>
  </main>
</template>

<style scoped>
.hint {
  color: var(--muted);
}

/* Le code : gros et espacé, facile à recopier depuis la télé */
.code-input {
  font-family: var(--font-display);
  font-size: 1.6rem;
  font-weight: 800;
  letter-spacing: 0.2em;
  text-transform: uppercase;
}

.player-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin: var(--space-4) 0 var(--space-6);
  padding: 0;
  list-style: none;
}

.player-list li {
  padding: 0.3rem 0.75rem;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--ink-raised);
  font-weight: 700;
}

/* Moi : entouré en laiton */
.player-list li.me {
  border-color: var(--brass);
}
</style>
