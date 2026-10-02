import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

// Déclare les informations qu'on peut placer dans "meta" sur chaque route, pour TypeScript
declare module 'vue-router' {
  interface RouteMeta {
    /** Page réservée aux utilisateurs connectés */
    requiresAuth?: boolean
    /** Page réservée aux visiteurs (connexion, inscription) */
    guestOnly?: boolean
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'lobby',
      component: () => import('@/views/LobbyView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { guestOnly: true },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
      meta: { guestOnly: true },
    },
    {
      // Toute adresse inconnue renvoie au lobby
      path: '/:pathMatch(.*)*',
      redirect: { name: 'lobby' },
    },
  ],
})

/** Avant chaque changement de page : vérifie que l'utilisateur a le droit d'y accéder. */
router.beforeEach((to) => {
  const auth = useAuthStore()

  if (to.meta.requiresAuth && !auth.isAuthenticated()) {
    // On retient la page demandée pour y revenir après la connexion
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.guestOnly && auth.isAuthenticated()) {
    return { name: 'lobby' }
  }
})

export default router
