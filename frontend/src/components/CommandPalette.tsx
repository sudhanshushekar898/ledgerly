import type { LucideIcon } from 'lucide-react'
import { Activity, ArrowUpRight, Eye, FlaskConical, LayoutGrid, LogOut, Plus, ReceiptText, Search } from 'lucide-react'
import { useEffect, useMemo, useState, useSyncExternalStore } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../features/auth/useAuth'
import { balancePrivacy, useBalanceHidden } from '../lib/privacy'
import { Kbd } from './ui'

let open = false
const listeners = new Set<() => void>()
function setOpen(value: boolean) {
  open = value
  listeners.forEach((listener) => listener())
}

/** Opened with Ctrl/⌘+K or the search button in the sidebar. */
export const palette = {
  subscribe(listener: () => void) {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },
  getSnapshot: () => open,
  open: () => setOpen(true),
  close: () => setOpen(false),
}

interface Command {
  id: string
  group: 'Navigate' | 'Engineering' | 'Actions'
  label: string
  keywords: string
  icon: LucideIcon
  run: () => void
}

export function CommandPalette({ customer }: { customer: boolean }) {
  const isOpen = useSyncExternalStore(palette.subscribe, palette.getSnapshot)
  const navigate = useNavigate()
  const { logout } = useAuth()
  const hidden = useBalanceHidden()
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        if (palette.getSnapshot()) palette.close()
        else palette.open()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const commands = useMemo<Command[]>(() => {
    const go = (to: string) => () => navigate(to)
    const wallet: Command[] = customer
      ? [
          { id: 'overview', group: 'Navigate', label: 'Overview', keywords: 'home dashboard balance', icon: LayoutGrid, run: go('/') },
          { id: 'send', group: 'Navigate', label: 'Send money', keywords: 'pay transfer', icon: ArrowUpRight, run: go('/send') },
          { id: 'add', group: 'Navigate', label: 'Add money', keywords: 'top up deposit', icon: Plus, run: go('/add-money') },
          { id: 'activity', group: 'Navigate', label: 'Activity', keywords: 'statement history transactions', icon: ReceiptText, run: go('/activity') },
          { id: 'lab', group: 'Engineering', label: 'Resilience lab', keywords: 'idempotency concurrency race test', icon: FlaskConical, run: go('/lab') },
          { id: 'privacy', group: 'Actions', label: hidden ? 'Show balance' : 'Hide balance', keywords: 'privacy mask', icon: Eye, run: balancePrivacy.toggle },
        ]
      : []
    return [
      ...wallet,
      { id: 'inspector', group: 'Engineering', label: 'API inspector', keywords: 'network requests latency debug', icon: Activity, run: go('/inspector') },
      { id: 'signout', group: 'Actions', label: 'Sign out', keywords: 'logout exit', icon: LogOut, run: () => void logout() },
    ]
  }, [customer, hidden, logout, navigate])

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase()
    return q ? commands.filter((c) => `${c.label} ${c.keywords}`.toLowerCase().includes(q)) : commands
  }, [commands, query])

  if (!isOpen) return null

  const close = () => {
    palette.close()
    setQuery('')
    setActive(0)
  }
  const run = (command: Command | undefined) => {
    if (!command) return
    close()
    command.run()
  }
  const onKeyDown = (event: ReactKeyboardEvent) => {
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setActive((i) => Math.min(i + 1, visible.length - 1))
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setActive((i) => Math.max(i - 1, 0))
    } else if (event.key === 'Enter') {
      event.preventDefault()
      run(visible[active])
    } else if (event.key === 'Escape') {
      close()
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center bg-ink-950/70 px-4 pt-[15vh] backdrop-blur-sm" onMouseDown={close}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Command palette"
        className="w-full max-w-lg animate-fade-up overflow-hidden rounded-2xl bg-ink-850 ring-1 ring-white/10 shadow-2xl shadow-black/60"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="flex items-center gap-3 border-b border-white/[0.06] px-4">
          <Search className="h-4 w-4 text-slate-500" aria-hidden />
          <input
            autoFocus
            role="combobox"
            aria-expanded="true"
            aria-controls="palette-options"
            aria-activedescendant={visible[active] ? `palette-${visible[active].id}` : undefined}
            aria-label="Search commands"
            value={query}
            onChange={(event) => {
              setQuery(event.target.value)
              setActive(0)
            }}
            onKeyDown={onKeyDown}
            placeholder="Type a command or search…"
            className="h-12 flex-1 bg-transparent text-sm text-slate-100 placeholder:text-slate-500 focus:outline-none"
          />
          <Kbd>esc</Kbd>
        </div>
        <ul id="palette-options" role="listbox" aria-label="Commands" className="max-h-80 overflow-y-auto p-2">
          {visible.length === 0 && <li className="px-3 py-6 text-center text-sm text-slate-500">No matching command</li>}
          {visible.map((command, index) => (
            <li
              key={command.id}
              id={`palette-${command.id}`}
              role="option"
              aria-selected={index === active}
              onMouseEnter={() => setActive(index)}
              onClick={() => run(command)}
              className={`flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2.5 text-sm ${index === active ? 'bg-teal-400/10 text-white' : 'text-slate-300'}`}
            >
              <command.icon className={`h-4 w-4 ${index === active ? 'text-teal-300' : 'text-slate-500'}`} aria-hidden />
              <span className="flex-1">{command.label}</span>
              <span className="font-mono text-[10px] uppercase tracking-wider text-slate-600">{command.group}</span>
            </li>
          ))}
        </ul>
        <div className="flex items-center gap-4 border-t border-white/[0.06] px-4 py-2 text-[11px] text-slate-500">
          <span className="flex items-center gap-1.5"><Kbd>↑</Kbd><Kbd>↓</Kbd> navigate</span>
          <span className="flex items-center gap-1.5"><Kbd>↵</Kbd> run</span>
        </div>
      </div>
    </div>
  )
}
