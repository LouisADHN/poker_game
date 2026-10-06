<script setup lang="ts">
// Le code de cette balise s'exécute une fois, à la création de la page.
// Toutes les variables et fonctions déclarées ici sont utilisables dans le template.

import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/api/http'

// --- Les outils dont la page a besoin
const auth = useAuthStore() // l'état de connexion partagé (le store Pinia)
const router = useRouter() // pour changer de page

// --- L'état du formulaire (réactif : l'affichage suit automatiquement)
const username = ref('') // type déduit : string
const password = ref('')
const passwordConfirm = ref('')
const error = ref<string | null>(null) // texte de l'erreur, ou null s'il n'y en a pas
const loading = ref(false) // vrai pendant l'appel au serveur
const fieldErrors = ref<Record<string, string>>({})

// --- La soumission du formulaire
async function submit() {
  error.value = null
  fieldErrors.value = {}

  if (password.value !== passwordConfirm.value) {
    fieldErrors.value = { password: 'Les mots de passe ne correspondent pas.' }
    return
  }

  loading.value = true

  try {
    // On attend la réponse du serveur ; si elle échoue, on saute directement au catch
    await auth.register(username.value, password.value)

    // Retour à la page demandée avant la connexion, sinon au lobby.
    // route.query.redirect peut être un texte, une liste ou absent :
    // le typeof vérifie que c'est bien un texte.
    // Le startsWith('/') empêche de rediriger vers un autre site
    // (une faille classique appelée "redirection ouverte").
    await router.push({ name: 'lobby' })
  } catch (e) {
    // e peut être n'importe quoi : instanceof vérifie qu'il s'agit d'une erreur de l'API
    if (e instanceof ApiError) {
      error.value = e.message // ex : "Pseudo ou mot de passe incorrect"
      fieldErrors.value = e.fieldErrors
    } else {
      error.value = 'Impossible de contacter le serveur. Réessaie dans un instant.'
    }
  } finally {
    // Exécuté dans tous les cas, succès ou erreur
    loading.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <h1>Créer mon compte</h1>

    <!-- .prevent empêche le navigateur de recharger la page à la soumission -->
    <form @submit.prevent="submit">
      <!-- Affiché seulement s'il y a une erreur ; role="alert" la fait lire par les lecteurs d'écran -->
      <p v-if="error" class="error" role="alert">{{ error }}</p>

      <label for="username">Pseudo</label>
      <!-- v-model relie le champ à la variable dans les deux sens ; .trim retire les espaces -->
      <input id="username" v-model.trim="username" type="text" autocomplete="username" required />
      <p v-if="fieldErrors.username" class="field-error">{{ fieldErrors.username }}</p>

      <label for="password">Mot de passe</label>
      <input
        id="password"
        v-model="password"
        type="password"
        autocomplete="new-password"
        required
      />
      <p v-if="fieldErrors.password" class="field-error">{{ fieldErrors.password }}</p>

      <label for="passwordConfirm">Confirmer le mot de passe</label>
      <input
        id="passwordConfirm"
        v-model="passwordConfirm"
        type="password"
        autocomplete="new-password"
        required
      />
      <p v-if="fieldErrors.passwordConfirm" class="field-error">
        {{ fieldErrors.passwordConfirm }}
      </p>

      <!-- :disabled est lié à la variable loading : bouton grisé pendant l'appel -->
      <button type="submit" :disabled="loading">
        {{ loading ? 'Création…' : 'Créer mon compte' }}
      </button>
    </form>

    <p>
      Déjà un compte ?
      <RouterLink :to="{ name: 'login' }">Connecte-toi</RouterLink>
    </p>
  </main>
</template>
