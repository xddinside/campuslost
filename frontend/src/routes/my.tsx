import { createFileRoute, Link, Navigate } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Badge } from '#/components/ui/badge.tsx'
import { Button } from '#/components/ui/button.tsx'
import { Card, CardContent } from '#/components/ui/card.tsx'
import { ClaimBadge, StatusBadge } from '../components/StatusBadge'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { formatDate, timeAgo } from '../lib/format'
import { invalidateWorkflow, queryKeys } from '../lib/query'
import type { Claim, Item } from '../lib/types'

export const Route = createFileRoute('/my')({ component: MyItemsPage })

function MyItemsPage() {
  const { user, hydrated } = useAuth()

  if (hydrated && !user) {
    return <Navigate to="/login" search={{ redirect: '/my' }} />
  }

  return (
    <main className="demo-page">
      <p className="island-kicker mb-2">Your account</p>
      <h1 className="demo-title mb-8">My activity</h1>
      {user ? (
        <div className="grid gap-8 lg:grid-cols-2">
          <MyReports userId={user.id} />
          <MyClaims userId={user.id} />
        </div>
      ) : (
        <Card className="rounded-xl shadow-none ring-border">
          <CardContent className="text-sm text-muted-foreground">Loading your account…</CardContent>
        </Card>
      )}
    </main>
  )
}

function MyReports({ userId }: { userId: number }) {
  const queryClient = useQueryClient()
  const { data: items, isLoading } = useQuery({
    queryKey: queryKeys.accountReports(userId),
    queryFn: () => api<Item[]>('/items/me/reported'),
  })

  const resolve = useMutation({
    mutationFn: (id: number) =>
      api<Item>(`/items/${id}/status`, {
        method: 'PATCH',
        body: JSON.stringify({ status: 'RESOLVED' }),
      }),
    onSuccess: async () => { await invalidateWorkflow(queryClient) },
  })

  return (
    <section>
      <h2 className="demo-section-title mb-3">Reports I posted</h2>
      <div className="grid gap-3">
        {isLoading && (
          <Card size="sm" className="rounded-xl shadow-none ring-border">
            <CardContent className="text-sm text-muted-foreground">Loading…</CardContent>
          </Card>
        )}
        {items && items.length === 0 && (
          <Card size="sm" className="rounded-xl shadow-none ring-border">
            <CardContent className="text-sm text-muted-foreground">
              You haven't reported anything yet.{' '}
              <Link to="/report" className="font-bold no-underline">Report an item →</Link>
            </CardContent>
          </Card>
        )}
        {items?.map((item) => (
          <Card key={item.id} size="sm" className="rounded-xl shadow-none ring-border">
            <CardContent>
              <div className="flex flex-wrap items-center gap-2">
                <Link
                  to="/items/$id"
                  params={{ id: String(item.id) }}
                  className="text-base font-bold no-underline"
                >
                  {item.title}
                </Link>
                <StatusBadge status={item.status} />
                {item.pendingClaims > 0 && item.status === 'OPEN' && (
                  <Badge variant="secondary">
                    {item.pendingClaims} pending response{item.pendingClaims > 1 ? 's' : ''}
                  </Badge>
                )}
              </div>
              <p className="m-0 mt-1.5 text-xs text-[var(--muted-foreground)]">
                {item.type === 'LOST' ? 'Lost' : 'Found'} · {item.location} ·{' '}
                {formatDate(item.dateOccurred)} · posted {timeAgo(item.createdAt)}
              </p>

              {item.status === 'RESERVED' && (
                <p className="m-0 mt-2 text-xs text-muted-foreground">
                  An approved pickup is in progress. Release it on the report to edit or reopen.
                </p>
              )}
              {item.status === 'CLOSED' && (
                <p className="m-0 mt-2 text-xs text-muted-foreground">
                  Closed reports are final.
                </p>
              )}

              <div className="mt-3 flex flex-wrap gap-2">
                {(item.status === 'OPEN' || item.status === 'RESOLVED') && (
                  <Button
                    variant="outline"
                    size="sm"
                    className="rounded-lg"
                    render={<Link to="/items/$id/edit" params={{ id: String(item.id) }} />}
                  >
                    Edit
                  </Button>
                )}
                {item.status === 'OPEN' && (
                  <Button
                    variant="outline"
                    size="sm"
                    className="rounded-lg"
                    disabled={resolve.isPending}
                    onClick={() => resolve.mutate(item.id)}
                  >
                    Mark as resolved
                  </Button>
                )}
              </div>
            </CardContent>
          </Card>
        ))}
      </div>
    </section>
  )
}

function MyClaims({ userId }: { userId: number }) {
  const { data: claims, isLoading } = useQuery({
    queryKey: queryKeys.accountClaims(userId),
    queryFn: () => api<Claim[]>('/me/claims'),
  })

  return (
    <section>
      <h2 className="demo-section-title mb-3">Responses I sent</h2>
      <div className="grid gap-3">
        {isLoading && (
          <Card size="sm" className="rounded-xl shadow-none ring-border">
            <CardContent className="text-sm text-muted-foreground">Loading…</CardContent>
          </Card>
        )}
        {claims && claims.length === 0 && (
          <Card size="sm" className="rounded-xl shadow-none ring-border">
            <CardContent className="text-sm text-muted-foreground">
              You haven't responded to anything yet.{' '}
              <Link to="/" className="font-bold no-underline">Browse reports →</Link>
            </CardContent>
          </Card>
        )}
        {claims?.map((claim) => (
          <Card key={claim.id} size="sm" className="rounded-xl shadow-none ring-border">
            <CardContent>
              <div className="flex flex-wrap items-center gap-2">
                <Link
                  to="/items/$id"
                  params={{ id: String(claim.itemId) }}
                  className="text-base font-bold no-underline"
                >
                  {claim.itemTitle}
                </Link>
                <ClaimBadge status={claim.status} />
                <span className="ml-auto text-xs text-[var(--muted-foreground)]">
                  {timeAgo(claim.createdAt)}
                </span>
              </div>
              <p className="m-0 mt-1.5 line-clamp-2 text-sm text-[var(--muted-foreground)]">
                {claim.message}
              </p>
              <p className="m-0 mt-2 text-xs text-muted-foreground">
                {claim.status === 'PENDING' && 'Awaiting the reporter’s review.'}
                {claim.status === 'APPROVED' &&
                  'Approved — open the report for contact details and pickup steps.'}
                {claim.status === 'COMPLETED' &&
                  (claim.itemType === 'FOUND'
                    ? 'Pickup complete.'
                    : 'The reporter marked this resolved.')}
                {claim.status === 'REJECTED' && 'The reporter declined this response.'}
                {claim.status === 'CANCELLED' && 'This response was cancelled.'}
              </p>
              {claim.lostReportId && (
                <Link
                  to="/items/$id"
                  params={{ id: String(claim.lostReportId) }}
                  className="mt-1 inline-block text-xs font-bold no-underline"
                >
                  Linked lost report →
                </Link>
              )}
            </CardContent>
          </Card>
        ))}
      </div>
    </section>
  )
}
