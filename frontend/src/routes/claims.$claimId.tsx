import { createFileRoute, Link, Navigate } from '@tanstack/react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from '@phosphor-icons/react'
import { Alert, AlertDescription } from '#/components/ui/alert.tsx'
import { Button } from '#/components/ui/button.tsx'
import { Card, CardContent } from '#/components/ui/card.tsx'
import { ContactPanel } from '../components/ContactPanel'
import { ClaimBadge } from '../components/StatusBadge'
import { ClaimEvidence } from '../features/ownership'
import { HandoverPanel } from '../features/handover'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { timeAgo } from '../lib/format'
import { invalidateWorkflow, queryKeys } from '../lib/query'
import type { Claim } from '../lib/types'

interface ClaimSearch {
  itemId?: number
}

export const Route = createFileRoute('/claims/$claimId')({
  validateSearch: (search: Record<string, unknown>): ClaimSearch => {
    const parsed = typeof search.itemId === 'number' ? search.itemId : Number(search.itemId)
    return Number.isInteger(parsed) && parsed > 0 ? { itemId: parsed } : {}
  },
  component: ClaimHistoryPage,
})

/**
 * Authorized claim history, independent of the report's public availability.
 * A claimant's claim comes from /me/claims; a reviewer or admin reads the
 * preserved history through /items/{id}/claims, which stays reachable after
 * the report is soft-deleted.
 */
function ClaimHistoryPage() {
  const { claimId } = Route.useParams()
  const { itemId } = Route.useSearch()
  const { user, hydrated } = useAuth()

  const mine = useQuery({
    queryKey: queryKeys.accountClaims(user?.id ?? 0),
    queryFn: ({ signal }) => api<Claim[]>('/me/claims', { signal }),
    enabled: hydrated && !!user,
  })
  const reviewers = useQuery({
    queryKey: queryKeys.accountItemClaims(user?.id ?? 0, itemId ?? 0),
    queryFn: ({ signal }) => api<Claim[]>(`/items/${itemId}/claims`, { signal }),
    enabled: hydrated && !!user && itemId !== undefined,
    retry: false,
  })

  if (hydrated && !user) {
    return <Navigate to="/login" search={{ redirect: `/claims/${claimId}` }} />
  }

  const id = Number(claimId)
  const claim =
    mine.data?.find((c) => c.id === id) ??
    reviewers.data?.find((c) => c.id === id)

  if (mine.isLoading || (claim === undefined && reviewers.isLoading)) {
    return (
      <main className="demo-page">
        <Card className="rounded-xl shadow-none ring-border">
          <CardContent className="text-sm text-muted-foreground">Loading the claim…</CardContent>
        </Card>
      </main>
    )
  }

  if (claim === undefined) {
    const failed = mine.isError || reviewers.isError
    return (
      <main className="demo-page">
        <Alert variant={failed ? 'destructive' : 'default'}>
          <AlertDescription>
            {failed
              ? 'Couldn’t load this claim. Try again.'
              : 'This claim is not available to your account.'}
          </AlertDescription>
        </Alert>
        <div className="mt-4 flex gap-2">
          {failed && (
            <Button
              variant="outline"
              onClick={() => {
                void mine.refetch()
                void reviewers.refetch()
              }}
            >
              Retry
            </Button>
          )}
          <Button render={<Link to="/" />} variant="outline">Back to browse</Button>
        </div>
      </main>
    )
  }

  return (
    <main className="demo-page item-detail-page">
      <Link to="/my" className="detail-back-link">
        <ArrowLeft aria-hidden="true" size={18} weight="bold" />
        Back to my activity
      </Link>
      <ClaimHistoryCard claim={claim} userId={user!.id} />
    </main>
  )
}

function ClaimHistoryCard({ claim, userId }: { claim: Claim; userId: number }) {
  const queryClient = useQueryClient()
  const noun = claim.itemType === 'LOST' ? 'Response' : 'Claim'
  const heading =
    claim.status === 'PENDING'
      ? `${noun} sent`
      : claim.status === 'APPROVED'
        ? `${noun} approved`
        : claim.status === 'COMPLETED'
          ? `${noun} completed`
          : claim.status === 'REJECTED'
            ? `${noun} declined`
            : `${noun} cancelled`

  const detail =
    claim.status === 'PENDING'
      ? 'The reporter hasn’t reviewed it yet.'
      : claim.status === 'APPROVED'
        ? claim.itemType === 'FOUND'
          ? 'Approved. Use the contact details below to arrange pickup.'
          : 'Approved. The reporter marked this resolved.'
        : claim.status === 'COMPLETED'
          ? claim.itemType === 'FOUND'
            ? 'This pickup is complete.'
            : 'Resolved by the reporter.'
          : 'This response is no longer active.'

  return (
    <Card className="existing-claim-panel rounded-xl shadow-none ring-border">
      <CardContent>
        <div className="existing-claim-heading">
          <h2>{heading}</h2>
          <ClaimBadge status={claim.status} />
        </div>
        <p className="m-0 text-sm text-[var(--muted-foreground)]">
          {noun} on “{claim.itemTitle}” · {timeAgo(claim.createdAt)}
        </p>
        <Link
          to="/items/$id"
          params={{ id: String(claim.itemId) }}
          className="mt-1 inline-block text-xs font-bold no-underline"
        >
          View report →
        </Link>
        <p>{detail}</p>
        <blockquote>{claim.message}</blockquote>
        <div className="mt-3 grid gap-3">
          <ContactPanel claim={claim} userId={userId} />
          {claim.itemType === 'FOUND'
            && (claim.status === 'APPROVED' || claim.status === 'COMPLETED') && (
              <HandoverPanel
                claim={claim}
                onChanged={() => { void invalidateWorkflow(queryClient) }}
              />
            )}
          <ClaimEvidence claimId={claim.id} />
        </div>
      </CardContent>
    </Card>
  )
}
