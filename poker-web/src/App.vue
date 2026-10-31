<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useGameStore } from '@/stores/game'
import { useLobbyStore } from '@/stores/lobby'

const auth = useAuthStore()
const lobby = useLobbyStore()
const game = useGameStore()
const router = useRouter()
const route = useRoute()

onMounted(async () => {
  // Attend que le routeur ait déterminé la première page affichée
  await router.isReady()

  // Le compte existe-t-il toujours ? Sinon, verifySession déconnecte l'utilisateur
  await auth.verifySession()
  if (!auth.isAuthenticated() && route.meta.requiresAuth) {
    await router.push({ name: 'login' })
  }
})

/**
 * Sur la télé, pendant une partie locale, pas de barre : tout l'écran est pour la table,
 * et personne n'y clique (on regarde depuis le canapé).
 */
const hideAppBar = computed(() => route.name === 'game' && game.bigScreen)

async function logout() {
  const wasGuest = auth.isGuest // à lire AVANT logout(), qui efface l'utilisateur
  lobby.stopLive()
  auth.logout()
  await router.push({ name: wasGuest ? 'join' : 'login' })
}
</script>

<template>
  <!-- Barre commune, affichée uniquement quand on est connecté (et pas sur la télé en partie) -->
  <header v-if="auth.user && auth.isAuthenticated() && !hideAppBar" class="app-bar">
    <RouterLink :to="{ name: auth.isGuest ? 'join' : 'lobby' }" class="brand"
      >Tapis vert</RouterLink
    >

    <!-- Un invité n'a accès à aucune de ces pages : pas de navigation pour lui -->
    <nav v-if="!auth.isGuest" aria-label="Navigation principale">
      <!-- RouterLink ajoute aria-current="page" sur le lien de la page affichée -->
      <RouterLink :to="{ name: 'lobby' }">Tables</RouterLink>
      <RouterLink :to="{ name: 'local-host' }">Partie locale</RouterLink>
      <RouterLink :to="{ name: 'leaderboard' }">Classement</RouterLink>
    </nav>

    <div class="account">
      <span>{{ auth.user.username }}</span>
      <!--
        Pas de bouton pour un invité : se déconnecter le laisserait assis à la table, sans moyen
        d'y revenir. Il part avec « Quitter la table » ou « Quitter la partie », qui le libèrent aussi.
      -->
      <button v-if="!auth.isGuest" type="button" @click="logout">Se déconnecter</button>
    </div>
  </header>

  <RouterView />
</template>

<style scoped>
.app-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) var(--space-6);
  height: var(--app-bar-height);
  padding: 0 max(1rem, (100% - 64rem) / 2);
  border-bottom: 1px solid var(--line);
}

.brand {
  color: var(--paper);
  font-family: var(--font-display);
  font-size: 1.3rem;
  font-weight: 800;
  text-decoration: none;
}

nav {
  display: flex;
  gap: var(--space-4);
}

nav a {
  color: var(--muted);
  font-weight: 700;
  text-decoration: none;
}

nav a:hover {
  color: var(--paper);
}

/* La page actuelle : soulignée en laiton */
nav a[aria-current='page'] {
  color: var(--paper);
  text-decoration: underline;
  text-decoration-color: var(--brass);
  text-decoration-thickness: 2px;
}

.account {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-left: auto;
  color: var(--muted);
}

.account button {
  min-height: 2.25rem;
  padding: 0.3rem 0.8rem;
  font-size: 0.9rem;
}

@media (max-width: 52rem) {
  .app-bar {
    height: auto;
    padding-block: var(--space-3);
  }
}
</style>
