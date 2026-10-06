import { describe, expect, it } from 'vitest'
import type { ActionPayload } from '@/api/types.ts'
import { describeAction } from '../game'

describe('describeAction', () => {
  // it.each : le même test, exécuté pour chaque ligne du tableau
  it.each<[ActionPayload, string]>([
    [{ type: 'FOLD' }, 'se couche'],
    [{ type: 'CHECK' }, 'checke'],
    [{ type: 'CALL' }, 'suit'],
    [{ type: 'BET', total: 50 }, 'mise à 50'],
    [{ type: 'RAISE', total: 120 }, 'relance à 120'],
    [{ type: 'ALL_IN' }, 'fait tapis'],
  ])('%o -> "%s"', (action, expected) => {
    expect(describeAction(action)).toBe(expected)
  })
})
