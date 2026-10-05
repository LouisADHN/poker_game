<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useLobbyStore } from '@/stores/lobby'

const auth = useAuthStore()
const lobby = useLobbyStore()
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

async function logout() {
  lobby.stopLive()
  auth.logout()
  await router.push({ name: 'login' })
}
</script>

<template>
  <!-- Barre commune, affichée uniquement quand on est connecté -->
  <header v-if="auth.user && auth.isAuthenticated()" class="app-bar">
    <RouterLink :to="{ name: 'lobby' }" class="brand">Tapis vert</RouterLink>

    <nav aria-label="Navigation principale">
      <!-- RouterLink ajoute aria-current="page" sur le lien de la page affichée -->
      <RouterLink :to="{ name: 'lobby' }">Tables</RouterLink>
      <RouterLink :to="{ name: 'leaderboard' }">Classement</RouterLink>
    </nav>

    <div class="account">
      <span>{{ auth.user.username }}</span>
      <button type="button" @click="logout">Se déconnecter</button>
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
