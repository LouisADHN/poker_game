<script setup lang="ts">
import { computed } from 'vue'
import { encode } from 'uqr'

/**
 * Un QR code, dessiné en SVG.
 *
 * La bibliothèque uqr calcule seulement la grille : un tableau de cases noires (true) ou blanches (false).
 * Le dessin, c'est nous : un SVG s'agrandit sans flou, ce qui compte sur une télé vue de loin.
 */
const props = defineProps<{
  /** Le texte à encoder, ici l'adresse de la page pour rejoindre la partie */
  text: string
  /** Ce qu'annonce un lecteur d'écran à la place de l'image */
  label: string
}>()

/**
 * La grille, transformée en un seul tracé SVG : un carré de 1 × 1 par case noire.
 * computed : recalculé seulement si le texte change, pas à chaque affichage.
 */
const qr = computed(() => {
  // ecc 'M' : le code reste lisible même abîmé à 15 % (reflet sur l'écran…)
  // border 4 : la marge blanche que la norme demande autour du code, pour que les téléphones le repèrent
  const { size, data } = encode(props.text, { ecc: 'M', border: 4 })

  let path = ''
  data.forEach((row, y) => {
    row.forEach((dark, x) => {
      if (dark) {
        // M = aller en (x, y), h1 = 1 vers la droite, v1 = 1 vers le bas, h-1 = 1 vers la gauche, z = fermer
        path += `M${x} ${y}h1v1h-1z`
      }
    })
  })
  return { size, path }
})
</script>

<template>
  <!-- role="img" + aria-label : le SVG est annoncé comme UNE image, pas comme un dessin à explorer -->
  <svg
    class="qr-code"
    role="img"
    :aria-label="label"
    :viewBox="`0 0 ${qr.size} ${qr.size}`"
    shape-rendering="crispEdges"
  >
    <!-- Noir sur blanc, quel que soit le thème : les téléphones lisent mal un QR code inversé -->
    <rect :width="qr.size" :height="qr.size" fill="#fff" />
    <path :d="qr.path" fill="#000" />
  </svg>
</template>

<style scoped>
.qr-code {
  display: block;
  width: 100%;
  height: auto;
  border-radius: var(--radius-s);
}
</style>
