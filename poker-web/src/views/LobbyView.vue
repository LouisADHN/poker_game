<script setup lang="ts">

import {useAuthStore} from '@/stores/auth'
import {useRouter} from 'vue-router'

const auth = useAuthStore()
const router = useRouter()

function logout() {
  auth.logout()
  router.push({name: 'login'})
}

</script>

<template>
  <main>
    <h1>Lobby</h1>

    <h2>Bienvenue, {{ auth.user?.username }} !</h2>

    <button type="button" @click="logout">
      Se déconnecter
    </button>
  </main>
</template>
