import { createFileRoute, Link, Navigate } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { CaretRight } from '@phosphor-icons/react'
import { Badge } from '#/components/ui/badge.tsx'
import { Button } from '#/components/ui/button.tsx'
import { Skeleton } from '#/components/ui/skeleton.tsx'
import { ClaimBadge, StatusBadge } from '../components/StatusBadge'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { formatDate, formatDateTime, timeAgo } from '../lib/format'
import { queryKeys } from '../lib/query'
import type { ReactNode } from 'react'
import type { Claim, Item } from '../lib/types'

export const Route = createFileRoute('/my')({ component: MyItemsPage })

function MyItemsPage() {
  const { user, hydrated } = useAuth()

  if (hydrated && !user) {
    return <Navigate to="/login" search={{ redirect: '/my' }} />
  }

  return (
    <main className="demo-page">
      <p className="island-kicker mb-3">Your account</p>
      <h1 className="demo-title mb-10">My activity</h1>
      {user ? (
        <div className="grid grid-cols-1 items-start gap-10 lg:grid-cols-2">
          <MyReports userId={user.id} />
          <MyClaims userId={user.id} />
        </div>
      ) : (
        <div className="item-list">
          <p className="group-note">Loading your account…</p>
        </div>
      )}
    </main>
  )
}

function GroupState({
  isLoading,
  isError,
  error,
  onRetry,
  empty,
}: {
  isLoading: boolean
  isError: boolean
  error?: string | null
  onRetry: () => void
  empty: ReactNode
}) {
  if (isLoading) {
    return (
      <div className="item-list" aria-label="Loading">
        {Array.from({ length: 3 }).map((_, index) => (
          <div className="item-row" key={index} aria-hidden="true">
            <div className="w-full">
              <Skeleton className="mb-2 h-3 w-1/3" />
              <Skeleton className="h-3 w-1/2" />
            </div>
          </div>
        ))}
      </div>
    )
  }
  if (isError) {
    return (
      <div className="item-list">
        <div className="group-note flex items-center gap-3">
          <span className="text-destructive-foreground">{error ?? 'Couldn’t load this list.'}</span>
          <Button size="sm" variant="outline" onClick={onRetry}>Retry</Button>
        </div>
      </div>
    )
  }
  return <>{empty}</>
}

function MyReports({ userId }: { userId: number }) {
  const { data: items, isLoading, isError, refetch } = useQuery({
    queryKey: queryKeys.accountReports(userId),
    queryFn: ({ signal }) => api<Item[]>('/items/me/reported', { signal }),
  })

  return (
    <section aria-labelledby="my-reports-heading">
      <h2 className="group-label" id="my-reports-heading">Reports</h2>
      <GroupState
        isLoading={isLoading}
        isError={isError}
        error="Couldn't load your reports."
        onRetry={() => void refetch()}
        empty={
          <div className="item-list">
            {items && items.length === 0 && (
              <p className="group-note">
                Nothing reported yet.{' '}
                <Link to="/report" className="font-medium">Report an item</Link>
              </p>
            )}
            {items?.map((item) => (
              <Link
                key={item.id}
                to="/items/$id"
                params={{ id: String(item.id) }}
                className="item-row"
              >
                <div className="min-w-0 flex-1">
                  <h3 className="item-row-title truncate">{item.title}</h3>
                  <p className="row-sub truncate">
                    {item.type === 'LOST' ? 'Lost' : 'Found'} · {item.location} ·{' '}
                    {formatDate(item.dateOccurred)}
                  </p>
                </div>
                {item.pendingClaims > 0 && item.status === 'OPEN' && (
                  <Badge variant="info">{item.pendingClaims} pending</Badge>
                )}
                <StatusBadge status={item.status} />
                <CaretRight aria-hidden="true" size={16} weight="bold" className="item-row-chevron" />
              </Link>
            ))}
          </div>
        }
      />
    </section>
  )
}

function claimSubline(claim: Claim): string {
  if (claim.status === 'COMPLETED') {
    const when = formatDateTime(claim.completedAt ?? claim.decidedAt ?? claim.createdAt)
    return claim.itemType === 'FOUND' ? `Picked up ${when}` : `Resolved ${formatDate(claim.completedAt ?? claim.decidedAt)}`
  }
  return `${claim.itemType === 'LOST' ? 'Lost item' : 'Found item'} · sent ${timeAgo(claim.createdAt)}`
}

function MyClaims({ userId }: { userId: number }) {
  const { data: claims, isLoading, isError, refetch } = useQuery({
    queryKey: queryKeys.accountClaims(userId),
    queryFn: ({ signal }) => api<Claim[]>('/me/claims', { signal }),
  })

  return (
    <section aria-labelledby="my-claims-heading">
      <h2 className="group-label" id="my-claims-heading">Responses</h2>
      <GroupState
        isLoading={isLoading}
        isError={isError}
        error="Couldn't load your responses."
        onRetry={() => void refetch()}
        empty={
          <div className="item-list">
            {claims && claims.length === 0 && (
              <p className="group-note">
                Nothing sent yet.{' '}
                <Link to="/" className="font-medium">Browse reports</Link>
              </p>
            )}
            {claims?.map((claim) => (
              <Link
                key={claim.id}
                to="/claims/$claimId"
                params={{ claimId: String(claim.id) }}
                search={{ itemId: claim.itemId }}
                className="item-row"
              >
                <div className="min-w-0 flex-1">
                  <h3 className="item-row-title truncate">{claim.itemTitle}</h3>
                  <p className="row-sub truncate">{claimSubline(claim)}</p>
                </div>
                <ClaimBadge status={claim.status} />
                <CaretRight aria-hidden="true" size={16} weight="bold" className="item-row-chevron" />
              </Link>
            ))}
          </div>
        }
      />
    </section>
  )
}
