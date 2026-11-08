import { describe, expect, it } from 'vitest'
import { amountSchema, formatInr, toPaise } from './money'

describe('formatInr', () => {
  it.each([
    ['1500.00', '₹1,500.00'],
    ['12345678.90', '₹1,23,45,678.90'], // Indian grouping: 1 crore 23 lakh ...
    ['0.00', '₹0.00'],
    ['5', '₹5.00'],
    ['7.5', '₹7.50'],
    ['-250.50', '−₹250.50'],
  ])('formats %s as %s', (input, expected) => {
    expect(formatInr(input)).toBe(expected)
  })

  it('marks money in with a plus sign when asked', () => {
    expect(formatInr('250.00', { signed: true })).toBe('+₹250.00')
  })

  it('stays exact beyond what a JavaScript number can hold', () => {
    // Number('9007199254740993') === 9007199254740992: a float would silently change the amount.
    const formatted = formatInr('9007199254740993.01')
    expect(formatted.replace(/\D/g, '')).toBe('900719925474099301')
  })

  it('returns unparseable input unchanged instead of guessing', () => {
    expect(formatInr('abc')).toBe('abc')
  })
})

describe('toPaise', () => {
  it.each([
    ['250', 25000n],
    ['250.5', 25050n],
    ['250.05', 25005n],
    ['0.01', 1n],
    [' 10 ', 1000n],
  ])('%s is %s paise', (input, expected) => {
    expect(toPaise(input)).toBe(expected)
  })

  it.each(['', '1.234', '-1', '1e3', '1,000', '₹5', '.5', '12345678901234'])('rejects %j', (input) => {
    expect(toPaise(input)).toBeNull()
  })
})

describe('amountSchema', () => {
  const schema = amountSchema('25000.00', 'send')
  const message = (value: string) => schema.safeParse(value).error?.issues[0]?.message

  it('accepts amounts up to the limit', () => {
    expect(schema.safeParse('25000').success).toBe(true)
    expect(schema.safeParse('0.01').success).toBe(true)
  })

  it('explains each kind of invalid amount', () => {
    expect(message('')).toBe('Enter an amount')
    expect(message('12.345')).toBe('Enter an amount like 250 or 250.50')
    expect(message('0')).toBe('Amount must be more than ₹0')
    expect(message('25000.01')).toBe('You can send at most ₹25,000.00 at a time')
  })
})
