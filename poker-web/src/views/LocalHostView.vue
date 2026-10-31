<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import { useLocalHostStore } from '@/stores/localHost'
import CreateTableForm from '@/components/CreateTableForm.vue'
import QrCode from '@/components/QrCode.vue'

/**
 * L'écran de la télé, en mode local.
 * 1. l'hôte crée la partie (formulaire) ;
 * 2. la télé affiche le QR code et le code, et les joueurs qui arrivent ;
 * 3. l'hôte lance la partie : la télé passe sur la table, en spectateur.
 */
const local = useLocalHostStore()
const router = useRouter()

/** Vrai tant qu'on ne sait pas s'il existe déjà une table (page rechargée) */
const loading = ref(true)
const starting = ref(false)
const error = ref<string | null>(null)

onMounted(async () => {
  try {
    await local.resume()
  } catch {
    error.value = 'Impossible de retrouver la partie en attente.'
  } finally {
    loading.value = false
  }
})

onUnmounted(() => {
  local.stopLive()
})

// La partie démarre : la télé affiche la table. L'hôte n'y est pas assis,
// le serveur le traite donc en spectateur : il ne voit aucune carte privée.
watch(
  () => local.table?.status,
  (status) => {
    if (status === 'PLAYING' && local.table) {
      router.push({ name: 'game', params: { id: local.table.id } })
    }
  },
)

/** L'adresse à taper à la main, sans « https:// » : plus courte à lire de loin */
const joinAddress = computed(() => `${window.location.host}/join`)

/**
 * Le code lettre par lettre pour un lecteur d'écran : « K 7 Q M 2 X ».
 * Collé, « K7QM2X » serait lu comme un mot imprononçable.
 */
const spelledCode = computed(() => local.joinCode?.split('').join(' ') ?? '')

async function start() {
  error.value = null
  starting.value = true
  try {
    await local.start()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : 'Impossible de contacter le serveur.'
  } finally {
    starting.value = false
  }
}
</script>

<template>
  <main class="local">
    <h1>Partie locale</h1>

    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <p v-if="loading" class="hint">Chargement…</p>

    <!-- 1. Pas encore de partie : le formulaire -->
    <section v-else-if="!local.table" class="panel create" aria-labelledby="create-title">
      <h2 id="create-title">Créer la partie</h2>
      <p class="hint">
        Cet écran affichera la table pour tout le monde. Chacun jouera depuis son téléphone, avec ou
        sans compte.
      </p>
      <CreateTableForm :create="local.create" />
    </section>

    <!-- 2. La salle d'attente -->
    <div v-else class="waiting-room">
      <section class="panel join" aria-labelledby="join-title">
        <h2 id="join-title">Rejoindre « {{ local.table.name }} »</h2>

        <QrCode
          v-if="local.joinUrl"
          :text="local.joinUrl"
          label="QR code à scanner avec un téléphone pour rejoindre la partie"
        />

        <p class="join-address">
          Ou ouvre <strong>{{ joinAddress }}</strong> et saisis le code :
        </p>
        <!-- Deux versions du code : l'une pour les yeux, l'autre pour les lecteurs d'écran.
             (aria-label ne convient pas : il est ignoré sur un simple paragraphe) -->
        <p class="join-code">
          <span aria-hidden="true">{{ local.joinCode }}</span>
          <span class="visually-hidden">Code : {{ spelledCode }}</span>
        </p>
      </section>

      <section class="panel players" aria-labelledby="players-title">
        <h2 id="players-title">
          Joueurs
          <span class="count">
            {{ local.table.players.length }}/{{ local.table.settings.maxPlayers }}
          </span>
        </h2>

        <!-- aria-live : un lecteur d'écran annonce chaque joueur qui arrive -->
        <ul class="player-list" aria-live="polite">
          <li v-for="player in local.table.players" :key="player.userId">
            {{ player.username }}
          </li>
        </ul>
        <p v-if="local.table.players.length === 0" class="hint">
          Personne pour l'instant. Scannez le QR code !
        </p>

        <button
          v-if="local.canStart"
          type="button"
          class="primary start"
          :disabled="starting"
          @click="start"
        >
          {{ starting ? 'Lancement…' : 'Lancer la partie' }}
        </button>
        <p v-else class="hint">Il faut au moins 2 joueurs pour lancer la partie.</p>
      </section>
    </div>
  </main>
</template>

<style scoped>
.panel {
  padding: var(--space-6);
  border: 1px solid var(--line);
  border-radius: var(--radius-m);
  background: var(--ink-raised);
}

.create {
  max-width: 28rem;
}

.hint {
  color: var(--muted);
}

/* Ordinateur / télé : le QR code à gauche, les joueurs à droite */
.waiting-room {
  display: grid;
  grid-template-columns: minmax(0, 24rem) minmax(0, 1fr);
  align-items: start;
  gap: var(--space-8);
}

.join {
  border-color: var(--brass);
  text-align: center;
}

.join-address {
  margin: var(--space-4) 0 var(--space-1);
  overflow-wrap: anywhere;
}

/* Le code : énorme et espacé, lisible depuis le canapé */
.join-code {
  margin: 0;
  color: var(--brass);
  font-family: var(--font-display);
  font-size: clamp(2.25rem, 5vw, 3.25rem); /* au plus grand, il tient encore dans le panneau */
  font-weight: 800;
  letter-spacing: 0.15em;
  font-variant-numeric: tabular-nums;
}

.count {
  color: var(--muted);
  font-weight: 400;
}

.player-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin: 0 0 var(--space-6);
  padding: 0;
  list-style: none;
}

.player-list li {
  padding: 0.5rem 1.1rem;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--ink);
  font-size: 1.4rem;
  font-weight: 700;
}

.start {
  font-size: 1.2rem;
}

/* Téléphone : les deux panneaux s'empilent */
@media (max-width: 52rem) {
  .waiting-room {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
