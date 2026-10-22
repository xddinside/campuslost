import { createFileRoute, Link, Navigate } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Badge } from '#/components/ui/badge.tsx'
import { Button } from '#/components/ui/button.tsx'
import { Card, CardContent } from '#/components/ui/card.tsx'
import { ClaimBadge, StatusBadge } from '../components/StatusBadge'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { formatDate, timeAgo } from '../lib/format'
import type { Claim, Item } from '../lib/types'

export const Route = createFileRoute('/my')({ component: MyItemsPage })

function MyItemsPage() {
  const { user, hydrated } = useAuth()

  if (hydrated && !user) {
    return <Navigate to="/login" />
  }

  return (
    <main className="demo-page">
      <p className="island-kicker mb-2">Your account</p>
      <h1 className="demo-title mb-8">My items</h1>
      <div className="grid gap-8 lg:grid-cols-2">
        <MyReports />
        <MyClaims />
      </div>
    </main>
  )
}

function MyReports() {
  const queryClient = useQueryClient()
  const { data: items, isLoading } = useQuery({
    queryKey: ['my-items'],
    queryFn: () => api<Item[]>('/items/me/reported'),
  })

  const resolve = useMutation({
    mutationFn: (id: number) =>
      api<Item>(`/items/${id}/status`, { method: 'PATCH', body: JSON.stringify({ status: 'RESOLVED' }) }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['my-items'] })
    },
  })

  return (
    <section>
      <h2 className="demo-section-title mb-3">Items I reported</h2>
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
              {item.pendingClaims > 0 && (
                <Badge variant="secondary">
                  {item.pendingClaims} claim{item.pendingClaims > 1 ? 's' : ''}
                </Badge>
              )}
            </div>
            <p className="m-0 mt-1.5 text-xs text-[var(--muted-foreground)]">
              {item.type === 'LOST' ? 'Lost' : 'Found'} · {item.location} ·{' '}
              {formatDate(item.dateOccurred)} · posted {timeAgo(item.createdAt)}
            </p>
            {item.status === 'OPEN' && (
              <Button
                variant="outline"
                size="sm"
                className="mt-3 rounded-lg"
                disabled={resolve.isPending}
                onClick={() => resolve.mutate(item.id)}
              >
                Mark as resolved
              </Button>
            )}
            </CardContent>
          </Card>
        ))}
      </div>
    </section>
  )
}

function MyClaims() {
  const { data: claims, isLoading } = useQuery({
    queryKey: ['my-claims'],
    queryFn: () => api<Claim[]>('/me/claims'),
  })

  return (
    <section>
      <h2 className="demo-section-title mb-3">Claims I made</h2>
      <div className="grid gap-3">
        {isLoading && (
          <Card size="sm" className="rounded-xl shadow-none ring-border">
            <CardContent className="text-sm text-muted-foreground">Loading…</CardContent>
          </Card>
        )}
        {claims && claims.length === 0 && (
          <Card size="sm" className="rounded-xl shadow-none ring-border">
            <CardContent className="text-sm text-muted-foreground">
              You haven't claimed anything yet.{' '}
              <Link to="/" className="font-bold no-underline">Browse items →</Link>
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
            {claim.status === 'APPROVED' && (
              <p className="m-0 mt-2 text-xs font-bold text-[var(--foreground)]">
                Approved! The reporter has been notified — check their contact info
                on the item page.
              </p>
            )}
            </CardContent>
          </Card>
        ))}
      </div>
    </section>
  )
}
