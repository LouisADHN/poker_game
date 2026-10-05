import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

// Déclare les informations qu'on peut placer dans "meta" sur chaque route, pour TypeScript
declare module 'vue-router' {
  interface RouteMeta {
    /** Page réservée aux utilisateurs connectés */
    requiresAuth?: boolean
    /** Page réservée aux visiteurs (connexion, inscription) */
    guestOnly?: boolean
    title?: string
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'lobby',
      component: () => import('@/views/LobbyView.vue'),
      meta: { requiresAuth: true, title: 'Lobby' },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { guestOnly: true, title: 'Connexion' },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
      meta: { guestOnly: true, title: 'Inscription' },
    },
    {
      path: '/tables/:id',
      name: 'game',
      component: () => import('@/views/GameTableView.vue'),
      meta: { requiresAuth: true, title: 'Partie en cours' },
    },
    {
      path: '/leaderboard',
      name: 'leaderboard',
      component: () => import('@/views/LeaderboardView.vue'),
      meta: { requiresAuth: true, title: 'Classement' },
    },
    {
      // Toute adresse inconnue renvoie au lobby
      path: '/:pathMatch(.*)*',
      redirect: { name: 'lobby' },
      meta: { title: 'Lobby'}
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

/** Après chaque changement de page : met à jour le titre de l'onglet */
router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · Tapis vert` : 'Tapis vert'
})

export default router
