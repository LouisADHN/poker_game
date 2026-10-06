import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import PlayingCard from '../PlayingCard.vue'

/**
 * mount() crée le composant dans un faux navigateur (jsdom),
 * avec les props données, et permet d'inspecter le HTML produit.
 */
describe('PlayingCard', () => {
  it('affiche le rang et le symbole de la couleur', () => {
    const wrapper = mount(PlayingCard, { props: { card: 'As' } })

    expect(wrapper.text()).toContain('A')
    expect(wrapper.text()).toContain('♠')
  })

  it('affiche "10" pour la notation courte "T"', () => {
    const wrapper = mount(PlayingCard, { props: { card: 'Th' } })

    expect(wrapper.text()).toContain('10')
    expect(wrapper.text()).toContain('♥')
  })

  it('donne un nom complet aux lecteurs d’écran', () => {
    expect(mount(PlayingCard, { props: { card: 'As' } }).attributes('aria-label')).toBe(
      'As de pique',
    )
    expect(mount(PlayingCard, { props: { card: 'Qd' } }).attributes('aria-label')).toBe(
      'Dame de carreau',
    )
    expect(mount(PlayingCard, { props: { card: '7c' } }).attributes('aria-label')).toBe(
      '7 de trèfle',
    )
  })

  it('affiche les cœurs et les carreaux en rouge, pas les piques ni les trèfles', () => {
    expect(mount(PlayingCard, { props: { card: 'Kh' } }).classes()).toContain('red')
    expect(mount(PlayingCard, { props: { card: 'Kd' } }).classes()).toContain('red')
    expect(mount(PlayingCard, { props: { card: 'Ks' } }).classes()).not.toContain('red')
    expect(mount(PlayingCard, { props: { card: 'Kc' } }).classes()).not.toContain('red')
  })

  it('affiche le dos d’une carte cachée, sans révéler de valeur', () => {
    const wrapper = mount(PlayingCard, { props: { hidden: true } })

    expect(wrapper.classes()).toContain('back')
    expect(wrapper.text()).toBe('')
    expect(wrapper.attributes('aria-label')).toBe('Carte cachée')
  })
})
