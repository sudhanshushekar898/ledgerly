import { Plus, Users } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Avatar, Card, EmptyState, Skeleton } from '../../components/ui'
import { useContacts } from './api'

/** People you recently paid or were paid by: one tap opens Send with the recipient filled in. */
export function QuickSend() {
  const contacts = useContacts()

  return (
    <Card eyebrow="Quick send" title="Recent people" className="h-full">
      {contacts.isPending ? (
        <div className="flex gap-4">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-16 w-14 rounded-xl" />
          ))}
        </div>
      ) : !contacts.data?.length ? (
        <EmptyState icon={Users} title="No contacts yet">
          People you pay, or who pay you, show up here.
        </EmptyState>
      ) : (
        <ul className="flex flex-wrap gap-x-3 gap-y-4" aria-label="Recent contacts">
          {contacts.data.map((contact) => (
            <li key={contact.email}>
              <Link
                to={`/send?to=${encodeURIComponent(contact.email)}`}
                aria-label={`Send money to ${contact.name}`}
                className="group flex w-16 flex-col items-center gap-1.5 rounded-xl p-1 transition hover:bg-white/[0.04]"
              >
                <Avatar name={contact.name} />
                <span className="w-full truncate text-center text-[11px] text-slate-400 group-hover:text-slate-200">
                  {contact.name.split(' ')[0]}
                </span>
              </Link>
            </li>
          ))}
          <li>
            <Link to="/send" className="group flex w-16 flex-col items-center gap-1.5 rounded-xl p-1 transition hover:bg-white/[0.04]">
              <span className="flex h-10 w-10 items-center justify-center rounded-full border border-dashed border-white/20 text-slate-500 group-hover:text-teal-300">
                <Plus className="h-4 w-4" aria-hidden />
              </span>
              <span className="text-[11px] text-slate-500">New</span>
            </Link>
          </li>
        </ul>
      )}
    </Card>
  )
}
