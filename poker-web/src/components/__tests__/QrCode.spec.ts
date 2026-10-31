import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { encode } from 'uqr'
import QrCode from '../QrCode.vue'

const URL_A = 'https://poker.louisadhn.fr/join/K7QM2X'
const URL_B = 'https://poker.louisadhn.fr/join/ABCDEF'

/** Nombre de cases noires de la grille calculée par uqr (avec les mêmes options que le composant) */
function darkModules(text: string): number {
  return encode(text, { ecc: 'M', border: 4 })
    .data.flat()
    .filter((dark) => dark).length
}

describe('QrCode', () => {
  it('est annoncé comme une seule image, avec son texte alternatif', () => {
    const wrapper = mount(QrCode, { props: { text: URL_A, label: 'QR code pour rejoindre' } })

    const svg = wrapper.find('svg')
    expect(svg.attributes('role')).toBe('img')
    expect(svg.attributes('aria-label')).toBe('QR code pour rejoindre')
  })

  it('dessine exactement un carré par case noire de la grille', () => {
    const wrapper = mount(QrCode, { props: { text: URL_A, label: 'QR' } })

    // Chaque carré du tracé commence par « M » (aller à…)
    const path = wrapper.find('path').attributes('d') ?? ''
    expect(path.split('M').length - 1).toBe(darkModules(URL_A))

    // La zone dessinée fait la taille de la grille
    const size = encode(URL_A, { ecc: 'M', border: 4 }).size
    expect(wrapper.find('svg').attributes('viewBox')).toBe(`0 0 ${size} ${size}`)
  })

  it('se redessine quand le texte change', async () => {
    const wrapper = mount(QrCode, { props: { text: URL_A, label: 'QR' } })
    const before = wrapper.find('path').attributes('d')

    await wrapper.setProps({ text: URL_B })

    expect(wrapper.find('path').attributes('d')).not.toBe(before)
  })
})
