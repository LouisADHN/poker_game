<script setup lang="ts">
import { ref } from 'vue'
import { ApiError } from '@/api/http'
import { useLobbyStore } from '@/stores/lobby'

// 1. Le store du lobby : c'est lui qui appelle le serveur
const lobby = useLobbyStore()

// Les champs du formulaire, avec des valeurs par défaut (type déduit : number)
const name = ref('')
const smallBlind = ref(10)
const bigBlind = ref(20)
const startingChips = ref(1000)
const maxPlayers = ref(6)

const error = ref<string | null>(null)
const loading = ref(false)
const fieldErrors = ref<Record<string, string>>({})

// 3. async : la fonction peut attendre la réponse du serveur avec await
async function submit() {
  error.value = null
  fieldErrors.value = {}
  loading.value = true

  // 4. Même schéma que la page d'inscription
  try {
    // L'objet doit correspondre exactement à l'interface CreateTableRequest :
    // un champ oublié ou mal orthographié est souligné en rouge par TypeScript
    await lobby.create({
      name: name.value,
      smallBlind: smallBlind.value,
      bigBlind: bigBlind.value,
      startingChips: startingChips.value,
      maxPlayers: maxPlayers.value,
    })
    // Rien d'autre à faire : la page du lobby affiche la nouvelle table
    // et masque ce formulaire automatiquement
  } catch (e) {
    if (e instanceof ApiError) {
      error.value = e.message // ex : "La small blind ne peut pas dépasser la big blind"
      fieldErrors.value = e.fieldErrors // ex : { name: "Le nom ne peut pas être vide" }
    } else {
      error.value = 'Impossible de contacter le serveur. Réessaie dans un instant.'
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <form @submit.prevent="submit">
    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <label for="table-name">Nom de la table</label>
    <input id="table-name" v-model.trim="name" type="text" maxlength="30" required />
    <p v-if="fieldErrors.name" class="field-error">{{ fieldErrors.name }}</p>

    <label for="small-blind">Small blind</label>
    <input id="small-blind" v-model.number="smallBlind" type="number" min="1" required />
    <p v-if="fieldErrors.smallBlind" class="field-error">{{ fieldErrors.smallBlind }}</p>

    <label for="big-blind">Big blind</label>
    <input id="big-blind" v-model.number="bigBlind" type="number" min="1" required />
    <p v-if="fieldErrors.bigBlind" class="field-error">{{ fieldErrors.bigBlind }}</p>

    <label for="starting-chips">Tapis de départ</label>
    <input id="starting-chips" v-model.number="startingChips" type="number" min="1" required />
    <p v-if="fieldErrors.startingChips" class="field-error">{{ fieldErrors.startingChips }}</p>

    <label for="max-players">Nombre de joueurs maximum</label>
    <input id="max-players" v-model.number="maxPlayers" type="number" min="2" max="10" required />
    <p v-if="fieldErrors.maxPlayers" class="field-error">{{ fieldErrors.maxPlayers }}</p>

    <button type="submit" :disabled="loading">
      {{ loading ? 'Création…' : 'Créer la table' }}
    </button>
  </form>
</template>
