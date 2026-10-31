import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

// Déclare les informations qu'on peut placer dans "meta" sur chaque route, pour TypeScript
declare module 'vue-router' {
  interface RouteMeta {
    /** Page réservée aux utilisateurs connectés */
    requiresAuth?: boolean
    /** Page réservée aux visiteurs (connexion, inscription). Rien à voir avec les invités du mode local ! */
    guestOnly?: boolean
    /** Page réservée aux comptes : un invité du mode local n'y a pas accès (le serveur lui refuserait tout) */
    requiresAccount?: boolean
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
      meta: { requiresAuth: true, requiresAccount: true, title: 'Lobby' },
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
      // L'écran partagé du mode local (la télé) : créer la partie, afficher le code, lancer
      path: '/local',
      name: 'local-host',
      component: () => import('@/views/LocalHostView.vue'),
      meta: { requiresAuth: true, requiresAccount: true, title: 'Partie locale' },
    },
    {
      // La page du QR code, sur les téléphones : ouverte à tous, avec ou sans compte.
      // « :code? » : le code est facultatif dans l'adresse (/join tout court, pour le saisir à la main)
      path: '/join/:code?',
      name: 'join',
      component: () => import('@/views/JoinView.vue'),
      meta: { title: 'Rejoindre une partie' },
    },
    {
      path: '/leaderboard',
      name: 'leaderboard',
      component: () => import('@/views/LeaderboardView.vue'),
      meta: { requiresAuth: true, requiresAccount: true, title: 'Classement' },
    },
    {
      // Toute adresse inconnue renvoie au lobby
      path: '/:pathMatch(.*)*',
      redirect: { name: 'lobby' },
      meta: { title: 'Lobby' },
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
  // Un invité du mode local n'a accès qu'à sa partie : ailleurs, on le ramène à la page pour rejoindre
  if (to.meta.requiresAccount && auth.isGuest) {
    return { name: 'join' }
  }
  // Un invité PEUT ouvrir la connexion : il a peut-être un compte, finalement
  if (to.meta.guestOnly && auth.isAuthenticated() && !auth.isGuest) {
    return { name: 'lobby' }
  }
})

/** Après chaque changement de page : met à jour le titre de l'onglet */
router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · Tapis vert` : 'Tapis vert'
})

export default router
