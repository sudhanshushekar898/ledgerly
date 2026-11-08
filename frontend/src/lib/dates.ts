const pad = (n: number) => String(n).padStart(2, '0')

/** The viewer's local calendar day of an instant, as YYYY-MM-DD. */
export function dayKey(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

const DAY_LABEL = new Intl.DateTimeFormat('en-IN', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
const TIME = new Intl.DateTimeFormat('en-IN', { hour: 'numeric', minute: '2-digit' })
const DATE_TIME = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'medium' })
const SHORT_DATE = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short' })

/** "Today", "Yesterday" or "Sat, 7 Nov 2026". */
export function dayLabel(key: string, now: Date = new Date()): string {
  const today = dayKey(now.toISOString())
  const yesterday = dayKey(new Date(now.getTime() - 86_400_000).toISOString())
  if (key === today) return 'Today'
  if (key === yesterday) return 'Yesterday'
  const [y, m, d] = key.split('-').map(Number)
  return DAY_LABEL.format(new Date(y ?? 1970, (m ?? 1) - 1, d ?? 1))
}

/** Consecutive items grouped by local day, keeping the incoming order (newest first). */
export function groupByDay<T extends { createdAt: string }>(items: T[], now: Date = new Date()) {
  const groups: { key: string; label: string; items: T[] }[] = []
  for (const item of items) {
    const key = dayKey(item.createdAt)
    const last = groups[groups.length - 1]
    if (last && last.key === key) last.items.push(item)
    else groups.push({ key, label: dayLabel(key, now), items: [item] })
  }
  return groups
}

export const formatTime = (iso: string) => TIME.format(new Date(iso))
export const formatDateTime = (iso: string) => DATE_TIME.format(new Date(iso))
/** "8 Nov" from a YYYY-MM-DD calendar date (no time zone shift). */
export function formatShortDate(date: string): string {
  const [y, m, d] = date.split('-').map(Number)
  return SHORT_DATE.format(new Date(y ?? 1970, (m ?? 1) - 1, d ?? 1))
}
