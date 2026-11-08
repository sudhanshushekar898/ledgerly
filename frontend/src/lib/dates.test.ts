import { describe, expect, it } from 'vitest'
import { dayKey, dayLabel, groupByDay } from './dates'

describe('day grouping', () => {
  const now = new Date(2026, 10, 8, 15, 0) // 8 Nov 2026, 15:00 local time

  it('labels today, yesterday and older days', () => {
    expect(dayLabel('2026-11-08', now)).toBe('Today')
    expect(dayLabel('2026-11-07', now)).toBe('Yesterday')
    expect(dayLabel('2026-11-01', now)).toMatch(/1 Nov,? 2026/)
  })

  it('groups consecutive items by local day and keeps their order', () => {
    const at = (day: number, hour: number) => new Date(2026, 10, day, hour).toISOString()
    const groups = groupByDay([{ createdAt: at(8, 12), id: 'c' }, { createdAt: at(8, 9), id: 'b' }, { createdAt: at(7, 20), id: 'a' }], now)

    expect(groups.map((g) => g.label)).toEqual(['Today', 'Yesterday'])
    expect(groups[0]?.items.map((i) => i.id)).toEqual(['c', 'b'])
    expect(dayKey(at(7, 20))).toBe('2026-11-07')
  })
})
