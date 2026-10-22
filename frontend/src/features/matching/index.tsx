import { Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import {
  ArrowRight,
  CalendarBlank,
  CheckCircle,
  ImageSquare,
  MagnifyingGlass,
  MapPin,
  PauseCircle,
  Sparkle,
} from '@phosphor-icons/react'
import { Alert, AlertDescription, AlertTitle } from '#/components/ui/alert.tsx'
import { Button } from '#/components/ui/button.tsx'
import { Card, CardContent } from '#/components/ui/card.tsx'
import { Skeleton } from '#/components/ui/skeleton.tsx'
import type { MatchPanelProps } from '../../lib/feature-contracts'
import { ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { formatDate } from '../../lib/format'
import { queryKeys } from '../../lib/query'
import { fetchMatches } from './api'
import type { MatchView } from './types'

/**
 * Owner/admin suggestions for one report. The backend decides eligibility;
 * this panel only explains the committed snapshot it receives.
 */
export function MatchPanel({ item }: MatchPanelProps) {
  const { user, hydrated } = useAuth()
  const canView = Boolean(
    user && (user.role === 'ADMIN' || user.id === item.reporterId),
  )

  const isOpen = item.status === 'OPEN'
  const { data, isLoading, isError, error, refetch, isFetching } = useQuery({
    queryKey: queryKeys.account(user?.id ?? 0, 'item', item.id, 'matches'),
    queryFn: () => fetchMatches(item.id),
    enabled: hydrated && canView && isOpen,
    staleTime: 5_000,
    refetchInterval: 10_000,
    refetchOnWindowFocus: true,
  })

  if (!hydrated || !canView) {
    return null
  }

  const matches = data?.matches ?? []
  const sourceIsLost = item.type === 'LOST'

  return (
    <Card className="overflow-hidden rounded-xl shadow-none ring-border">
      <CardContent className="p-0">
        <header className="border-b border-border bg-muted/50 px-5 py-4">
          <div className="flex items-center gap-2">
            <Sparkle aria-hidden="true" size={18} weight="fill" className="text-[var(--primary)]" />
            <p className="island-kicker m-0">Possible matches</p>
            {isOpen && data && matches.length > 0 && (
              <span className="ml-auto rounded-full bg-card px-2 py-0.5 text-xs font-bold tabular-nums text-muted-foreground ring-1 ring-border">
                {matches.length} {matches.length === 1 ? 'match' : 'matches'}
              </span>
            )}
          </div>
          <h2 className="mt-1 text-base font-bold text-foreground">
            {sourceIsLost ? 'Found reports that may be yours' : 'Lost reports that may describe this item'}
          </h2>
          <p className="m-0 mt-1 text-xs text-muted-foreground">
            Ranked by a transparent similarity score across description, category,
            location and date. A score never approves a claim or proves ownership.
          </p>
        </header>

        <div className="grid gap-3 px-5 py-4">
          {isOpen && (
            <p className="sr-only" role="status" aria-live="polite" aria-atomic="true">
              {isLoading
                ? 'Looking for likely matches.'
                : isError
                  ? 'Could not load match suggestions.'
                  : matches.length === 0
                    ? 'No likely matches yet.'
                    : `${matches.length} likely ${matches.length === 1 ? 'match' : 'matches'} found.`}
            </p>
          )}

          {!isOpen && (
            <div className="grid justify-items-start gap-2 rounded-xl border border-dashed border-border bg-muted/30 p-4">
              <span className="grid size-9 place-items-center rounded-full bg-card text-muted-foreground ring-1 ring-border">
                <PauseCircle aria-hidden="true" size={18} />
              </span>
              <p className="m-0 text-sm font-bold text-foreground">Matching is paused</p>
              <p className="m-0 text-xs text-muted-foreground">
                This report is {item.status.toLowerCase()}. Suggestions only compare
                open reports, so there is nothing to show right now.
              </p>
            </div>
          )}

          {isOpen && isLoading && (
            <>
              <p className="m-0 text-sm text-muted-foreground">
                Looking for likely matches…
              </p>
              <Skeleton className="h-28 w-full rounded-xl" />
              <Skeleton className="h-28 w-full rounded-xl" />
            </>
          )}

          {isOpen && isError && (
            <div className="grid gap-3">
              <Alert variant="destructive">
                <AlertTitle>Couldn't load suggestions</AlertTitle>
                <AlertDescription>
                  {error instanceof ApiError
                    ? error.message
                    : 'The matching service did not respond. Your report is safe.'}
                </AlertDescription>
              </Alert>
              <Button
                variant="outline"
                size="sm"
                className="w-fit"
                disabled={isFetching}
                onClick={() => { void refetch() }}
              >
                {isFetching ? 'Trying again…' : 'Try again'}
              </Button>
            </div>
          )}

          {isOpen && !isLoading && !isError && matches.length === 0 && (
            <div className="grid justify-items-start gap-2 rounded-xl border border-dashed border-border bg-muted/30 p-4">
              <span className="grid size-9 place-items-center rounded-full bg-card text-muted-foreground ring-1 ring-border">
                <MagnifyingGlass aria-hidden="true" size={18} />
              </span>
              <p className="m-0 text-sm font-bold text-foreground">No likely matches yet</p>
              <p className="m-0 text-xs text-muted-foreground">
                {sourceIsLost
                  ? 'We compare every new found report with this description. If someone hands in something similar, it will appear here.'
                  : 'We compare every new lost report with this description. If someone describes this item, it will appear here.'}
              </p>
              <p className="m-0 text-xs text-muted-foreground">
                Only genuine text overlap can create a suggestion; category,
                location and date add explanation, never a match on their own.
              </p>
            </div>
          )}

          {isOpen && !isLoading && !isError && matches.map((match) => (
            <MatchCard
              key={`${match.lostItemId}-${match.foundItemId}`}
              match={match}
              sourceIsLost={sourceIsLost}
              sourceItemId={item.id}
            />
          ))}
        </div>
      </CardContent>
    </Card>
  )
}

function MatchCard({
  match,
  sourceIsLost,
  sourceItemId,
}: {
  match: MatchView
  sourceIsLost: boolean
  sourceItemId: number
}) {
  const candidate = match.item
  const tone = similarityTone(match.score)
  const search = sourceIsLost ? { lostReportId: sourceItemId } : {}

  return (
    <article className="grid gap-3 rounded-xl border border-border bg-card p-3.5 sm:grid-cols-[64px_minmax(0,1fr)]">
      <div className="size-16 overflow-hidden rounded-lg bg-muted ring-1 ring-border">
        {candidate.photoUrl ? (
          <img
            src={candidate.photoUrl}
            alt={`Photo of ${candidate.title}`}
            className="size-full object-cover"
          />
        ) : (
          <span className="grid size-full place-items-center text-muted-foreground">
            <ImageSquare aria-hidden="true" size={22} />
          </span>
        )}
      </div>

      <div className="min-w-0">
        <div className="grid grid-cols-[minmax(0,1fr)_auto] items-start gap-x-3">
          <div className="min-w-0">
            <Link
              to="/items/$id"
              params={{ id: String(candidate.id) }}
              search={search}
              className="line-clamp-2 text-sm font-bold text-foreground no-underline hover:underline"
            >
              {candidate.title}
            </Link>
            <p className="m-0 mt-1 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-muted-foreground">
              <span>{candidate.category}</span>
              <span aria-hidden="true">·</span>
              <span className="inline-flex items-center gap-1">
                <MapPin aria-hidden="true" size={12} />
                {candidate.location}
              </span>
              <span aria-hidden="true">·</span>
              <span className="inline-flex items-center gap-1">
                <CalendarBlank aria-hidden="true" size={12} />
                {formatDate(candidate.dateOccurred)}
              </span>
            </p>
          </div>

          <span
            className={`shrink-0 rounded-full px-2.5 py-1 text-center ring-1 ${tone.badge}`}
          >
            <span className="block text-sm font-black leading-none tabular-nums">{match.score}</span>
            <span className="mt-0.5 block text-[10px] font-bold uppercase tracking-wide">
              similarity
            </span>
          </span>
        </div>

        <div className="mt-2.5">
          <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted" aria-hidden="true">
            <div className={`h-full rounded-full ${tone.bar}`} style={{ width: `${match.score}%` }} />
          </div>
          <p className={`m-0 mt-1 text-[11px] font-bold ${tone.text}`}>{tone.label}</p>
        </div>

        <ul className="m-0 mt-2 grid list-none gap-1 p-0">
          {match.reasons.map((reason) => (
            <li key={reason} className="flex items-start gap-1.5 text-xs text-muted-foreground">
              <CheckCircle
                aria-hidden="true"
                size={14}
                weight="fill"
                className="mt-0.5 shrink-0 text-[var(--primary)]"
              />
              <span>{reason}</span>
            </li>
          ))}
        </ul>

        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Button
            size="sm"
            variant="outline"
            render={
              <Link
                to="/items/$id"
                params={{ id: String(candidate.id) }}
                search={search}
              />
            }
          >
            {sourceIsLost ? 'View found report' : 'View lost report'}
            <ArrowRight aria-hidden="true" size={14} weight="bold" />
          </Button>
          <span className="text-[11px] text-muted-foreground">
            {sourceIsLost
              ? 'Claim from that page with this lost report attached.'
              : 'You can inspect this report; only its reporter can claim your item.'}
          </span>
        </div>
      </div>
    </article>
  )
}

function similarityTone(score: number) {
  if (score >= 75) {
    return {
      label: 'Strong similarity',
      badge: 'bg-emerald-50 text-emerald-800 ring-emerald-200 dark:bg-emerald-950/40 dark:text-emerald-200 dark:ring-emerald-900',
      bar: 'bg-emerald-500',
      text: 'text-emerald-700 dark:text-emerald-300',
    }
  }
  if (score >= 60) {
    return {
      label: 'Good similarity',
      badge: 'bg-amber-50 text-amber-800 ring-amber-200 dark:bg-amber-950/40 dark:text-amber-200 dark:ring-amber-900',
      bar: 'bg-amber-500',
      text: 'text-amber-700 dark:text-amber-300',
    }
  }
  return {
    label: 'Possible similarity',
    badge: 'bg-muted text-foreground ring-border',
    bar: 'bg-[var(--primary)]',
    text: 'text-muted-foreground',
  }
}
