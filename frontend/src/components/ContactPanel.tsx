import { useQuery } from '@tanstack/react-query'
import { EnvelopeSimple, Phone, UserCircle } from '@phosphor-icons/react'
import { Alert, AlertDescription } from '#/components/ui/alert.tsx'
import { api } from '../lib/api'
import { queryKeys } from '../lib/query'
import type { Claim, ContactResponse } from '../lib/types'

interface ContactPanelProps {
  claim: Claim
  userId: number
}

export function ContactPanel({ claim, userId }: ContactPanelProps) {
  const isShareable = claim.status === 'APPROVED' || claim.status === 'COMPLETED'
  const { data, isLoading, isError, error } = useQuery({
    queryKey: queryKeys.accountContact(userId, claim.id),
    queryFn: ({ signal }) => api<ContactResponse>(`/claims/${claim.id}/contact`, { signal }),
    enabled: isShareable,
    gcTime: 0,
    staleTime: 0,
  })

  if (!isShareable) return null
  if (isLoading) {
    return <p className="m-0 text-xs text-muted-foreground">Loading contact details…</p>
  }
  if (isError || !data) {
    return (
      <Alert variant="destructive">
        <AlertDescription>
          {(error as Error | undefined)?.message ?? 'Contact details are unavailable.'}
        </AlertDescription>
      </Alert>
    )
  }

  return (
    <section
      aria-label="Pickup contacts"
      className="grid gap-3 rounded-2xl bg-fill p-4 outline-1 -outline-offset-1 outline-hairline"
    >
      <p className="m-0 text-[0.6875rem] font-semibold tracking-[0.08em] text-muted-foreground uppercase">
        Pickup contacts
      </p>
      {[
        ['Reporter', data.reporter],
        ['Claimant', data.claimant],
      ].map(([label, person]) => {
        const participant = person as ContactResponse['reporter']
        return (
          <div key={label as string} className="grid gap-1">
            <div className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
              <UserCircle aria-hidden="true" size={16} className="text-muted-foreground" />
              {label as string}: {participant.name}
            </div>
            <a
              className="inline-flex items-center gap-1.5 text-[0.8125rem]"
              href={`mailto:${participant.email}`}
            >
              <EnvelopeSimple aria-hidden="true" size={14} />
              {participant.email}
            </a>
            {participant.phone && (
              <a
                className="inline-flex items-center gap-1.5 text-[0.8125rem]"
                href={`tel:${participant.phone}`}
              >
                <Phone aria-hidden="true" size={14} />
                {participant.phone}
              </a>
            )}
          </div>
        )
      })}
    </section>
  )
}
